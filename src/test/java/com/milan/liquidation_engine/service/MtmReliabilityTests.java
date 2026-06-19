package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.repository.InstrumentConfigRepository;
import com.milan.liquidation_engine.repository.PositionRepository;
import com.milan.liquidation_engine.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;

import static org.mockito.Mockito.*;

public class MtmReliabilityTests {

    private PositionRepository positionRepository;
    private UserRepository userRepository;
    private MarginService marginService;
    private RiskThresholdService riskThresholdService;
    private LiquidationService liquidationService;
    private AuditLogService auditLogService;
    private StringRedisTemplate redisTemplate;
    private InstrumentConfigRepository instrumentConfigRepository;

    private MtmService mtmService;
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() {
        positionRepository = mock(PositionRepository.class);
        userRepository = mock(UserRepository.class);
        marginService = mock(MarginService.class);
        riskThresholdService = mock(RiskThresholdService.class);
        liquidationService = mock(LiquidationService.class);
        auditLogService = mock(AuditLogService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        instrumentConfigRepository = mock(InstrumentConfigRepository.class);

        mtmService = new MtmService(
                positionRepository,
                userRepository,
                marginService,
                riskThresholdService,
                liquidationService,
                auditLogService,
                redisTemplate,
                instrumentConfigRepository
        );

        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        
        // Default mock setup: no positions to simplify processing
        when(positionRepository.findByInstrument(anyString())).thenReturn(Collections.emptyList());
        
        // Default mock setup: events are not duplicates
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test
    void testPriceUpdate_DuplicateEventSkipped() {
        String instrument = "BTC-USD";
        BigDecimal price = new BigDecimal("65000.00");
        String eventId = "event-dup-123";

        // First call: event is not duplicate (setIfAbsent returns true)
        when(valueOps.setIfAbsent("priceEventProcessed:" + eventId, "PROCESSED", Duration.ofMinutes(10)))
                .thenReturn(true);

        mtmService.updateMarkPrice(instrument, price, eventId, null, null, "PRIMARY");

        // Verify it updates/processes (checks database or writes to Redis)
        verify(valueOps, times(1)).set("markPrice:" + instrument, price.toString());
        verify(positionRepository, times(1)).findByInstrument(instrument);

        // Reset mock interactions for second call
        reset(positionRepository);
        when(positionRepository.findByInstrument(anyString())).thenReturn(Collections.emptyList());

        // Second call: event is duplicate (setIfAbsent returns false)
        when(valueOps.setIfAbsent("priceEventProcessed:" + eventId, "PROCESSED", Duration.ofMinutes(10)))
                .thenReturn(false);

        mtmService.updateMarkPrice(instrument, price, eventId, null, null, "PRIMARY");

        // Verify second call is skipped: no database lookup
        verify(positionRepository, never()).findByInstrument(anyString());
    }

    @Test
    void testPriceUpdate_OutOfOrderSequenceSkipped() {
        String instrument = "BTC-USD";
        BigDecimal price1 = new BigDecimal("65000.00");
        BigDecimal price2 = new BigDecimal("64800.00");

        // Scenario: Sequence 100 processed, sequence 95 arrives later
        when(valueOps.get("priceSequence:" + instrument)).thenReturn("100");

        mtmService.updateMarkPrice(instrument, price2, "event-stale", 95L, null, "PRIMARY");

        // Verify it was skipped: no position lookups and no markPrice cache update
        verify(positionRepository, never()).findByInstrument(anyString());
        verify(valueOps, never()).set(eq("markPrice:" + instrument), anyString());
    }

    @Test
    void testPriceUpdate_MonotonicSequenceAccepted() {
        String instrument = "BTC-USD";
        BigDecimal price = new BigDecimal("65100.00");

        // Scenario: Sequence 100 processed, sequence 101 arrives
        when(valueOps.get("priceSequence:" + instrument)).thenReturn("100");

        mtmService.updateMarkPrice(instrument, price, "event-ok", 101L, null, "PRIMARY");

        // Verify it is accepted
        verify(positionRepository, times(1)).findByInstrument(instrument);
        verify(valueOps, times(1)).set("markPrice:" + instrument, price.toString());
        verify(valueOps, times(1)).set("priceSequence:" + instrument, "101");
    }

    @Test
    void testPriceUpdate_OutOfOrderTimestampSkipped() {
        String instrument = "BTC-USD";
        BigDecimal price = new BigDecimal("65000.00");

        // Scenario: Timestamp 1700000000 processed, timestamp 1699999999 arrives later
        when(valueOps.get("priceTimestamp:" + instrument)).thenReturn("1700000000");

        mtmService.updateMarkPrice(instrument, price, "event-stale-ts", null, 1699999999L, "PRIMARY");

        // Verify skipped
        verify(positionRepository, never()).findByInstrument(anyString());
    }

    @Test
    void testPriceUpdate_ExchangeFailover_BackupFeedActiveWhenPrimaryActive() {
        String instrument = "BTC-USD";
        BigDecimal price = new BigDecimal("65000.00");

        // Scenario: Primary feed is active (last active 1000ms ago)
        long currentTime = System.currentTimeMillis();
        when(valueOps.get("feedLastActive:primary")).thenReturn(String.valueOf(currentTime - 1000));

        mtmService.updateMarkPrice(instrument, price, "event-backup", null, null, "BACKUP");

        // Verify backup feed price is skipped/ignored
        verify(positionRepository, never()).findByInstrument(anyString());
    }

    @Test
    void testPriceUpdate_ExchangeFailover_BackupFeedProcessedWhenPrimaryInactive() {
        String instrument = "BTC-USD";
        BigDecimal price = new BigDecimal("65000.00");

        // Scenario: Primary feed has been inactive (last active 6000ms ago, timeout is 5000ms)
        long currentTime = System.currentTimeMillis();
        when(valueOps.get("feedLastActive:primary")).thenReturn(String.valueOf(currentTime - 6000));

        mtmService.updateMarkPrice(instrument, price, "event-backup", null, null, "BACKUP");

        // Verify backup feed price is processed
        verify(positionRepository, times(1)).findByInstrument(instrument);
        verify(valueOps, times(1)).set("markPrice:" + instrument, price.toString());
    }
}
