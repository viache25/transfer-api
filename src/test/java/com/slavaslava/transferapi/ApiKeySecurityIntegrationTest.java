package com.slavaslava.transferapi;

import com.slavaslava.transferapi.config.ApiKeyHasher;
import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Terminal;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.TerminalRepository;
import com.slavaslava.transferapi.repository.TransferRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiKeySecurityIntegrationTest {

    private static final String API_KEY = "test-terminal-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TerminalRepository terminalRepository;

    private Long fromId;
    private Long toId;

    @BeforeEach
    void setUp() {
        fromId = accountRepository.save(new Account("Alice", new BigDecimal("100.00"), "EUR")).getId();
        toId = accountRepository.save(new Account("Bob", new BigDecimal("0.00"), "EUR")).getId();
        terminalRepository.save(new Terminal("test-terminal", ApiKeyHasher.sha256Hex(API_KEY)));
    }

    @AfterEach
    void tearDown() {
        transferRepository.deleteAll();
        accountRepository.deleteAll();
        terminalRepository.deleteAll();
    }

    @Test
    void requestWithoutApiKeyIsRejectedWithProblemDetail() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateTransferRequest(fromId, toId, new BigDecimal("10.00")));

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void requestWithUnknownApiKeyIsRejected() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateTransferRequest(fromId, toId, new BigDecimal("10.00")));

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-API-Key", "not-a-real-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithValidApiKeyIsAccepted() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateTransferRequest(fromId, toId, new BigDecimal("10.00")));

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    // An exception thrown while handling an authorized request makes the container forward to /error
    // (an ERROR dispatch). The API-key filter does not run again there, so before the fix the error
    // page itself was rejected and a deadlock surfaced to the client as "401 Missing or invalid X-API-Key".
    @Test
    void errorDispatchRendersTheRealStatusInsteadOf401() throws Exception {
        mockMvc.perform(get("/error")
                        .accept(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
                            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/transfers");
                            return request;
                        }))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void directRequestToTheErrorPageStillNeedsAnApiKey() throws Exception {
        mockMvc.perform(get("/error").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
