package com.slavaslava.transferapi.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.slavaslava.transferapi.config.ApiKeyHasher;
import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Terminal;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.TerminalRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Drives the terminal page (/ui) in a headless Chromium, the way a person at the terminal would: sign in
 * with the API key, send a transfer, retry it with the same key, run into insufficient funds. The app runs
 * on a random port with its own PostgreSQL and Redis (Testcontainers).
 *
 * <p>Every test records a Playwright trace. When a test fails, the trace and a full-page screenshot are
 * written to build/reports/playwright/ (open the trace with {@code npx playwright show-trace <file>} or at
 * trace.playwright.dev); passing tests discard them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(UiTestContainers.class)
class TerminalUiTest {

    private static final String API_KEY = "ui-test-terminal-key";
    private static final String TERMINAL = "ui-test-terminal";
    private static final Path REPORTS = Path.of(System.getProperty("playwright.reports", "build/reports/playwright"));

    private static Playwright playwright;
    private static Browser browser;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TerminalRepository terminalRepository;

    @Autowired
    private AccountRepository accountRepository;

    private BrowserContext context;
    private Page page;

    // Runs right after the test method, before @AfterEach closes the browser context.
    @RegisterExtension
    final AfterTestExecutionCallback keepTraceOfFailures = extension -> {
        if (context == null) {
            return;
        }
        if (extension.getExecutionException().isPresent()) {
            String name = extension.getRequiredTestMethod().getName();
            Files.createDirectories(REPORTS);
            page.screenshot(new Page.ScreenshotOptions().setPath(REPORTS.resolve(name + ".png")).setFullPage(true));
            context.tracing().stop(new Tracing.StopOptions().setPath(REPORTS.resolve(name + "-trace.zip")));
        } else {
            context.tracing().stop();
        }
    };

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void closeBrowser() {
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void openPage() {
        String hash = ApiKeyHasher.sha256Hex(API_KEY);
        if (terminalRepository.findByApiKeyHash(hash).isEmpty()) {
            terminalRepository.save(new Terminal(TERMINAL, hash));
        }
        context = browser.newContext(new Browser.NewContextOptions().setBaseURL("http://localhost:" + port));
        context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        if (context != null) {
            context.close();
        }
    }

    private long account(String owner, String balance) {
        return accountRepository.save(new Account(owner, new BigDecimal(balance), "EUR")).getId();
    }

    private void signIn() {
        page.navigate("/ui");
        assertThat(page).hasURL(Pattern.compile(".*/ui/login$"));
        page.getByLabel("API key").fill(API_KEY);
        page.getByTestId("sign-in").click();
        assertThat(page.getByTestId("terminal-name")).hasText(TERMINAL);
    }

    private void sendTransfer(long from, long to, String amount) {
        page.locator("#fromAccountId").selectOption(String.valueOf(from));
        page.locator("#toAccountId").selectOption(String.valueOf(to));
        page.locator("#amount").fill(amount);
        page.getByTestId("send").click();
    }

    private Locator balanceOf(long account) {
        return page.locator("[data-testid=accounts] tr[data-account-id='" + account + "'] [data-testid=balance]");
    }

    @Test
    void transferIsCreatedShownWithItsIdAndListedInTheHistory() {
        long alice = account("UI Alice", "100.00");
        long bob = account("UI Bob", "0.00");
        signIn();

        sendTransfer(alice, bob, "30.00");

        assertThat(page.getByTestId("result-status")).hasText("Created (201)");
        String id = page.getByTestId("transfer-id").textContent();
        assertThat(balanceOf(alice)).hasText("70.00 EUR");
        assertThat(balanceOf(bob)).hasText("30.00 EUR");
        assertThat(page.locator("[data-testid=history] tr[data-transfer-id='" + id + "']")).isVisible();
    }

    @Test
    void retryWithTheSameKeyShowsTheSameTransferIdAndMovesMoneyOnce() {
        long alice = account("UI Alice", "100.00");
        long bob = account("UI Bob", "0.00");
        signIn();
        sendTransfer(alice, bob, "30.00");
        assertThat(page.getByTestId("result-status")).hasText("Created (201)");
        String id = page.getByTestId("transfer-id").textContent();
        String key = page.getByTestId("result-key").textContent();

        page.getByTestId("retry").click();

        assertThat(page.getByTestId("result-status")).hasText("Replayed (200)");
        assertThat(page.getByTestId("transfer-id")).hasText(id);
        assertThat(page.getByTestId("result-key")).hasText(key);
        assertThat(balanceOf(alice)).hasText("70.00 EUR");
        assertThat(balanceOf(bob)).hasText("30.00 EUR");
    }

    @Test
    void insufficientFundsIsShownAsAnErrorMessage() {
        long carol = account("UI Carol", "10.00");
        long dave = account("UI Dave", "0.00");
        signIn();

        sendTransfer(carol, dave, "50.00");

        assertThat(page.getByTestId("error-title")).hasText("Insufficient funds");
        assertThat(page.getByTestId("error-detail")).containsText("has insufficient funds");
        assertThat(page.getByTestId("result")).hasCount(0);
        assertThat(balanceOf(carol)).hasText("10.00 EUR");
    }

    @Test
    void unknownApiKeyIsRejectedAtSignIn() {
        page.navigate("/ui/login");
        page.getByLabel("API key").fill("not-a-real-key");
        page.getByTestId("sign-in").click();

        assertThat(page.getByTestId("login-error")).hasText("Unknown API key.");
        assertThat(page).hasURL(Pattern.compile(".*/ui/login\\?error$"));
    }
}
