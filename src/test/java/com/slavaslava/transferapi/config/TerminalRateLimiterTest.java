package com.slavaslava.transferapi.config;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalRateLimiterTest {

    @Test
    void allowsRequestsUpToTheLimitThenAsksTheTerminalToWait() {
        TerminalRateLimiter limiter = new TerminalRateLimiter(2);

        assertThat(limiter.tryAcquire("t1")).isEmpty();
        assertThat(limiter.tryAcquire("t1")).isEmpty();
        OptionalLong retryAfter = limiter.tryAcquire("t1");

        // A token refills every 500 ms, so the client is told to wait at least one whole second.
        assertThat(retryAfter).isPresent();
        assertThat(retryAfter.getAsLong()).isBetween(1L, 2L);
    }

    @Test
    void eachTerminalHasItsOwnBucket() {
        TerminalRateLimiter limiter = new TerminalRateLimiter(1);

        assertThat(limiter.tryAcquire("busy")).isEmpty();
        assertThat(limiter.tryAcquire("busy")).isPresent();

        assertThat(limiter.tryAcquire("quiet")).isEmpty();
    }
}
