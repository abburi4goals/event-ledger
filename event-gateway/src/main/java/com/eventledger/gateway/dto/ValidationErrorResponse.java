package com.eventledger.gateway.dto;

import java.util.List;

public record ValidationErrorResponse(List<FieldError> errors) {}
