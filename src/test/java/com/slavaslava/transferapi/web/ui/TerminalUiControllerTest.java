package com.slavaslava.transferapi.web.ui;

import com.slavaslava.transferapi.dto.AccountResponse;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.dto.TransferCreationResult;
import com.slavaslava.transferapi.dto.TransferResponse;
import com.slavaslava.transferapi.exception.AccountNotFoundException;
import com.slavaslava.transferapi.exception.CurrencyMismatchException;
import com.slavaslava.transferapi.exception.IdempotencyKeyReuseException;
import com.slavaslava.transferapi.exception.InsufficientFundsException;
import com.slavaslava.transferapi.exception.SameAccountTransferException;
import com.slavaslava.transferapi.service.AccountService;
import com.slavaslava.transferapi.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.FlashMap;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The terminal page controller and its Thymeleaf templates, without Spring Security or a database
 * (standalone MockMvc, mocked services). The security wiring and the real services are covered by
 * TerminalUiIntegrationTest.
 */
class TerminalUiControllerTest {

    private static final String KEY = "7d0f6a52-0000-4000-8000-000000000001";

    private final AccountService accountService = mock(AccountService.class);
    private final TransferService transferService = mock(TransferService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver templates = new ClassLoaderTemplateResolver();
        templates.setPrefix("templates/");
        templates.setSuffix(".html");
        templates.setTemplateMode(TemplateMode.HTML);
        templates.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templates);
        ThymeleafViewResolver views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8");
        mockMvc = MockMvcBuilders.standaloneSetup(new TerminalUiController(accountService, transferService))
                .setViewResolvers(views)
                .build();
        when(accountService.listNewestAccounts(anyInt())).thenReturn(List.of(
                new AccountResponse(2L, "Bob", new BigDecimal("0.00"), "EUR"),
                new AccountResponse(1L, "Alice", new BigDecimal("100.00"), "EUR")));
        when(transferService.listTransfers(eq(1L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
    }

    private static MockHttpServletRequestBuilder asTerminal(MockHttpServletRequestBuilder request) {
        return request.principal(UsernamePasswordAuthenticationToken.authenticated("terminal-7", null, List.of()));
    }

    private static MockHttpServletRequestBuilder transferForm(String amount) {
        return asTerminal(post("/ui/transfers"))
                .param("fromAccountId", "1")
                .param("toAccountId", "2")
                .param("amount", amount)
                .param("idempotencyKey", KEY);
    }

    private static TransferResponse transfer(long id) {
        return new TransferResponse(id, 1L, 2L, new BigDecimal("30.00"), "COMPLETED", KEY, Instant.parse("2026-10-05T10:00:00Z"));
    }

    private String render(FlashMap flash) throws Exception {
        MockHttpServletRequestBuilder request = asTerminal(get("/ui")).param("accountId", "1");
        if (!flash.isEmpty()) {
            request.flashAttrs(flash);
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void loginPageAsksForTheApiKey() throws Exception {
        mockMvc.perform(get("/ui/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("ui/login"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"apiKey\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Unknown API key"))));

        mockMvc.perform(get("/ui/login").param("error", ""))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Unknown API key")));
    }

    @Test
    void terminalPageListsAccountsAndOffersAFreshKeyButNoHistoryYet() throws Exception {
        String html = mockMvc.perform(asTerminal(get("/ui")))
                .andExpect(status().isOk())
                .andExpect(view().name("ui/terminal"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("terminal-7", "#1 Alice (100.00 EUR)", "#2 Bob (0.00 EUR)", "100.00 EUR")
                .doesNotContain("data-testid=\"history\"", "data-testid=\"result\"", "data-testid=\"retry\"");
        assertThat(html).containsPattern("name=\"idempotencyKey\"[^>]*value=\"[0-9a-f-]{36}\"");
    }

    @Test
    void everyPageLoadOffersADifferentKey() throws Exception {
        String first = mockMvc.perform(asTerminal(get("/ui"))).andReturn().getModelAndView().getModel().get("form").toString();
        String second = mockMvc.perform(asTerminal(get("/ui"))).andReturn().getModelAndView().getModel().get("form").toString();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void historyOfTheSelectedAccountIsShownNewestFirst() throws Exception {
        when(transferService.listTransfers(eq(1L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(transfer(5L))));

        String html = render(new FlashMap());

        assertThat(html).contains("History of account #1", "data-transfer-id=\"5\"", KEY, "2026-10-05T10:00:00Z");
    }

    @Test
    void emptyStatesAreExplained() throws Exception {
        when(accountService.listNewestAccounts(anyInt())).thenReturn(List.of());
        when(transferService.listTransfers(eq(1L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertThat(render(new FlashMap())).contains("No accounts yet", "No transfers yet");
    }

    @Test
    void createdTransferIsShownWithItsIdAndCanBeRetriedWithTheSameKey() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, new BigDecimal("30.00"));
        when(transferService.createTransfer(request, KEY)).thenReturn(new TransferCreationResult(transfer(5L), false));

        MvcResult result = mockMvc.perform(transferForm("30.00"))
                .andExpect(redirectedUrl("/ui?accountId=1"))
                .andExpect(flash().attributeExists("result", "last"))
                .andExpect(flash().attributeCount(2))
                .andReturn();
        String html = render(result.getFlashMap());

        assertThat(html).contains("Created (201)", "data-testid=\"transfer-id\">5<", "data-testid=\"retry\"")
                .containsPattern("name=\"idempotencyKey\" value=\"" + KEY + "\"")
                .doesNotContain("nothing was executed again");
    }

    @Test
    void replayedTransferIsShownAsAReplay() throws Exception {
        when(transferService.createTransfer(any(), eq(KEY))).thenReturn(new TransferCreationResult(transfer(5L), true));

        MvcResult result = mockMvc.perform(transferForm("30.00")).andReturn();

        assertThat(render(result.getFlashMap())).contains("Replayed (200)", "data-testid=\"transfer-id\">5<",
                "nothing was executed again");
    }

    static Stream<Arguments> businessErrors() {
        return Stream.of(
                Arguments.of(new InsufficientFundsException(1L), "Insufficient funds"),
                Arguments.of(new AccountNotFoundException(1L), "Account not found"),
                Arguments.of(new CurrencyMismatchException("EUR", "USD"), "Currency mismatch"),
                Arguments.of(new SameAccountTransferException(1L), "Invalid transfer"),
                Arguments.of(new IdempotencyKeyReuseException(KEY), "Idempotency-Key reuse"),
                Arguments.of(new CannotAcquireLockException("deadlock detected"), "Concurrent modification"));
    }

    @ParameterizedTest
    @MethodSource("businessErrors")
    void businessErrorsAreShownAsMessagesWithARetryButton(RuntimeException error, String title) throws Exception {
        when(transferService.createTransfer(any(), eq(KEY))).thenThrow(error);

        MvcResult result = mockMvc.perform(transferForm("30.00"))
                .andExpect(redirectedUrl("/ui?accountId=1"))
                .andExpect(flash().attributeExists("error", "last"))
                .andReturn();

        assertThat(((UiMessage) result.getFlashMap().get("error")).title()).isEqualTo(title);
        assertThat(render(result.getFlashMap())).contains("data-testid=\"error\"", title, "data-testid=\"retry\"");
    }

    @Test
    void unexpectedErrorsAreNotSwallowed() {
        when(transferService.createTransfer(any(), eq(KEY))).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> mockMvc.perform(transferForm("30.00")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidFormIsRejectedWithoutCallingTheServiceOrOfferingARetry() throws Exception {
        MvcResult result = mockMvc.perform(asTerminal(post("/ui/transfers"))
                        .param("fromAccountId", "1")
                        .param("amount", "-5")
                        .param("idempotencyKey", ""))
                .andExpect(redirectedUrl("/ui"))
                .andExpect(flash().attributeCount(1))
                .andReturn();

        UiMessage error = (UiMessage) result.getFlashMap().get("error");
        assertThat(error.title()).isEqualTo("Invalid transfer");
        assertThat(error.detail()).contains("amount:", "idempotencyKey:", "toAccountId:");
        verifyNoInteractions(transferService);
    }

    @Test
    void unparsableAmountIsReportedAsInvalidValue() throws Exception {
        MvcResult result = mockMvc.perform(transferForm("thirty")).andExpect(redirectedUrl("/ui")).andReturn();

        assertThat(((UiMessage) result.getFlashMap().get("error")).detail()).isEqualTo("amount: not a valid value");
        verifyNoInteractions(transferService);
    }
}
