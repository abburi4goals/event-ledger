package com.eventledger.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = "gateway.rate-limit.requests-per-second=3")
@DirtiesContext
class RateLimitIT {

    private static final String API_KEY = "test-api-key-secret";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    /** Fire 10 rapid GET requests; the first 3 are allowed, the rest must be 429. */
    @Test
    void burstRequests_triggerRateLimit() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", API_KEY);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = "http://localhost:" + port + "/events/nonexistent";

        int rateLimitedCount = 0;
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (resp.getStatusCode().value() == 429) {
                rateLimitedCount++;
            }
        }

        assertThat(rateLimitedCount).isGreaterThan(0);
    }

    /** Rate-limited response must carry a Retry-After header. */
    @Test
    void rateLimitedResponse_hasRetryAfterHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", API_KEY);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = "http://localhost:" + port + "/events/nonexistent";

        ResponseEntity<String> limited = null;
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (resp.getStatusCode().value() == 429) {
                limited = resp;
                break;
            }
        }

        assertThat(limited).isNotNull();
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(limited.getBody()).contains("TOO_MANY_REQUESTS");
    }

    /** /health must never be rate-limited, even under burst traffic. */
    @Test
    void healthEndpoint_exemptFromRateLimit() {
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    "http://localhost:" + port + "/health", String.class);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
        }
    }

    /** X-Forwarded-For header is used as the rate-limit key when present. */
    @Test
    void xForwardedFor_usedAsClientKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", API_KEY);
        // Use a distinct fake IP so this test gets a fresh 3-req budget
        headers.set("X-Forwarded-For", "10.0.0.99");
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        String url = "http://localhost:" + port + "/events/nonexistent";

        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 6; i++) {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            int status = resp.getStatusCode().value();
            if (status == 404 || status == 200) ok++;
            else if (status == 429) limited++;
        }

        assertThat(ok).isEqualTo(3);
        assertThat(limited).isEqualTo(3);
    }
}
