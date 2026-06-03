package com.eventledger.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotencyIT {

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

    private String base() {
        return "http://localhost:" + port;
    }

    private static final String ACCOUNT_STUB_BODY = """
            {"accountId":"acct-idem","balance":100.00,"currency":"USD"}
            """;

    private static final String EVENT_BODY = """
            {
              "eventId": "evt-idem-001",
              "accountId": "acct-idem",
              "type": "CREDIT",
              "amount": 100.00,
              "currency": "USD",
              "eventTimestamp": "2026-05-10T10:00:00Z"
            }
            """;

    // T-1: Second POST with same eventId returns 200 with identical body
    @Test
    void postSameEventIdTwice_secondReturns200WithSameBody() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ACCOUNT_STUB_BODY)));

        HttpEntity<String> req = jsonEntity(EVENT_BODY);

        ResponseEntity<String> first = restTemplate.postForEntity(base() + "/events", req, String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = restTemplate.postForEntity(base() + "/events", req, String.class);
        // T-1: second must be 200 OK
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        // T-1: body must match the original
        assertThat(second.getBody()).contains("evt-idem-001");
        assertThat(second.getBody()).contains("acct-idem");
    }

    // T-2: Account Service called exactly once — not on the duplicate
    @Test
    void postSameEventIdTwice_accountServiceCalledOnce() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ACCOUNT_STUB_BODY)));

        HttpEntity<String> req = jsonEntity("""
                {
                  "eventId": "evt-idem-002",
                  "accountId": "acct-idem",
                  "type": "CREDIT",
                  "amount": 50.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-11T10:00:00Z"
                }
                """);

        restTemplate.postForEntity(base() + "/events", req, String.class);
        restTemplate.postForEntity(base() + "/events", req, String.class);

        // T-2: Account Service must have been called exactly once
        wm.verify(1, postRequestedFor(urlPathMatching("/accounts/acct-idem/transactions")));
    }

    private HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
