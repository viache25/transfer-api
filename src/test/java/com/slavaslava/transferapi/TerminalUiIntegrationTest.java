package com.slavaslava.transferapi;

import com.slavaslava.transferapi.config.ApiKeyHasher;
import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Terminal;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.dto.TransferCreationResult;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.TerminalRepository;
import com.slavaslava.transferapi.repository.TransferRepository;
import com.slavaslava.transferapi.service.TransferService;
import com.slavaslava.transferapi.web.ui.UiMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The terminal UI page (D9) with the real security chain, services and database: API-key login into a
 * session, CSRF protection, a transfer, a retry with the same key and an insufficient-funds message.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TerminalUiIntegrationTest {

    private static final String API_KEY = "ui-test-terminal-key";
    private static final String TERMINAL = "ui-test-terminal";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TerminalRepository terminalRepository;

    @Autowired
    private TransferService transferService;

    private Long alice;
    private Long bob;

    @BeforeEach
    void setUp() {
        alice = accountRepository.save(new Account("Alice", new BigDecimal("100.00"), "EUR")).getId();
        bob = accountRepository.save(new Account("Bob", new BigDecimal("0.00"), "EUR")).getId();
        terminalRepository.save(new Terminal(TERMINAL, ApiKeyHasher.sha256Hex(API_KEY)));
    }

    @AfterEach
    void tearDown() {
        transferRepository.deleteAll();
        accountRepository.deleteAll();
        terminalRepository.deleteAll();
    }

    private static MockHttpServletRequestBuilder transfer(Long from, Long to, String amount, String key) {
        return post("/ui/transfers")
                .with(user(TERMINAL).roles("TERMINAL"))
                .with(csrf())
                .param("fromAccountId", String.valueOf(from))
                .param("toAccountId", String.valueOf(to))
                .param("amount", amount)
                .param("idempotencyKey", key);
    }

    private BigDecimal balance(Long id) {
        return accountRepository.findById(id).orElseThrow().getBalance();
    }

    @Test
    void pagesRedirectToTheLoginFormWithoutASession() throws Exception {
        mockMvc.perform(get("/ui"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/ui/login"));
    }

    @Test
    void anXApiKeyHeaderDoesNotOpenThePages() throws Exception {
        mockMvc.perform(get("/ui").header("X-API-Key", API_KEY))
                .andExpect(redirectedUrlPattern("**/ui/login"));
    }

    @Test
    void loginPageAndStylesheetArePublicAndLockedDownByCsp() throws Exception {
        mockMvc.perform(get("/ui/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"apiKey\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")));
        mockMvc.perform(get("/ui/ui.css")).andExpect(status().isOk());
    }

    @Test
    void validApiKeyStartsASessionThatOpensTheTerminalPage() throws Exception {
        MvcResult login = mockMvc.perform(post("/ui/login").param("apiKey", API_KEY).with(csrf()))
                .andExpect(redirectedUrl("/ui"))
                .andExpect(authenticated().withUsername(TERMINAL).withRoles("TERMINAL"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/ui").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(TERMINAL)))
                .andExpect(content().string(containsString("#" + alice + " Alice (100.00 EUR)")));
    }

    @Test
    void unknownOrMissingApiKeyIsRejected() throws Exception {
        mockMvc.perform(post("/ui/login").param("apiKey", "not-a-real-key").with(csrf()))
                .andExpect(redirectedUrl("/ui/login?error"))
                .andExpect(unauthenticated());
        mockMvc.perform(post("/ui/login").with(csrf()))
                .andExpect(redirectedUrl("/ui/login?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void formsWithoutACsrfTokenAreForbidden() throws Exception {
        mockMvc.perform(post("/ui/login").param("apiKey", API_KEY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/ui/transfers").with(user(TERMINAL).roles("TERMINAL"))
                        .param("fromAccountId", String.valueOf(alice)).param("toAccountId", String.valueOf(bob))
                        .param("amount", "30.00").param("idempotencyKey", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        assertThat(balance(alice)).isEqualByComparingTo("100.00");
    }

    @Test
    void transferIsCreatedAndShownWithItsId() throws Exception {
        MvcResult result = mockMvc.perform(transfer(alice, bob, "30.00", UUID.randomUUID().toString()))
                .andExpect(redirectedUrl("/ui?accountId=" + alice))
                .andReturn();

        TransferCreationResult created = (TransferCreationResult) result.getFlashMap().get("result");
        assertThat(created.replayed()).isFalse();
        assertThat(balance(alice)).isEqualByComparingTo("70.00");
        assertThat(balance(bob)).isEqualByComparingTo("30.00");
        mockMvc.perform(get("/ui").param("accountId", String.valueOf(alice))
                        .with(user(TERMINAL).roles("TERMINAL")).flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Created (201)")))
                .andExpect(content().string(containsString("data-transfer-id=\"" + created.transfer().id() + "\"")));
    }

    @Test
    void retryWithTheSameKeyReplaysTheSameTransferAndMovesMoneyOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        MvcResult first = mockMvc.perform(transfer(alice, bob, "30.00", key)).andReturn();
        MvcResult retry = mockMvc.perform(transfer(alice, bob, "30.00", key)).andReturn();

        TransferCreationResult original = (TransferCreationResult) first.getFlashMap().get("result");
        TransferCreationResult replay = (TransferCreationResult) retry.getFlashMap().get("result");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transfer().id()).isEqualTo(original.transfer().id());
        assertThat(balance(alice)).isEqualByComparingTo("70.00");
        assertThat(transferRepository.count()).isEqualTo(1);
    }

    @Test
    void insufficientFundsIsShownAsAnErrorMessage() throws Exception {
        MvcResult result = mockMvc.perform(transfer(bob, alice, "50.00", UUID.randomUUID().toString()))
                .andExpect(redirectedUrl("/ui?accountId=" + bob))
                .andReturn();

        assertThat(((UiMessage) result.getFlashMap().get("error")).title()).isEqualTo("Insufficient funds");
        mockMvc.perform(get("/ui").with(user(TERMINAL).roles("TERMINAL")).flashAttrs(result.getFlashMap()))
                .andExpect(content().string(containsString("Insufficient funds")))
                .andExpect(content().string(containsString("Account " + bob + " has insufficient funds")));
        assertThat(balance(bob)).isEqualByComparingTo("0.00");
    }

    @Test
    void historyShowsTheSelectedAccountsTransfers() throws Exception {
        String key = UUID.randomUUID().toString();
        long id = transferService.createTransfer(new CreateTransferRequest(alice, bob, new BigDecimal("12.50")), key)
                .transfer().id();

        mockMvc.perform(get("/ui").param("accountId", String.valueOf(bob)).with(user(TERMINAL).roles("TERMINAL")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("History of account #" + bob)))
                .andExpect(content().string(containsString("data-transfer-id=\"" + id + "\"")))
                .andExpect(content().string(containsString(key)));
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        mockMvc.perform(post("/ui/logout").with(user(TERMINAL).roles("TERMINAL")).with(csrf()))
                .andExpect(redirectedUrl("/ui/login?logout"))
                .andExpect(unauthenticated());
    }
}
