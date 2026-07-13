package com.slavaslava.transferapi;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.TransferRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class TransferIdempotencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void retryingWithSameIdempotencyKeyReturnsSameTransferAndDoesNotDoubleDebit() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(new CreateTransferRequest(fromId, toId, new BigDecimal("30.00")));

        MvcResult first = mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.get("id").asLong()).isEqualTo(firstJson.get("id").asLong());

        assertThat(accountRepository.findById(fromId).orElseThrow().getBalance()).isEqualByComparingTo("70.00");
        assertThat(accountRepository.findById(toId).orElseThrow().getBalance()).isEqualByComparingTo("30.00");
        assertThat(transferRepository.count()).isEqualTo(1);
    }

    @Test
    void reusingKeyWithDifferentPayloadIsRejectedWithoutExecuting() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTransferRequest(fromId, toId, new BigDecimal("30.00")))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateTransferRequest(fromId, toId, new BigDecimal("55.00")))))
                .andExpect(status().isUnprocessableContent());

        assertThat(accountRepository.findById(fromId).orElseThrow().getBalance()).isEqualByComparingTo("70.00");
        assertThat(transferRepository.count()).isEqualTo(1);
    }
}
