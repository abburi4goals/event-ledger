package com.eventledger.account.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AccountServiceIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String base() {
        return "http://localhost:" + port;
    }

    // T-12: Health endpoint returns UP
    @Test
    void health_returnsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity(base() + "/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).contains("\"service\":\"account-service\"");
        assertThat(response.getBody()).contains("\"db\":\"UP\"");
    }

    // T-4 (integration): POST transaction → account auto-created → GET balance reflects it
    @Test
    void postTransaction_newAccount_balanceReflectsCredit() {
        String body = """
                {
                  "eventId": "it-evt-001",
                  "type": "CREDIT",
                  "amount": 250.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-10T10:00:00Z"
                }
                """;
        ResponseEntity<String> post = restTemplate.postForEntity(
                base() + "/accounts/it-acct-001/transactions", requestEntity(body), String.class);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post.getBody()).contains("\"balance\"");

        ResponseEntity<String> balance = restTemplate.getForEntity(
                base() + "/accounts/it-acct-001/balance", String.class);
        assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(balance.getBody()).contains("250");
    }

    // T-4 (integration): DEBIT reduces balance
    @Test
    void postTransaction_creditThenDebit_balanceCorrect() {
        String credit = """
                {
                  "eventId": "it-evt-credit",
                  "type": "CREDIT",
                  "amount": 300.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-10T10:00:00Z"
                }
                """;
        String debit = """
                {
                  "eventId": "it-evt-debit",
                  "type": "DEBIT",
                  "amount": 100.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-11T10:00:00Z"
                }
                """;
        restTemplate.postForEntity(base() + "/accounts/it-acct-002/transactions", requestEntity(credit), String.class);
        restTemplate.postForEntity(base() + "/accounts/it-acct-002/transactions", requestEntity(debit), String.class);

        ResponseEntity<String> balance = restTemplate.getForEntity(
                base() + "/accounts/it-acct-002/balance", String.class);
        assertThat(balance.getBody()).contains("200");
    }

    // GET /accounts/{accountId} returns account details with transaction list
    @Test
    void getAccountDetails_returnsTransactionsInDescendingOrder() {
        String body = """
                {
                  "eventId": "it-evt-account",
                  "type": "CREDIT",
                  "amount": 150.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-10T10:00:00Z"
                }
                """;
        restTemplate.postForEntity(base() + "/accounts/it-acct-details/transactions", requestEntity(body), String.class);

        ResponseEntity<String> response = restTemplate.getForEntity(
                base() + "/accounts/it-acct-details", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"accountId\":\"it-acct-details\"");
        assertThat(response.getBody()).contains("\"balance\"");
        assertThat(response.getBody()).contains("\"transactions\"");
        assertThat(response.getBody()).contains("\"it-evt-account\"");
    }

    // Idempotency: posting the same eventId twice returns 200 on second and balance unchanged
    @Test
    void postTransaction_duplicateEventId_returns200_balanceUnchanged() {
        String body = """
                {
                  "eventId": "it-evt-dup",
                  "type": "CREDIT",
                  "amount": 100.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-10T10:00:00Z"
                }
                """;
        restTemplate.postForEntity(base() + "/accounts/it-acct-003/transactions", requestEntity(body), String.class);
        ResponseEntity<String> second = restTemplate.postForEntity(
                base() + "/accounts/it-acct-003/transactions", requestEntity(body), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> balance = restTemplate.getForEntity(
                base() + "/accounts/it-acct-003/balance", String.class);
        assertThat(balance.getBody()).contains("100");
        // Balance must still be 100, not 200
        assertThat(balance.getBody()).doesNotContain("200");
    }

    private org.springframework.http.HttpEntity<String> requestEntity(String body) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Content-Type", "application/json");
        return new org.springframework.http.HttpEntity<>(body, headers);
    }
}
