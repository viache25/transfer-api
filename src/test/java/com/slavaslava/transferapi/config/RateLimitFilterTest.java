package com.slavaslava.transferapi.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private final TerminalRateLimiter rateLimiter = mock(TerminalRateLimiter.class);
    private final RateLimitFilter filter = new RateLimitFilter(rateLimiter, JsonMapper.builder().build());
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unauthenticatedRequestPassesThroughWithoutUsingABucket() throws Exception {
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void authenticatedTerminalWithinItsLimitPassesThrough() throws Exception {
        authenticateAs("terminal-1");
        when(rateLimiter.tryAcquire("terminal-1")).thenReturn(OptionalLong.empty());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void terminalOverItsLimitGets429ProblemWithRetryAfterAndTheChainStops() throws Exception {
        authenticateAs("terminal-1");
        when(rateLimiter.tryAcquire("terminal-1")).thenReturn(OptionalLong.of(3));

        filter.doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"status\":429")
                .contains("Too Many Requests")
                .contains("retry after 3s");
    }

    private static void authenticateAs(String terminal) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(terminal, null, List.of()));
    }
}
