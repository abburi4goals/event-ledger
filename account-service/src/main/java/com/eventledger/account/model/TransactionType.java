package com.eventledger.account.model;

/**
 * The direction of a ledger transaction.
 *
 * <p>{@code CREDIT} increases the derived balance, {@code DEBIT} decreases it. Persisted as a
 * string. Balance is computed as {@code Σ(CREDIT) − Σ(DEBIT)} and never stored.
 */
public enum TransactionType {
    CREDIT,
    DEBIT
}
