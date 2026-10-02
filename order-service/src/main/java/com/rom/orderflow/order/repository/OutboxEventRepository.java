package com.rom.orderflow.order.repository;

import com.rom.orderflow.order.outbox.OutboxEvent;
import com.rom.orderflow.order.outbox.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(
            OutboxStatus status
    );

    @Query(
            value = """
            SELECT *
            FROM outbox_events
            WHERE (
                status = 'PENDING'
                OR (
                    status = 'PROCESSING'
                    AND locked_at < :staleBefore
                )
            )
            AND next_attempt_at <= :now
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """,
            nativeQuery = true
    )
    List<OutboxEvent> findClaimableEvents(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            @Param("batchSize") int batchSize
    );
}