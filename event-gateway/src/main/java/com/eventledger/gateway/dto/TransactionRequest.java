package com.eventledger.gateway.dto;

import com.eventledger.gateway.model.EventType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Payload sent by the Gateway to Account Service POST /accounts/{id}/transactions. */
public record TransactionRequest(
        String eventId,
        EventType type,
        BigDecimal amount,
        String currency,
        OffsetDateTime eventTimestamp
) {}
