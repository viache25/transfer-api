package com.slavaslava.transferapi;

import com.slavaslava.transferapi.config.ApiKeyHasher;
import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Terminal;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.TerminalRepository;
import com.slavaslava.transferapi.repository.TransferRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TransferReplayCacheIntegrationTest {

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

    @Autowired
    private StringRedisTemplate redis;

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
    void replayIsServedFromRedisBeforeTheDatabase() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(new CreateTransferRequest(fromId, toId, new BigDecimal("30.00")));

        MvcResult first = perform(key, body).andReturn();
        assertThat(redis.opsForValue().get("idempotency:transfer:" + key)).isNotNull();

        // the row is gone, so only the cache can still answer the replay
        transferRepository.deleteAll();

        MvcResult second = mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", key)
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.get("id").asLong()).isEqualTo(firstJson.get("id").asLong());
    }

    @Test
    void reusingCachedKeyWithDifferentPayloadIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        perform(key, objectMapper.writeValueAsString(new CreateTransferRequest(fromId, toId, new BigDecimal("30.00"))));

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", key)
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTransferRequest(fromId, toId, new BigDecimal("55.00")))))
                .andExpect(status().isUnprocessableContent());
    }

    private org.springframework.test.web.servlet.ResultActions perform(String key, String body) throws Exception {
        return mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", key)
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }
}
