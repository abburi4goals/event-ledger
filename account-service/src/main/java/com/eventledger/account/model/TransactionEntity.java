package com.eventledger.account.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A single immutable entry in an account's ledger.
 *
 * <p>The table uses a surrogate {@code Long id} primary key; the natural key {@code event_id}
 * carries a unique constraint so a replayed event cannot be applied twice (the Account Service's
 * own idempotency safety net, independent of the Gateway). Rows are never updated — balance is
 * always recomputed from the full set of transactions, so arrival order is irrelevant.
 */
@Entity
@Table(
    name = "transactions",
    uniqueConstraints = @UniqueConstraint(name = "uk_transactions_event_id", columnNames = "event_id"),
    indexes = @Index(name = "idx_transactions_account_id", columnList = "account_id")
)
public class TransactionEntity extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Idempotency safety-net key — unique, client-originated, never updated. */
    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 6)
    private TransactionType type;

    /** Monetary amount — always {@link BigDecimal}, never double/float. DECIMAL(19,4) in H2. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** ISO 4217 currency code (e.g. "USD"). */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** When the originating event occurred (client-provided). */
    @Column(name = "event_timestamp", nullable = false)
    private OffsetDateTime eventTimestamp;

    /** Required by JPA. */
    protected TransactionEntity() {
    }

    public TransactionEntity(String eventId, String accountId, TransactionType type,
                             BigDecimal amount, String currency, OffsetDateTime eventTimestamp) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
    }

    public Long getId() {
        return id;
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

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionEntity other)) {
            return false;
        }
        return eventId != null && eventId.equals(other.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(eventId);
    }
}
