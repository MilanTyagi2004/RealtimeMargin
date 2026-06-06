package com.milan.liquidation_engine.repository;

import com.milan.liquidation_engine.entity.InstrumentConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InstrumentConfigRepository extends JpaRepository<InstrumentConfig, Long> {

    Optional<InstrumentConfig> findByInstrument(String instrument);
}
