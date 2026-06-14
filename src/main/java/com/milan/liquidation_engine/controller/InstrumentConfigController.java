package com.milan.liquidation_engine.controller;

import com.milan.liquidation_engine.dto.InstrumentConfigRequest;
import com.milan.liquidation_engine.entity.InstrumentConfig;
import com.milan.liquidation_engine.repository.InstrumentConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/instruments")
@RequiredArgsConstructor
public class InstrumentConfigController {

    private final InstrumentConfigRepository configRepository;

    @PostMapping
    @CacheEvict(value = "instrumentConfigs", key = "#request.instrument")
    public ResponseEntity<InstrumentConfig> saveConfig(@RequestBody InstrumentConfigRequest request) {
        InstrumentConfig config = configRepository.findByInstrument(request.getInstrument())
                .orElse(new InstrumentConfig());

        config.setInstrument(request.getInstrument());
        config.setInitialMarginRate(request.getInitialMarginRate());
        config.setMaintenanceMarginRate(request.getMaintenanceMarginRate());
        config.setLiquidationPenaltyRate(request.getLiquidationPenaltyRate());
        config.setLiquidityLimit(request.getLiquidityLimit());
        config.setVolatility(request.getVolatility());

        InstrumentConfig saved = configRepository.save(config);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{instrument}")
    public ResponseEntity<InstrumentConfig> getConfig(@PathVariable String instrument) {
        InstrumentConfig config = configRepository.findByInstrument(instrument)
                .orElseThrow(() -> new IllegalArgumentException("Config not found for instrument: " + instrument));
        return ResponseEntity.ok(config);
    }

    @GetMapping
    public ResponseEntity<List<InstrumentConfig>> getAllConfigs() {
        return ResponseEntity.ok(configRepository.findAll());
    }
}
