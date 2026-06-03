package com.eventledger.gateway.repository;

import com.eventledger.gateway.model.EventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<EventEntity, String> {

    // Three-key sort: eventTimestamp ASC (primary), receivedAt ASC (tie-break for same-ms events),
    // eventId ASC (final guarantee of total deterministic ordering — eventId is unique)
    List<EventEntity> findByAccountIdOrderByEventTimestampAscReceivedAtAscEventIdAsc(String accountId);
}
