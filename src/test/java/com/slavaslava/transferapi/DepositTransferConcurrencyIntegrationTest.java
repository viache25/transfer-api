package com.slavaslava.transferapi;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.dto.DepositRequest;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.DepositRepository;
import com.slavaslava.transferapi.repository.TransferRepository;
import com.slavaslava.transferapi.service.AccountService;
import com.slavaslava.transferapi.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DepositTransferConcurrencyIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private DepositRepository depositRepository;

    private Long fromId;
    private Long toId;

    @BeforeEach
    void setUp() {
        fromId = accountRepository.save(new Account("Alice", new BigDecimal("100.00"), "EUR")).getId();
        toId = accountRepository.save(new Account("Bob", new BigDecimal("0.00"), "EUR")).getId();
    }

    @AfterEach
    void tearDown() {
        transferRepository.deleteAll();
        depositRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void depositRacingATransferOnTheSameAccountNeverSurfacesAsAConcurrentModificationError() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> depositTask = executor.submit(() -> {
            await(startLatch);
            accountService.deposit(toId, new DepositRequest(new BigDecimal("15.00")), UUID.randomUUID().toString());
        });
        Future<?> transferTask = executor.submit(() -> {
            await(startLatch);
            transferService.createTransfer(new CreateTransferRequest(fromId, toId, new BigDecimal("30.00")),
                    UUID.randomUUID().toString());
        });
        startLatch.countDown();

        assertThatCode(() -> {
            depositTask.get();
            transferTask.get();
        }).doesNotThrowAnyException();
        executor.shutdown();

        assertThat(accountRepository.findById(toId).orElseThrow().getBalance()).isEqualByComparingTo("45.00");
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
