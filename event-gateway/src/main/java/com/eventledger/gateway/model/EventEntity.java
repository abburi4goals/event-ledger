package com.eventledger.gateway.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * An ingested financial event stored in the Gateway's own H2 database.
 *
 * <p>{@code eventId} is the primary key and the idempotency key — a duplicate POST is detected by
 * its presence (or, on the concurrent-insert race, by the resulting
 * {@code DataIntegrityViolationException}). The {@code account_id} index backs
 * {@code GET /events?account=}. Listings are always sorted by {@code eventTimestamp} (when the
 * event occurred), never by {@code receivedAt} (when it was ingested).
 */
@Entity
@Table(
    name = "events",
    indexes = @Index(name = "idx_events_account_id", columnList = "account_id")
)
public class EventEntity extends AuditableEntity {

    /** Idempotency key — natural primary key, client-assigned, never updated. */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 6)
    private EventType type;

    /** Monetary amount — always {@link BigDecimal}, never double/float. DECIMAL(19,4) in H2. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** ISO 4217 currency code (e.g. "USD"). */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** When the event occurred in the real world (client-provided) — used for sorting. */
    @Column(name = "event_timestamp", nullable = false)
    private OffsetDateTime eventTimestamp;

    /** When the Gateway received the event (server-set at ingestion) — audit only. */
    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    /** Optional free-form metadata, persisted as a raw JSON string. */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    /** Required by JPA. */
    protected EventEntity() {
    }

    public EventEntity(String eventId, String accountId, EventType type, BigDecimal amount,
                       String currency, OffsetDateTime eventTimestamp, OffsetDateTime receivedAt,
                       String metadata) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.receivedAt = receivedAt;
        this.metadata = metadata;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public EventType getType() {
        return type;
    }

    public void setType(EventType type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public OffsetDateTime getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(OffsetDateTime eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public OffsetDateTime getReceivedAt() {
        return receivedAt;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EventEntity other)) {
            return false;
        }
        return eventId != null && eventId.equals(other.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(eventId);
    }
}
