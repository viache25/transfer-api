package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.dto.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferReplayCacheTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private TransferReplayCache cache;

    private final TransferResponse response = new TransferResponse(
            7L, 1L, 2L, new BigDecimal("10.00"), "COMPLETED", "key-1", Instant.parse("2026-01-01T10:00:00.123456Z"));

    @BeforeEach
    void setUp() {
        cache = new TransferReplayCache(redis, jsonMapper);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void roundTripsResponseThroughJson() {
        cache.put("key-1", response);

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("idempotency:transfer:key-1"), json.capture(), eq(TransferReplayCache.TTL));

        when(valueOps.get("idempotency:transfer:key-1")).thenReturn(json.getValue());
        assertThat(cache.find("key-1")).contains(response);
    }

    @Test
    void missReturnsEmpty() {
        when(valueOps.get(anyString())).thenReturn(null);

        assertThat(cache.find("key-1")).isEqualTo(Optional.empty());
    }

    @Test
    void readFailureFallsBackToEmpty() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.find("key-1")).isEmpty();
    }

    @Test
    void corruptEntryFallsBackToEmpty() {
        when(valueOps.get(anyString())).thenReturn("{not json");

        assertThat(cache.find("key-1")).isEmpty();
    }

    @Test
    void writeFailureIsSwallowed() {
        doThrow(new RedisConnectionFailureException("down")).when(valueOps).set(anyString(), any(), any(java.time.Duration.class));

        assertThatCode(() -> cache.put("key-1", response)).doesNotThrowAnyException();
    }
}
