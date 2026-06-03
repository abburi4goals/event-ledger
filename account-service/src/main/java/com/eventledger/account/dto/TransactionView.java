package com.eventledger.account.dto;

import com.eventledger.account.model.TransactionType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionView(
        String eventId,
        TransactionType type,
        BigDecimal amount,
        OffsetDateTime eventTimestamp
) {}
