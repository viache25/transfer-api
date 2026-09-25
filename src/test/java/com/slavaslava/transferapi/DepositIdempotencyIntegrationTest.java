package com.slavaslava.transferapi;

import com.slavaslava.transferapi.config.ApiKeyHasher;
import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Terminal;
import com.slavaslava.transferapi.dto.DepositRequest;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.DepositRepository;
import com.slavaslava.transferapi.repository.TerminalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DepositIdempotencyIntegrationTest {

    private static final String API_KEY = "test-terminal-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DepositRepository depositRepository;

    @Autowired
    private TerminalRepository terminalRepository;

    private Long accountId;

    @BeforeEach
    void setUp() {
        accountId = accountRepository.save(new Account("Alice", new BigDecimal("100.00"), "EUR")).getId();
        terminalRepository.save(new Terminal("test-terminal", ApiKeyHasher.sha256Hex(API_KEY)));
    }

    @AfterEach
    void tearDown() {
        depositRepository.deleteAll();
        accountRepository.deleteAll();
        terminalRepository.deleteAll();
    }

    @Test
    void retryingWithSameIdempotencyKeyCreditsOnce() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("25.00")));

        mockMvc.perform(post("/accounts/{id}/deposit", accountId)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/accounts/{id}/deposit", accountId)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("125.00");
        assertThat(depositRepository.count()).isEqualTo(1);
    }

    @Test
    void reusingKeyWithDifferentPayloadIsRejectedWithoutExecuting() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/accounts/{id}/deposit", accountId)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("25.00")))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/accounts/{id}/deposit", accountId)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("99.00")))))
                .andExpect(status().isUnprocessableContent());

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("125.00");
        assertThat(depositRepository.count()).isEqualTo(1);
    }

    @Test
    void depositWithoutIdempotencyKeyStillWorksAndLeavesNoDepositRecord() throws Exception {
        String body = objectMapper.writeValueAsString(new DepositRequest(new BigDecimal("25.00")));

        mockMvc.perform(post("/accounts/{id}/deposit", accountId)
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("125.00");
        assertThat(depositRepository.count()).isEqualTo(0);
    }
}
