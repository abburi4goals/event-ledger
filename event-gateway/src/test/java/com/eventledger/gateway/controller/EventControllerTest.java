package com.eventledger.gateway.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.exception.EventNotFoundException;
import com.eventledger.gateway.model.EventType;
import com.eventledger.gateway.service.EventService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EventService eventService;

    // T-5: Missing required field → 400 with field error
    @Test
    void postEvent_missingEventId_returns400() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": "acct-001",
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("eventId"))
                .andExpect(jsonPath("$.errors[0].message").value("eventId is required"));
    }

    // T-6: amount = 0 → 400
    @Test
    void postEvent_zeroAmount_returns400() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "accountId": "acct-001",
                                  "type": "CREDIT",
                                  "amount": 0,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    // T-6b: negative amount → 400
    @Test
    void postEvent_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "accountId": "acct-001",
                                  "type": "CREDIT",
                                  "amount": -5,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
    }

    // T-7: Unknown type → 400 with field error shape (enum deserialization path)
    @Test
    void postEvent_unknownType_returns400() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "accountId": "acct-001",
                                  "type": "TRANSFER",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.errors[0].message").value("type must be CREDIT or DEBIT"));
    }

    @Test
    void postEvent_valid_returns201() throws Exception {
        EventResponse resp = new EventResponse("evt-001", "acct-001", EventType.CREDIT,
                new BigDecimal("100.00"), "USD", OffsetDateTime.now(), OffsetDateTime.now(), null);
        when(eventService.submitEvent(any()))
                .thenReturn(new EventService.EventSubmitResult(resp, true));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "accountId": "acct-001",
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"));
    }

    @Test
    void postEvent_duplicate_returns200() throws Exception {
        EventResponse resp = new EventResponse("evt-001", "acct-001", EventType.CREDIT,
                new BigDecimal("100.00"), "USD", OffsetDateTime.now(), OffsetDateTime.now(), null);
        when(eventService.submitEvent(any()))
                .thenReturn(new EventService.EventSubmitResult(resp, false));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "accountId": "acct-001",
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void getEvent_notFound_returns404() throws Exception {
        when(eventService.getEvent("unknown")).thenThrow(new EventNotFoundException("unknown"));

        mockMvc.perform(get("/events/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Event not found: unknown"));
    }

    // Covers GlobalExceptionHandler OffsetDateTime deserialization branch
    @Test
    void postEvent_badTimestamp_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "accountId": "acct-001",
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "not-a-date"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("eventTimestamp"));
    }

    // Covers GlobalExceptionHandler catch-all (handleUnexpected)
    @Test
    void postEvent_serviceThrowsUnexpected_returns500() throws Exception {
        when(eventService.submitEvent(any())).thenThrow(new RuntimeException("unexpected"));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-001",
                                  "accountId": "acct-001",
                                  "type": "CREDIT",
                                  "amount": 100.00,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-10T10:00:00Z"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"));
    }
}
