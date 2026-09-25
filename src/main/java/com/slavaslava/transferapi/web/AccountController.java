package com.slavaslava.transferapi.web;

import com.slavaslava.transferapi.dto.AccountResponse;
import com.slavaslava.transferapi.dto.CreateAccountRequest;
import com.slavaslava.transferapi.dto.DepositRequest;
import com.slavaslava.transferapi.service.AccountService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return accountService.getAccount(id);
    }

    @PostMapping("/{id}/deposit")
    public AccountResponse deposit(@PathVariable Long id, @Valid @RequestBody DepositRequest request,
                                    @RequestHeader(value = "Idempotency-Key", required = false)
                                    @Parameter(description = "Optional client-generated key that makes a retried deposit credit the account once instead of on every retry.")
                                    @Size(max = 255) String idempotencyKey) {
        String normalizedKey = (idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey;
        return accountService.deposit(id, request, normalizedKey);
    }
}
