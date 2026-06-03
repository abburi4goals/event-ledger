package com.eventledger.gateway.dto;

public record HealthResponse(String status, String service, String db) {}
