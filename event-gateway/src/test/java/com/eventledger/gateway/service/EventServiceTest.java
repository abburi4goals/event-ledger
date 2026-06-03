package com.eventledger.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.dto.TransactionResponse;
import com.eventledger.gateway.exception.EventNotFoundException;
import com.eventledger.gateway.exception.ServiceUnavailableException;
import com.eventledger.gateway.model.EventEntity;
import com.eventledger.gateway.model.EventType;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private AccountServiceClient accountServiceClient;

    private EventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository, accountServiceClient, new ObjectMapper());
    }

    @Test
    void submitEvent_duplicate_returnsExistingWithoutCallingAccountService() {
        EventRequest req = sampleRequest("evt-dup");
        EventEntity existing = sampleEntity("evt-dup");
        when(eventRepository.findById("evt-dup")).thenReturn(Optional.of(existing));

        EventService.EventSubmitResult result = eventService.submitEvent(req);

        assertThat(result.created()).isFalse();
        assertThat(result.response().eventId()).isEqualTo("evt-dup");
        verify(accountServiceClient, never()).applyTransaction(any(), any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void submitEvent_newEvent_callsAccountServiceThenSaves() {
        EventRequest req = sampleRequest("evt-new");
        when(eventRepository.findById("evt-new")).thenReturn(Optional.empty());
        when(accountServiceClient.applyTransaction(eq("acct-001"), any()))
                .thenReturn(new TransactionResponse("acct-001", BigDecimal.TEN, "USD"));
        EventEntity saved = sampleEntity("evt-new");
        when(eventRepository.save(any())).thenReturn(saved);

        EventService.EventSubmitResult result = eventService.submitEvent(req);

        assertThat(result.created()).isTrue();
        verify(accountServiceClient).applyTransaction(eq("acct-001"), any());
        verify(eventRepository).save(any());
    }

    @Test
    void submitEvent_circuitOpen_throwsAndDoesNotSave() {
        EventRequest req = sampleRequest("evt-503");
        when(eventRepository.findById("evt-503")).thenReturn(Optional.empty());
        when(accountServiceClient.applyTransaction(any(), any()))
                .thenThrow(new ServiceUnavailableException("Account Service unavailable"));

        assertThatThrownBy(() -> eventService.submitEvent(req))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void getEvent_notFound_throwsEventNotFoundException() {
        when(eventRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getEvent("unknown"))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessageContaining("Event not found: unknown");
    }

    // Concurrent duplicate: save() throws DataIntegrityViolationException, second findById finds it
    @Test
    void submitEvent_concurrentDuplicate_returnsExistingWithCreatedFalse() {
        EventRequest req = sampleRequest("evt-concurrent");
        EventEntity concurrent = sampleEntity("evt-concurrent");
        when(eventRepository.findById("evt-concurrent"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrent));
        when(accountServiceClient.applyTransaction(any(), any()))
                .thenReturn(new TransactionResponse("acct-001", BigDecimal.TEN, "USD"));
        when(eventRepository.save(any())).thenThrow(DataIntegrityViolationException.class);

        EventService.EventSubmitResult result = eventService.submitEvent(req);

        assertThat(result.created()).isFalse();
        assertThat(result.response().eventId()).isEqualTo("evt-concurrent");
    }

    // Non-null metadata: exercises the try{} path in both serializeMetadata and deserializeMetadata
    @Test
    void submitEvent_withMetadata_serializesAndDeserializesCorrectly() {
        EventRequest req = new EventRequest("evt-meta", "acct-001", EventType.CREDIT,
                new BigDecimal("100.00"), "USD", OffsetDateTime.now(), Map.of("ref", "ABC"));
        when(eventRepository.findById("evt-meta")).thenReturn(Optional.empty());
        when(accountServiceClient.applyTransaction(any(), any()))
                .thenReturn(new TransactionResponse("acct-001", BigDecimal.TEN, "USD"));
        EventEntity saved = new EventEntity("evt-meta", "acct-001", EventType.CREDIT,
                new BigDecimal("100.00"), "USD", OffsetDateTime.now(), OffsetDateTime.now(),
                "{\"ref\":\"ABC\"}");
        when(eventRepository.save(any())).thenReturn(saved);

        EventService.EventSubmitResult result = eventService.submitEvent(req);

        assertThat(result.created()).isTrue();
        assertThat(result.response().metadata()).containsKey("ref");
    }

    // Malformed JSON in metadata field: exercises deserializeMetadata catch block
    @Test
    void getEvent_withMalformedMetadata_returnsNullMetadata() {
        EventEntity entity = new EventEntity("evt-bad-meta", "acct-001", EventType.CREDIT,
                new BigDecimal("100.00"), "USD", OffsetDateTime.now(), OffsetDateTime.now(),
                "not-valid-json");
        when(eventRepository.findById("evt-bad-meta")).thenReturn(Optional.of(entity));

        EventResponse response = eventService.getEvent("evt-bad-meta");

        assertThat(response.metadata()).isNull();
    }

    private EventRequest sampleRequest(String eventId) {
        return new EventRequest(eventId, "acct-001", EventType.CREDIT,
                new BigDecimal("100.00"), "USD", OffsetDateTime.now(), null);
    }

    private EventEntity sampleEntity(String eventId) {
        return new EventEntity(eventId, "acct-001", EventType.CREDIT,
                new BigDecimal("100.00"), "USD", OffsetDateTime.now(), OffsetDateTime.now(), null);
    }
}
