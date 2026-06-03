package com.eventledger.gateway.model;

/**
 * The kind of financial movement an event represents.
 *
 * <p>Persisted as a string ({@code @Enumerated(EnumType.STRING)}) so the stored value is stable
 * and human-readable. Any payload value other than {@code CREDIT} or {@code DEBIT} fails Jackson
 * deserialization and is mapped to a 400 field error by the GlobalExceptionHandler.
 */
public enum EventType {
    CREDIT,
    DEBIT
}
