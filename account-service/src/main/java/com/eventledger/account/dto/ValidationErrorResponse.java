package com.eventledger.account.dto;

import java.util.List;

public record ValidationErrorResponse(List<FieldError> errors) {}
