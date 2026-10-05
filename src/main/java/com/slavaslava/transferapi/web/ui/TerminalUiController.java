package com.slavaslava.transferapi.web.ui;

import com.slavaslava.transferapi.exception.AccountNotFoundException;
import com.slavaslava.transferapi.exception.CurrencyMismatchException;
import com.slavaslava.transferapi.exception.IdempotencyKeyReuseException;
import com.slavaslava.transferapi.exception.InsufficientFundsException;
import com.slavaslava.transferapi.exception.SameAccountTransferException;
import com.slavaslava.transferapi.service.AccountService;
import com.slavaslava.transferapi.service.TransferService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.stream.Collectors;

/**
 * The terminal page (D9): one server-rendered Thymeleaf page, no JavaScript. It exists as a target for the
 * Playwright UI tests, not as a product. It calls the same services as the REST controllers, so a transfer
 * made here goes through the same idempotency, retry and locking logic. Posts follow post/redirect/get, and
 * the outcome travels to the next page load as a flash attribute.
 */
@Hidden
@Controller
@RequestMapping("/ui")
public class TerminalUiController {

    static final int ACCOUNTS_SHOWN = 50;
    static final int HISTORY_SIZE = 20;

    private final AccountService accountService;
    private final TransferService transferService;

    public TerminalUiController(AccountService accountService, TransferService transferService) {
        this.accountService = accountService;
        this.transferService = transferService;
    }

    @GetMapping("/login")
    public String login() {
        return "ui/login";
    }

    @GetMapping
    public String terminal(@RequestParam(required = false) Long accountId, Authentication authentication, Model model) {
        model.addAttribute("terminal", authentication.getName());
        model.addAttribute("accounts", accountService.listNewestAccounts(ACCOUNTS_SHOWN));
        model.addAttribute("form", TransferForm.withNewKey());
        if (accountId != null) {
            model.addAttribute("historyAccountId", accountId);
            model.addAttribute("history", transferService.listTransfers(accountId,
                    PageRequest.of(0, HISTORY_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent());
        }
        return "ui/terminal";
    }

    @PostMapping("/transfers")
    public String transfer(@Valid TransferForm form, BindingResult binding, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            redirect.addFlashAttribute("error", new UiMessage("Invalid transfer", describe(binding)));
            return "redirect:/ui";
        }
        // the attempt is offered for a retry with the same key, whatever its outcome
        redirect.addFlashAttribute("last", form);
        try {
            redirect.addFlashAttribute("result", transferService.createTransfer(form.toRequest(), form.idempotencyKey()));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", describe(e));
        }
        redirect.addAttribute("accountId", form.fromAccountId());
        return "redirect:/ui";
    }

    private static String describe(BindingResult binding) {
        return binding.getFieldErrors().stream()
                .map(TerminalUiController::describe)
                .sorted()
                .collect(Collectors.joining("; "));
    }

    private static String describe(FieldError error) {
        return error.isBindingFailure()
                ? error.getField() + ": not a valid value"
                : error.getField() + ": " + error.getDefaultMessage();
    }

    // the same outcomes GlobalExceptionHandler maps to problem+json for the REST API; anything else propagates
    private static UiMessage describe(RuntimeException e) {
        return switch (e) {
            case InsufficientFundsException x -> new UiMessage("Insufficient funds", x.getMessage());
            case AccountNotFoundException x -> new UiMessage("Account not found", x.getMessage());
            case CurrencyMismatchException x -> new UiMessage("Currency mismatch", x.getMessage());
            case SameAccountTransferException x -> new UiMessage("Invalid transfer", x.getMessage());
            case IdempotencyKeyReuseException x -> new UiMessage("Idempotency-Key reuse", x.getMessage());
            case ConcurrencyFailureException x -> new UiMessage("Concurrent modification",
                    "The account was modified by a concurrent operation; retry with the same key.");
            default -> throw e;
        };
    }
}
