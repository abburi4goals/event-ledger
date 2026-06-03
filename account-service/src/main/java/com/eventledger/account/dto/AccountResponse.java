package com.eventledger.account.dto;

import java.math.BigDecimal;
import java.util.List;

public record AccountResponse(
        String accountId,
        BigDecimal balance,
        String currency,
        List<TransactionView> transactions
) {}
