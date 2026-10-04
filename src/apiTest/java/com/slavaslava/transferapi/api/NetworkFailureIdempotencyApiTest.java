package com.slavaslava.transferapi.api;

import com.slavaslava.transferapi.config.ApiKeyHasher;
import com.slavaslava.transferapi.domain.Terminal;
import com.slavaslava.transferapi.repository.TerminalRepository;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.Testcontainers;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scenario idempotency exists for: a POS terminal sends a transfer, the server commits it, and the
 * network loses the response. The terminal can't tell whether the money moved, so it retries the same
 * request with the same Idempotency-Key and must get the original transfer back, without a second debit.
 *
 * <p>The terminal talks to the app through Toxiproxy (Testcontainers), which reaches the app on the
 * host via {@link Testcontainers#exposeHostPorts}. A {@code limit_data} toxic on the downstream
 * (app → terminal) direction closes the connection once the first response byte arrives. The app only
 * writes a response after the transfer's transaction has committed, so the cut always happens after
 * the commit. That makes the test deterministic: it doesn't depend on timing or sleeps. Setup and
 * assertions bypass the proxy and call the app directly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rate-limit.requests-per-second=1000")
@Import(ApiTestContainers.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NetworkFailureIdempotencyApiTest {

    private static final Logger log = LoggerFactory.getLogger(NetworkFailureIdempotencyApiTest.class);

    private static final String API_KEY = "network-failure-terminal-key";
    private static final int PROXY_PORT = 8666; // first of the proxy ports ToxiproxyContainer exposes
    private static final String CUT_RESPONSE = "cut-response-after-first-byte";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TerminalRepository terminalRepository;

    private ToxiproxyContainer toxiproxy;
    private Proxy proxy;

    @BeforeAll
    void startToxiproxyInFrontOfTheApp() throws IOException {
        if (terminalRepository.findByApiKeyHash(ApiKeyHasher.sha256Hex(API_KEY)).isEmpty()) {
            terminalRepository.save(new Terminal("network-failure-terminal", ApiKeyHasher.sha256Hex(API_KEY)));
        }
        // Must happen before the Toxiproxy container starts, so it gets host.testcontainers.internal.
        Testcontainers.exposeHostPorts(port);
        toxiproxy = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"));
        toxiproxy.start();
        proxy = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
                .createProxy("transfer-api", "0.0.0.0:" + PROXY_PORT, "host.testcontainers.internal:" + port);
        log.info("Toxiproxy {} forwards {}:{} -> app port {}", proxy.getName(),
                toxiproxy.getHost(), toxiproxy.getMappedPort(PROXY_PORT), port);
    }

    @AfterAll
    void stopToxiproxy() {
        if (toxiproxy != null) {
            toxiproxy.stop();
        }
    }

    @Test
    void responseLostAfterCommitIsRecoveredByRetryingWithTheSameKeyAndMoneyMovesOnce() throws Exception {
        long from = createAccount("Alice", "100.00");
        long to = createAccount("Bob", "0.00");
        String key = UUID.randomUUID().toString();
        String body = """
                {"fromAccountId":%d,"toAccountId":%d,"amount":30.00}""".formatted(from, to);

        // 1. The network drops the response: the connection closes after one byte of it.
        proxy.toxics().limitData(CUT_RESPONSE, ToxicDirection.DOWNSTREAM, 1);
        assertThatThrownBy(() -> terminalSendsTransfer(key, body))
                .as("the terminal must not receive a usable response while the toxic is active")
                .isInstanceOf(IOException.class);
        log.info("First attempt lost its response as intended");

        // The terminal saw nothing, but the server did commit the transfer.
        List<Long> committed = transferIds(from);
        assertThat(committed).as("transfers committed by the lost request").hasSize(1);
        assertThat(balance(from)).isEqualByComparingTo("70.00");

        // 2. The network recovers and the terminal retries the exact same request with the same key.
        proxy.toxics().get(CUT_RESPONSE).remove();
        HttpResponse<String> retry = terminalSendsTransfer(key, body);
        log.info("Retry with the same Idempotency-Key answered {} {}", retry.statusCode(), retry.body());

        // 200 (replay), not 201: nothing was executed a second time.
        assertThat(retry.statusCode()).isEqualTo(200);
        JsonPath replayed = JsonPath.from(retry.body());
        assertThat(replayed.getLong("id")).isEqualTo(committed.getFirst());
        assertThat(replayed.getString("status")).isEqualTo("COMPLETED");

        // Money moved exactly once.
        assertThat(transferIds(from)).containsExactlyElementsOf(committed);
        assertThat(balance(from)).isEqualByComparingTo("70.00");
        assertThat(balance(to)).isEqualByComparingTo("30.00");
    }

    /**
     * The POS terminal: one POST /transfers through Toxiproxy. A fresh client per attempt means a
     * new connection, as a terminal reconnecting after a network error would make. The JDK client
     * never retries a POST on its own, so each call is exactly one attempt.
     */
    private HttpResponse<String> terminalSendsTransfer(String idempotencyKey, String body)
            throws IOException, InterruptedException {
        URI uri = URI.create("http://%s:%d/transfers".formatted(toxiproxy.getHost(), toxiproxy.getMappedPort(PROXY_PORT)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-API-Key", API_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private RequestSpecification direct() {
        return given().port(port).header("X-API-Key", API_KEY).contentType(ContentType.JSON);
    }

    private long createAccount(String owner, String balance) {
        return direct().body("""
                        {"owner":"%s","initialBalance":%s,"currency":"EUR"}""".formatted(owner, balance))
                .post("/accounts").then().statusCode(201).extract().jsonPath().getLong("id");
    }

    private BigDecimal balance(long accountId) {
        String json = direct().get("/accounts/{id}", accountId).then().statusCode(200).extract().asString();
        return JsonPath.from(json).using(new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL)).get("balance");
    }

    private List<Long> transferIds(long accountId) {
        return direct().queryParam("accountId", accountId).get("/transfers")
                .then().statusCode(200).extract().jsonPath().getList("content.id", Long.class);
    }
}
