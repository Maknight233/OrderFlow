package com.rom.orderflow.order.repository;

import com.rom.orderflow.order.outbox.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import com.rom.orderflow.order.outbox.OutboxStatus;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE (status = 'PENDING' OR (status = 'PROCESSING' AND locked_at < :staleBefore))
              AND next_attempt_at <= :now
            ORDER BY created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findClaimableEvents(@Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore, @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.id = :id")
    Optional<OutboxEvent> findForUpdate(@Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_events SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                locked_at = NULL, locked_by = NULL, last_error = NULL
            WHERE id = :id AND status = 'PROCESSING' AND locked_by = :token
            """, nativeQuery = true)
    int markPublished(@Param("id") UUID id, @Param("token") String token);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_events SET status = 'PENDING', retry_count = 0,
                next_attempt_at = :now, locked_at = NULL, locked_by = NULL, last_error = NULL
            WHERE id = :id AND status = 'FAILED'
            """, nativeQuery = true)
    int requeueFailed(@Param("id") UUID id, @Param("now") Instant now);
}
