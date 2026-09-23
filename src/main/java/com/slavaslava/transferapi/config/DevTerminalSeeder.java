package com.slavaslava.transferapi.config;

import com.slavaslava.transferapi.domain.Terminal;
import com.slavaslava.transferapi.repository.TerminalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevTerminalSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevTerminalSeeder.class);

    public static final String DEV_API_KEY = "dev-local-terminal-key";
    private static final String DEV_TERMINAL_NAME = "dev-terminal";

    private final TerminalRepository terminalRepository;

    public DevTerminalSeeder(TerminalRepository terminalRepository) {
        this.terminalRepository = terminalRepository;
    }

    @Override
    public void run(String... args) {
        String hash = ApiKeyHasher.sha256Hex(DEV_API_KEY);
        if (terminalRepository.findByApiKeyHash(hash).isEmpty()) {
            terminalRepository.save(new Terminal(DEV_TERMINAL_NAME, hash));
            log.info("Seeded dev terminal '{}' with X-API-Key: {}", DEV_TERMINAL_NAME, DEV_API_KEY);
        }
    }
}
