package com.slavaslava.transferapi.repository;

import com.slavaslava.transferapi.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
