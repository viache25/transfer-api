package com.slavaslava.transferapi.config;

import com.slavaslava.transferapi.repository.TerminalRepository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Checks the API key a terminal typed into the /ui login form, exactly like {@link ApiKeyAuthenticationFilter}
 * checks the {@code X-API-Key} header: SHA-256 of the key, looked up in {@code terminals}. The key arrives as
 * the credentials of the form-login token; the principal of the result is the terminal's name.
 */
public class TerminalApiKeyAuthenticationProvider implements AuthenticationProvider {

    private final TerminalRepository terminalRepository;

    public TerminalApiKeyAuthenticationProvider(TerminalRepository terminalRepository) {
        this.terminalRepository = terminalRepository;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        Object credentials = authentication.getCredentials();
        String apiKey = credentials == null ? "" : credentials.toString();
        if (apiKey.isBlank()) {
            throw new BadCredentialsException("API key is required");
        }
        return terminalRepository.findByApiKeyHash(ApiKeyHasher.sha256Hex(apiKey))
                .map(ApiKeyAuthenticationFilter::authenticated)
                .orElseThrow(() -> new BadCredentialsException("Unknown API key"));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
