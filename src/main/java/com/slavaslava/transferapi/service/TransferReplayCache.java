package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.dto.TransferResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * Read-through cache in front of the idempotency lookup. The database stays the source of truth:
 * every Redis failure is logged and swallowed so callers fall back to the DB lookup.
 */
@Component
public class TransferReplayCache {

    private static final Logger log = LoggerFactory.getLogger(TransferReplayCache.class);
    private static final String KEY_PREFIX = "idempotency:transfer:";
    static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public TransferReplayCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public Optional<TransferResponse> find(String idempotencyKey) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, TransferResponse.class));
        } catch (RuntimeException e) {
            log.warn("Idempotency cache read failed, falling back to the database: {}", e.toString());
            return Optional.empty();
        }
    }

    public void put(String idempotencyKey, TransferResponse response) {
        try {
            redis.opsForValue().set(KEY_PREFIX + idempotencyKey, objectMapper.writeValueAsString(response), TTL);
        } catch (RuntimeException e) {
            log.warn("Idempotency cache write failed, continuing without it: {}", e.toString());
        }
    }
}
