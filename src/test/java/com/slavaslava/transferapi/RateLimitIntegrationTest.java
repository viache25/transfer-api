package com.slavaslava.transferapi;

import com.slavaslava.transferapi.config.ApiKeyHasher;
import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Terminal;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.TerminalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.rate-limit.requests-per-second=1")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TerminalRepository terminalRepository;

    private Long accountId;

    @BeforeEach
    void setUp() {
        accountId = accountRepository.save(new Account("Alice", new BigDecimal("100.00"), "EUR")).getId();
        terminalRepository.save(new Terminal("rl-a", ApiKeyHasher.sha256Hex("rl-a-key")));
        terminalRepository.save(new Terminal("rl-b", ApiKeyHasher.sha256Hex("rl-b-key")));
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteAll();
        terminalRepository.deleteAll();
    }

    @Test
    void terminalExceedingItsLimitGetsTooManyRequestsWithRetryAfter() throws Exception {
        int rejected = 0;
        for (int i = 0; i < 5; i++) {
            int status = mockMvc.perform(get("/accounts/{id}", accountId).header("X-API-Key", "rl-a-key"))
                    .andReturn().getResponse().getStatus();
            if (status == 429) {
                rejected++;
            }
        }
        assertThat(rejected).isPositive();

        mockMvc.perform(get("/accounts/{id}", accountId).header("X-API-Key", "rl-a-key"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void limitIsPerTerminal() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/accounts/{id}", accountId).header("X-API-Key", "rl-a-key"));
        }

        mockMvc.perform(get("/accounts/{id}", accountId).header("X-API-Key", "rl-b-key"))
                .andExpect(status().isOk());
    }
}
