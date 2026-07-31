package com.edwardmagongo.ledgerapi.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    List<Transaction> findByTransferId(UUID transferId);
    List<Transaction> findByAccountId(UUID accountId);

    @Query("""
            select t from Transaction t
            where t.account.id = :accountId
              and (cast(:from as Instant) is null or t.createdAt >= :from)
              and (cast(:to as Instant) is null or t.createdAt < :to)
              and (:type is null or t.type = :type)
            """)
    Page<Transaction> findHistory(@Param("accountId") UUID accountId,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  @Param("type") TransactionType type,
                                  Pageable pageable);
}
