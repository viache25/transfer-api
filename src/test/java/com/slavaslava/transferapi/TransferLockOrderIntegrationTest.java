package com.slavaslava.transferapi;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.dto.TransferCreationResult;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.TransferRepository;
import com.slavaslava.transferapi.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two opposite transfers (A to B and B to A) committing at the same moment deadlocked in PostgreSQL
 * when each locked its account rows in load order (the k6 load test hit this 1-2 times per run).
 * With {@code hibernate.order_updates} every transfer updates, and so locks, the lower account id
 * first. This test makes the ordering visible: it holds a row lock on the higher id, starts a transfer
 * from the higher to the lower id, and checks that the transfer already holds the lower id's row
 * while it waits for the higher one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferLockOrderIntegrationTest {

    // NO KEY UPDATE: conflicts with the transfer's UPDATE of the row, but not with the KEY SHARE
    // lock its INSERT INTO transfers takes on both accounts for the foreign-key check.
    private static final String LOCK_ROW = "SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void tearDown() {
        transferRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void transferLocksTheLowerAccountIdFirstWhateverItsDirection() throws Exception {
        long lower = accountRepository.save(new Account("Lower", new BigDecimal("100.00"), "EUR")).getId();
        long higher = accountRepository.save(new Account("Higher", new BigDecimal("100.00"), "EUR")).getId();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection(); Connection probe = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            probe.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(LOCK_ROW)) {
                lock.setLong(1, higher);
                lock.executeQuery().close();
            }

            // "from" is loaded first, so a flush in load order would update the higher id first and
            // wait there without ever locking the lower one
            Future<TransferCreationResult> transfer = executor.submit(() -> transferService.createTransfer(
                    new CreateTransferRequest(higher, lower, new BigDecimal("10.00")), UUID.randomUUID().toString()));

            assertThat(waitUntilLocked(probe, lower, Duration.ofSeconds(10)))
                    .as("the transfer holds the lower id's row while it waits for the higher one")
                    .isTrue();
            assertThat(transfer.isDone()).isFalse();

            blocker.rollback();
            assertThat(transfer.get(10, TimeUnit.SECONDS).replayed()).isFalse();
        } finally {
            executor.shutdownNow();
        }
        assertThat(accountRepository.findById(higher).orElseThrow().getBalance()).isEqualByComparingTo("90.00");
        assertThat(accountRepository.findById(lower).orElseThrow().getBalance()).isEqualByComparingTo("110.00");
    }

    // Polls with NOWAIT until the row is locked by someone else (true) or the timeout passes (false).
    private static boolean waitUntilLocked(Connection probe, long id, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (PreparedStatement lock = probe.prepareStatement(LOCK_ROW + " NOWAIT")) {
                lock.setLong(1, id);
                lock.executeQuery().close();
            } catch (SQLException e) {
                probe.rollback();
                if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                    return true;
                }
                throw e;
            }
            probe.rollback(); // got it ourselves: the transfer hasn't locked it (yet), release and look again
            Thread.sleep(20);
        }
        return false;
    }
}
