package com.eventledger.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiKeyFilterIT {

    private static final String VALID_KEY = "test-api-key-secret";
    private static final String VALID_BODY = """
            {
              "eventId": "evt-auth-001",
              "accountId": "acct-auth",
              "type": "CREDIT",
              "amount": 100.00,
              "currency": "USD",
              "eventTimestamp": "2026-05-10T10:00:00Z"
            }
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String base() { return "http://localhost:" + port; }

    @Test
    void postEvent_missingApiKey_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                base() + "/events", new HttpEntity<>(VALID_BODY, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("UNAUTHORIZED");
    }

    @Test
    void postEvent_wrongApiKey_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", "wrong-key");
        ResponseEntity<String> resp = restTemplate.postForEntity(
                base() + "/events", new HttpEntity<>(VALID_BODY, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("UNAUTHORIZED");
    }

    @Test
    void getEvents_missingApiKey_returns401() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                base() + "/events?account=acct-auth", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void health_noApiKey_returns200() {
        // /health must be accessible without a key
        ResponseEntity<String> resp = restTemplate.getForEntity(
                base() + "/health", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void postEvent_validApiKey_passesThroughFilter() {
        // Account Service is not running — we expect 503, not 401.
        // The point is: a valid key passes the filter and reaches the endpoint.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", VALID_KEY);
        ResponseEntity<String> resp = restTemplate.postForEntity(
                base() + "/events", new HttpEntity<>(VALID_BODY, headers), String.class);

        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getEvents_validApiKey_passesThroughFilter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", VALID_KEY);
        ResponseEntity<String> resp = restTemplate.exchange(
                base() + "/events?account=acct-auth",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
