package com.eventledger.gateway.model;

public enum EventStatus {
    /** Event was forwarded to Account Service and applied successfully. */
    PROCESSED,
    /** Account Service was unavailable; event is queued for background retry. */
    QUEUED
}
