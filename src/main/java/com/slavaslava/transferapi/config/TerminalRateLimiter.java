package com.slavaslava.transferapi.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One token bucket per terminal (capacity = refill per second = the configured limit).
 * Buckets live in process memory: Bucket4j's Redis proxy manager needs a raw Lettuce client
 * that Boot's auto-configuration does not expose, and Redis here is an optional cache, so
 * the limit is per application instance (D6 fallback).
 */
@Component
public class TerminalRateLimiter {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int requestsPerSecond;

    public TerminalRateLimiter(@Value("${app.rate-limit.requests-per-second:20}") int requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
    }

    /** Empty if the request is allowed, otherwise the seconds the terminal should wait (at least 1). */
    public OptionalLong tryAcquire(String terminal) {
        Bucket bucket = buckets.computeIfAbsent(terminal, k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(requestsPerSecond)
                        .refillGreedy(requestsPerSecond, Duration.ofSeconds(1)))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return OptionalLong.empty();
        }
        long seconds = Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds();
        return OptionalLong.of(Math.max(1, seconds + 1));
    }
}
