package com.eventledger.gateway.dto;

import java.math.BigDecimal;

/** Response from Account Service after applying a transaction. */
public record TransactionResponse(String accountId, BigDecimal balance, String currency) {}
