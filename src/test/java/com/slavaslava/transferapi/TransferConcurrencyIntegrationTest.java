package com.slavaslava.transferapi;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.exception.InsufficientFundsException;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.TransferRepository;
import com.slavaslava.transferapi.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransferConcurrencyIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

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
        accountRepository.deleteAll();
    }

    @Test
    void concurrentTransfersFromSameAccountNeverOverdrawOrDoubleSpend() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<Boolean> result1 = executor.submit(transferTask(startLatch, new BigDecimal("80.00")));
        Future<Boolean> result2 = executor.submit(transferTask(startLatch, new BigDecimal("80.00")));
        startLatch.countDown();

        boolean succeeded1 = result1.get();
        boolean succeeded2 = result2.get();
        executor.shutdown();

        assertThat(succeeded1 ^ succeeded2).as("exactly one of the two 80.00 transfers should succeed").isTrue();
        assertThat(accountRepository.findById(fromId).orElseThrow().getBalance()).isEqualByComparingTo("20.00");
        assertThat(accountRepository.findById(toId).orElseThrow().getBalance()).isEqualByComparingTo("80.00");
    }

    private Callable<Boolean> transferTask(CountDownLatch startLatch, BigDecimal amount) {
        return () -> {
            startLatch.await();
            try {
                transferService.createTransfer(new CreateTransferRequest(fromId, toId, amount),
                        UUID.randomUUID().toString());
                return true;
            } catch (InsufficientFundsException e) {
                return false;
            }
        };
    }
}
