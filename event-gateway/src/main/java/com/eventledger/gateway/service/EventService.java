package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.dto.TransactionRequest;
import com.eventledger.gateway.exception.EventNotFoundException;
import com.eventledger.gateway.model.EventEntity;
import com.eventledger.gateway.model.EventType;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;

    public EventService(EventRepository eventRepository,
                        AccountServiceClient accountServiceClient,
                        ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Submit an event. Returns the result with a flag indicating whether this was a new event
     * (created=true → 201) or a duplicate (created=false → 200).
     *
     * <p>Ordering invariant: Account Service is called BEFORE the event is saved to the Gateway
     * DB. If the Account Service call fails, nothing is persisted and the client can safely retry.
     */
    @Transactional
    public EventSubmitResult submitEvent(EventRequest req) {
        log.info("Processing event eventId={} accountId={} type={}", req.eventId(), req.accountId(), req.type());

        // Idempotency check — ALWAYS FIRST
        Optional<EventEntity> existing = eventRepository.findById(req.eventId());
        if (existing.isPresent()) {
            log.info("Duplicate event detected eventId={} — returning original", req.eventId());
            return new EventSubmitResult(toResponse(existing.get()), false);
        }

        // Call Account Service BEFORE saving — if this throws (circuit open or HTTP error),
        // nothing is saved and the client can retry safely with the same eventId.
        TransactionRequest txnReq = new TransactionRequest(
                req.eventId(), req.type(), req.amount(), req.currency(), req.eventTimestamp());
        accountServiceClient.applyTransaction(req.accountId(), txnReq);

        // Build and persist the event
        EventEntity entity = new EventEntity(
                req.eventId(), req.accountId(), req.type(),
                req.amount(), req.currency(), req.eventTimestamp(),
                OffsetDateTime.now(),       // receivedAt — server-set, not an audit field
                serializeMetadata(req.metadata()));

        try {
            EventEntity saved = eventRepository.save(entity);
            log.info("Event persisted eventId={} accountId={}", req.eventId(), req.accountId());
            return new EventSubmitResult(toResponse(saved), true);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate: another thread won the race and already saved this eventId.
            // Re-read and return 200 — the Account Service's unique constraint prevented double-apply.
            log.info("Concurrent duplicate detected eventId={} — re-reading", req.eventId());
            EventEntity concurrent = eventRepository.findById(req.eventId())
                    .orElseThrow(() -> new IllegalStateException("Event vanished after constraint violation", e));
            return new EventSubmitResult(toResponse(concurrent), false);
        }
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(String eventId) {
        return eventRepository.findById(eventId)
                .map(this::toResponse)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getEventsByAccount(String accountId) {
        return eventRepository
                .findByAccountIdOrderByEventTimestampAscReceivedAtAscEventIdAsc(accountId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private EventResponse toResponse(EventEntity e) {
        return new EventResponse(
                e.getEventId(), e.getAccountId(), e.getType(),
                e.getAmount(), e.getCurrency(), e.getEventTimestamp(),
                e.getReceivedAt(), deserializeMetadata(e.getMetadata()));
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize metadata, storing null");
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeMetadata(String metadata) {
        if (metadata == null) return null;
        try {
            return objectMapper.readValue(metadata, Map.class);
        } catch (Exception ex) {
            return null;
        }
    }

    public record EventSubmitResult(EventResponse response, boolean created) {}
}
