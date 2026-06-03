package com.eventledger.account.repository;

import com.eventledger.account.model.TransactionEntity;
import com.eventledger.account.model.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {

    boolean existsByEventId(String eventId);

    Optional<TransactionEntity> findByEventId(String eventId);

    // DESC with eventId tie-break for deterministic ordering of same-timestamp transactions
    List<TransactionEntity> findByAccountIdOrderByEventTimestampDescEventIdDesc(String accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM TransactionEntity t " +
           "WHERE t.accountId = :accountId AND t.type = :type")
    BigDecimal sumAmountByAccountIdAndType(@Param("accountId") String accountId,
                                           @Param("type") TransactionType type);
}
