package com.slavaslava.transferapi.repository;

import com.slavaslava.transferapi.domain.Deposit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepositRepository extends JpaRepository<Deposit, Long> {

    Optional<Deposit> findByIdempotencyKey(String idempotencyKey);
}
