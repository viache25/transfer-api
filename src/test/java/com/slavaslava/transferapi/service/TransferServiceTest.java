package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.domain.Transfer;
import com.slavaslava.transferapi.domain.TransferStatus;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.dto.TransferCreationResult;
import com.slavaslava.transferapi.exception.IdempotencyKeyReuseException;
import com.slavaslava.transferapi.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private TransferTransactionExecutor transactionExecutor;

    private TransferService transferService;

    private final CreateTransferRequest request = new CreateTransferRequest(1L, 2L, new BigDecimal("10.00"));

    @BeforeEach
    void setUp() {
        transferService = new TransferService(transferRepository, transactionExecutor);
    }

    @Test
    void returnsStoredResultWithoutExecutingWhenIdempotencyKeyAlreadySeen() {
        when(transferRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(transfer(1L, "10.00")));

        TransferCreationResult result = transferService.createTransfer(request, "key-1");

        assertThat(result.transfer().id()).isEqualTo(1L);
        assertThat(result.replayed()).isTrue();
        verify(transactionExecutor, never()).execute(any(), any());
    }

    @Test
    void rejectsKeyReuseWithDifferentPayload() {
        when(transferRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(transfer(1L, "99.00")));

        assertThatThrownBy(() -> transferService.createTransfer(request, "key-1"))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        verify(transactionExecutor, never()).execute(any(), any());
    }

    @Test
    void executesOnceWhenIdempotencyKeyIsNew() {
        when(transferRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(transactionExecutor.execute(request, "key-1")).thenReturn(transfer(5L, "10.00"));

        TransferCreationResult result = transferService.createTransfer(request, "key-1");

        assertThat(result.transfer().id()).isEqualTo(5L);
        assertThat(result.replayed()).isFalse();
        verify(transactionExecutor, times(1)).execute(request, "key-1");
    }

    @Test
    void retriesOnOptimisticLockFailureAndSucceedsBeforeExhaustingAttempts() {
        when(transferRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(transactionExecutor.execute(request, "key-1"))
                .thenThrow(new ObjectOptimisticLockingFailureException(Transfer.class, 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException(Transfer.class, 1L))
                .thenReturn(transfer(7L, "10.00"));

        TransferCreationResult result = transferService.createTransfer(request, "key-1");

        assertThat(result.transfer().id()).isEqualTo(7L);
        verify(transactionExecutor, times(3)).execute(request, "key-1");
    }

    @Test
    void givesUpAfterExhaustingOptimisticLockRetries() {
        when(transferRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(transactionExecutor.execute(request, "key-1"))
                .thenThrow(new ObjectOptimisticLockingFailureException(Transfer.class, 1L));

        assertThatThrownBy(() -> transferService.createTransfer(request, "key-1"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(transactionExecutor, times(3)).execute(request, "key-1");
    }

    @Test
    void returnsRacingResultWhenConcurrentInsertWithSameKeyWinsFirst() {
        when(transferRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(transfer(9L, "10.00")));
        when(transactionExecutor.execute(request, "key-1"))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency key"));

        TransferCreationResult result = transferService.createTransfer(request, "key-1");

        assertThat(result.transfer().id()).isEqualTo(9L);
        assertThat(result.replayed()).isTrue();
    }

    @Test
    void rethrowsWhenDataIntegrityViolationIsNotAnIdempotencyKeyRace() {
        when(transferRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(transactionExecutor.execute(request, "key-1"))
                .thenThrow(new DataIntegrityViolationException("some other constraint"));

        assertThatThrownBy(() -> transferService.createTransfer(request, "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Transfer transfer(Long id, String amount) {
        Transfer transfer = new Transfer(1L, 2L, new BigDecimal(amount), TransferStatus.COMPLETED, "key-1");
        ReflectionTestUtils.setField(transfer, "id", id);
        return transfer;
    }
}
