package com.eventledger.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CircuitBreakerIT {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", wm::baseUrl);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String base() { return "http://localhost:" + port; }

    // T-8: After enough failures, circuit opens and Gateway returns 503
    @Test
    void accountServiceFailures_circuitOpens_returns503() {
        // Stub Account Service to always return 500
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"internal error\"}")));

        int failureCount = 0;
        int circuitOpenCount = 0;

        // Send 15 requests; minimumNumberOfCalls=10 so circuit can open after 10
        for (int i = 0; i < 15; i++) {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    base() + "/events", jsonEvent("evt-cb-" + i, "acct-cb"), String.class);
            if (resp.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                circuitOpenCount++;
                String body = resp.getBody();
                assertThat(body).contains("DEPENDENCY_UNAVAILABLE");
            } else {
                failureCount++;
            }
        }

        // At least some requests must have triggered the circuit breaker (503)
        assertThat(circuitOpenCount).isGreaterThan(0);
    }

    private static final String API_KEY = "test-api-key-secret";

    // T-9: GET /events?account= works even when circuit is OPEN
    @Test
    void getEvents_worksWhenCircuitOpen() {
        // Trigger circuit open by sending failing requests
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 12; i++) {
            restTemplate.postForEntity(base() + "/events",
                    jsonEvent("evt-t9-" + i, "acct-t9"), String.class);
        }

        // T-9: GET must still work regardless of circuit state
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.set("X-Api-Key", API_KEY);
        ResponseEntity<String> getResp = restTemplate.exchange(
                base() + "/events?account=acct-t9",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(getHeaders), String.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpEntity<String> jsonEvent(String eventId, String accountId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Api-Key", API_KEY);
        String body = String.format("""
                {
                  "eventId": "%s",
                  "accountId": "%s",
                  "type": "CREDIT",
                  "amount": 100.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-10T10:00:00Z"
                }
                """, eventId, accountId);
        return new HttpEntity<>(body, h);
    }
}
