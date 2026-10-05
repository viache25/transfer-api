package com.slavaslava.transferapi.config;

import com.slavaslava.transferapi.repository.TerminalRepository;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/health",
            "/actuator/prometheus",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /**
     * The terminal UI pages under /ui (D9). A browser can't attach X-API-Key to page loads and form posts,
     * so the terminal types its API key once into a login form. {@link TerminalApiKeyAuthenticationProvider}
     * checks it exactly like the header (SHA-256 hash lookup in terminals), and the authenticated terminal
     * is kept in a server-side HTTP session. The key itself is never stored in the browser: the session
     * cookie only carries a random session id (HttpOnly, SameSite=Strict), and the id is changed at login.
     * Because the session cookie is sent automatically, every form post is CSRF-protected (Spring Security's
     * default, Thymeleaf adds the token to each th:action form). This chain only matches /ui/**; the JSON API
     * stays stateless and accepts nothing but X-API-Key, so a UI session can't be used to call it.
     */
    @Bean
    @Order(1)
    SecurityFilterChain uiSecurityFilterChain(HttpSecurity http, TerminalRepository terminalRepository) throws Exception {
        http
                .securityMatcher("/ui/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/ui/ui.css").permitAll()
                        .anyRequest().hasRole("TERMINAL"))
                .authenticationManager(new ProviderManager(new TerminalApiKeyAuthenticationProvider(terminalRepository)))
                // the form has one field, the key, posted as the "password"; there is no username field
                .formLogin(form -> form
                        .loginPage("/ui/login")
                        .passwordParameter("apiKey")
                        .defaultSuccessUrl("/ui", true)
                        .failureUrl("/ui/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/ui/logout")
                        .logoutSuccessUrl("/ui/login?logout")
                        .permitAll())
                // no scripts at all on these pages: only the same-origin stylesheet and same-origin form posts
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'none'; style-src 'self'; img-src 'self' data:; form-action 'self'; "
                                + "frame-ancestors 'none'; base-uri 'none'")));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, TerminalRepository terminalRepository,
                                             RestAuthenticationEntryPoint entryPoint,
                                             TerminalRateLimiter rateLimiter, ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> auth
                        // the container's error dispatch for an exception thrown by an already-authorized
                        // request; without this, every unhandled 5xx was rendered as a misleading 401
                        // (the API-key filter doesn't run again on the ERROR dispatch). A direct request
                        // to /error is a REQUEST dispatch and still needs a key.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
                .addFilterBefore(new ApiKeyAuthenticationFilter(terminalRepository), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter, objectMapper), ApiKeyAuthenticationFilter.class);
        return http.build();
    }
}
