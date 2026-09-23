package com.slavaslava.transferapi.repository;

import com.slavaslava.transferapi.domain.Terminal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TerminalRepository extends JpaRepository<Terminal, Long> {

    Optional<Terminal> findByApiKeyHash(String apiKeyHash);
}
