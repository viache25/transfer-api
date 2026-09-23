package com.slavaslava.transferapi.config;

import com.slavaslava.transferapi.domain.Terminal;
import com.slavaslava.transferapi.repository.TerminalRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final TerminalRepository terminalRepository;

    public ApiKeyAuthenticationFilter(TerminalRepository terminalRepository) {
        this.terminalRepository = terminalRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            Optional<Terminal> terminal = terminalRepository.findByApiKeyHash(ApiKeyHasher.sha256Hex(apiKey));
            terminal.ifPresent(t -> SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(
                            t.getName(), null, List.of(new SimpleGrantedAuthority("ROLE_TERMINAL")))));
        }
        filterChain.doFilter(request, response);
    }
}
