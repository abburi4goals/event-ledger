package com.eventledger.gateway.dto;

import com.eventledger.gateway.model.EventStatus;
import com.eventledger.gateway.model.EventType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

public record EventResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        OffsetDateTime eventTimestamp,
        OffsetDateTime receivedAt,
        Map<String, Object> metadata,
        EventStatus status
) {}
