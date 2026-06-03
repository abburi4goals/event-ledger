package com.eventledger.gateway.client;

import com.eventledger.gateway.dto.TransactionRequest;
import com.eventledger.gateway.dto.TransactionResponse;
import com.eventledger.gateway.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public AccountServiceClient(RestTemplate accountServiceRestTemplate,
                                @Value("${account-service.base-url}") String baseUrl) {
        this.restTemplate = accountServiceRestTemplate;
        this.baseUrl = baseUrl;
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "applyTransactionFallback")
    public TransactionResponse applyTransaction(String accountId, TransactionRequest request) {
        String url = baseUrl + "/accounts/" + accountId + "/transactions";
        log.debug("Calling Account Service POST {} eventId={}", url, request.eventId());
        TransactionResponse response = restTemplate.postForObject(url, request, TransactionResponse.class);
        log.info("Account Service responded for accountId={} eventId={}", accountId, request.eventId());
        return response;
    }

    // Fallback signature must match the protected method with a trailing Throwable parameter
    private TransactionResponse applyTransactionFallback(String accountId, TransactionRequest request, Throwable t) {
        log.warn("Circuit breaker fallback triggered accountId={} eventId={} cause={}",
                accountId, request.eventId(), t.toString());
        throw new ServiceUnavailableException("Account Service is currently unavailable.", t);
    }
}
