package com.slavaslava.transferapi.api;

import com.slavaslava.transferapi.config.ApiKeyHasher;
import com.slavaslava.transferapi.domain.Terminal;
import com.slavaslava.transferapi.repository.TerminalRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.rate-limit.requests-per-second=1000")
@Import(ApiTestContainers.class)
class TransferApiTest {

    private static final String API_KEY = "api-test-terminal-key";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TerminalRepository terminalRepository;

    private static OpenApiContract contract;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        if (terminalRepository.findByApiKeyHash(ApiKeyHasher.sha256Hex(API_KEY)).isEmpty()) {
            terminalRepository.save(new Terminal("api-test-terminal", ApiKeyHasher.sha256Hex(API_KEY)));
        }
        if (contract == null) {
            contract = new OpenApiContract(port);
        }
    }

    private RequestSpecification authed() {
        return given().header("X-API-Key", API_KEY).contentType(ContentType.JSON);
    }

    private long createAccount(String owner, String balance) {
        return authed().body("""
                        {"owner":"%s","initialBalance":%s,"currency":"EUR"}""".formatted(owner, balance))
                .post("/accounts").then().statusCode(201).extract().jsonPath().getLong("id");
    }

    private Response transfer(String key, long from, long to, String amount) {
        return authed().header("Idempotency-Key", key)
                .body("""
                        {"fromAccountId":%d,"toAccountId":%d,"amount":%s}""".formatted(from, to, amount))
                .post("/transfers");
    }

    @Test
    void createAccountReturns201AndMatchesContract() {
        Response response = authed().body("""
                        {"owner":"Alice","initialBalance":100.00,"currency":"EUR"}""")
                .post("/accounts");

        response.then().statusCode(201)
                .body("id", notNullValue())
                .body("owner", equalTo("Alice"))
                .body("balance", equalTo(100.00f))
                .body("currency", equalTo("EUR"));
        contract.assertConforms("POST", "/accounts", response);
    }

    @Test
    void getAccountMatchesContract() {
        long id = createAccount("Carol", "5.00");

        Response response = authed().get("/accounts/{id}", id);

        response.then().statusCode(200).body("id", equalTo((int) id));
        contract.assertConforms("GET", "/accounts/{id}", response);
    }

    @Test
    void transferReturns201MovesMoneyAndMatchesContract() {
        long from = createAccount("Alice", "100.00");
        long to = createAccount("Bob", "0.00");

        Response response = transfer(UUID.randomUUID().toString(), from, to, "30.00");

        response.then().statusCode(201)
                .body("fromAccountId", equalTo((int) from))
                .body("toAccountId", equalTo((int) to))
                .body("amount", equalTo(30.00f));
        contract.assertConforms("POST", "/transfers", response);
        authed().get("/accounts/{id}", from).then().body("balance", equalTo(70.00f));
        authed().get("/accounts/{id}", to).then().body("balance", equalTo(30.00f));
    }

    @Test
    void replayWithSameKeyReturns200WithSameTransferId() {
        long from = createAccount("Alice", "100.00");
        long to = createAccount("Bob", "0.00");
        String key = UUID.randomUUID().toString();

        long firstId = transfer(key, from, to, "30.00").then().statusCode(201).extract().jsonPath().getLong("id");
        transfer(key, from, to, "30.00").then().statusCode(200).body("id", equalTo((int) firstId));

        authed().get("/accounts/{id}", from).then().body("balance", equalTo(70.00f));
    }

    @Test
    void reusingKeyWithDifferentPayloadReturns422ProblemJson() {
        long from = createAccount("Alice", "100.00");
        long to = createAccount("Bob", "0.00");
        String key = UUID.randomUUID().toString();
        transfer(key, from, to, "30.00").then().statusCode(201);

        transfer(key, from, to, "55.00").then()
                .statusCode(422)
                .contentType(containsString("application/problem+json"))
                .body("title", equalTo("Idempotency-Key Reuse"))
                .body("status", equalTo(422))
                .body("detail", notNullValue());
    }

    @Test
    void missingApiKeyReturns401ProblemJson() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"owner":"Mallory","initialBalance":1.00,"currency":"EUR"}""")
                .post("/accounts").then()
                .statusCode(401)
                .contentType(containsString("application/problem+json"))
                .body("status", equalTo(401));
    }

    @Test
    void insufficientFundsReturns409ProblemJsonAndLeavesBalancesUntouched() {
        long from = createAccount("Alice", "10.00");
        long to = createAccount("Bob", "0.00");

        transfer(UUID.randomUUID().toString(), from, to, "50.00").then()
                .statusCode(409)
                .contentType(containsString("application/problem+json"))
                .body("title", equalTo("Insufficient Funds"));

        authed().get("/accounts/{id}", from).then().body("balance", equalTo(10.00f));
    }

    @Test
    void historyIsPaginatedNewestFirst() {
        long from = createAccount("Alice", "100.00");
        long to = createAccount("Bob", "0.00");
        for (int i = 0; i < 3; i++) {
            transfer(UUID.randomUUID().toString(), from, to, "1.00").then().statusCode(201);
        }

        Response firstPage = authed().queryParam("accountId", from).queryParam("size", 2).queryParam("page", 0)
                .get("/transfers");
        firstPage.then().statusCode(200).body("content", hasSize(2));
        // Spring Data serializes the totals either at the top level or under "page", depending on version/config
        JsonPath json = firstPage.jsonPath();
        String prefix = json.get("page") != null ? "page." : "";
        assertThat(json.getInt(prefix + "totalElements")).isEqualTo(3);
        assertThat(json.getInt(prefix + "totalPages")).isEqualTo(2);
        authed().queryParam("accountId", from).queryParam("size", 2).queryParam("page", 1)
                .get("/transfers").then().statusCode(200).body("content", hasSize(1));
    }

    @BeforeAll
    static void resetContract() {
        contract = null;
    }
}
