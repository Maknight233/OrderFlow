package com.rom.orderflow.order.saga;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;

public interface OrderSagaRepository extends JpaRepository<OrderSaga, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OrderSaga s where s.orderId = :id")
    Optional<OrderSaga> findForUpdate(@Param("id") UUID id);

    @Query("select s.orderId from OrderSaga s where s.state in :states and s.deadline <= :now order by s.deadline")
    List<UUID> findExpired(@Param("states") Collection<SagaState> states,
                          @Param("now") Instant now, Pageable pageable);
}
