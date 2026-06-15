package com.milan.liquidation_engine.repository;

import com.milan.liquidation_engine.entity.LiquidationIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LiquidationIdempotencyRepository extends JpaRepository<LiquidationIdempotency, Long> {
    boolean existsByEventIdAndUserId(String eventId, Long userId);
}
