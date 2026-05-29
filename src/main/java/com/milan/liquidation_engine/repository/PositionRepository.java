package com.milan.liquidation_engine.repository;

import com.milan.liquidation_engine.entity.Position;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionRepository extends JpaRepository<Position, Long> {

}