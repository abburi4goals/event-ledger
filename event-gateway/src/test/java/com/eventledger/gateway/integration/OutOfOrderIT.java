package com.eventledger.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
class OutOfOrderIT {

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

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // T-3: POST 3 events with non-sequential eventTimestamps, assert GET returns them ASC
    @Test
    void outOfOrderEvents_getReturnsAscByEventTimestamp() throws Exception {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountId\":\"acct-ooo\",\"balance\":100.00,\"currency\":\"USD\"}")));

        // Submit in reverse order: T3, T1, T2
        postEvent("evt-ooo-3", "acct-ooo", "2026-05-12T10:00:00Z");  // latest
        postEvent("evt-ooo-1", "acct-ooo", "2026-05-10T10:00:00Z");  // earliest
        postEvent("evt-ooo-2", "acct-ooo", "2026-05-11T10:00:00Z");  // middle

        ResponseEntity<String> resp = restTemplate.getForEntity(
                base() + "/events?account=acct-ooo", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> events = mapper.readValue(resp.getBody(),
                new TypeReference<>() {});
        assertThat(events).hasSize(3);

        OffsetDateTime ts0 = OffsetDateTime.parse((String) events.get(0).get("eventTimestamp"));
        OffsetDateTime ts1 = OffsetDateTime.parse((String) events.get(1).get("eventTimestamp"));
        OffsetDateTime ts2 = OffsetDateTime.parse((String) events.get(2).get("eventTimestamp"));

        assertThat(ts0).isBefore(ts1);
        assertThat(ts1).isBefore(ts2);
        assertThat(events.get(0).get("eventId")).isEqualTo("evt-ooo-1");
        assertThat(events.get(2).get("eventId")).isEqualTo("evt-ooo-3");
    }

    // T-3 tie-break: two events with the same eventTimestamp must come back in stable order
    @Test
    void sameTimestampEvents_stableOrdering() throws Exception {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountId\":\"acct-tie\",\"balance\":100.00,\"currency\":\"USD\"}")));

        String sameTs = "2026-05-15T12:00:00Z";
        postEvent("evt-tie-b", "acct-tie", sameTs);
        postEvent("evt-tie-a", "acct-tie", sameTs);

        ResponseEntity<String> r1 = restTemplate.getForEntity(base() + "/events?account=acct-tie", String.class);
        ResponseEntity<String> r2 = restTemplate.getForEntity(base() + "/events?account=acct-tie", String.class);

        // Both calls must return the same order
        assertThat(r1.getBody()).isEqualTo(r2.getBody());
    }

    private void postEvent(String eventId, String accountId, String timestamp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("""
                {
                  "eventId": "%s",
                  "accountId": "%s",
                  "type": "CREDIT",
                  "amount": 100.00,
                  "currency": "USD",
                  "eventTimestamp": "%s"
                }
                """, eventId, accountId, timestamp);
        restTemplate.postForEntity(base() + "/events",
                new HttpEntity<>(body, headers), String.class);
    }
}
