package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.InstrumentConfig;
import com.milan.liquidation_engine.entity.LiquidationEvent;
import com.milan.liquidation_engine.entity.Position;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.InstrumentConfigRepository;
import com.milan.liquidation_engine.repository.LiquidationEventRepository;
import com.milan.liquidation_engine.repository.PositionRepository;
import com.milan.liquidation_engine.repository.UserRepository;
import com.milan.liquidation_engine.repository.LiquidationIdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class FailureScenarioSimulationTests {

    private UserRepository userRepository;
    private PositionRepository positionRepository;
    private InstrumentConfigRepository instrumentConfigRepository;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private LiquidationEventRepository liquidationEventRepository;
    private MarginService marginService;
    private RiskThresholdService riskThresholdService;
    private AuditLogService auditLogService;
    private StringRedisTemplate redisTemplate;
    private LiquidationIdempotencyRepository idempotencyRepository;

    private LiquidationService liquidationService;
    private MtmService mtmService;
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        positionRepository = mock(PositionRepository.class);
        instrumentConfigRepository = mock(InstrumentConfigRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        liquidationEventRepository = mock(LiquidationEventRepository.class);
        marginService = mock(MarginService.class);
        riskThresholdService = mock(RiskThresholdService.class);
        auditLogService = mock(AuditLogService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        idempotencyRepository = mock(LiquidationIdempotencyRepository.class);

        liquidationService = new LiquidationService(
                userRepository,
                positionRepository,
                instrumentConfigRepository,
                kafkaTemplate,
                liquidationEventRepository,
                marginService,
                riskThresholdService,
                auditLogService,
                redisTemplate,
                idempotencyRepository
        );

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
    }

    @Test
    void testPriceGapSimulation_NegativeBalanceProtection() {
        Long userId = 1L;
        User user = User.builder()
                .id(userId)
                .balance(new BigDecimal("10.0000"))
                .tradingRestricted(true)
                .build();

        // 1. Position: BTC-USD long. Entry=100. Mark dropped to 40 (severe gap). Size=1.0
        Position btcPos = Position.builder()
                .id(1L)
                .user(user)
                .instrument("BTC-USD")
                .direction("LONG")
                .quantity(new BigDecimal("1.0000"))
                .entryPrice(new BigDecimal("100.0000"))
                .markPrice(new BigDecimal("40.0000"))
                .build();

        InstrumentConfig config = InstrumentConfig.builder()
                .instrument("BTC-USD")
                .initialMarginRate(new BigDecimal("0.2000"))
                .maintenanceMarginRate(new BigDecimal("0.1000"))
                .liquidationPenaltyRate(new BigDecimal("0.0100"))
                .liquidityLimit(new BigDecimal("1000.0000"))
                .volatility(BigDecimal.ZERO)
                .build();

        when(userRepository.findAndLockById(userId)).thenReturn(Optional.of(user));
        // First loop: returns BTC position. Second loop: returns empty list to stop loop
        when(positionRepository.findByUser(user))
                .thenReturn(Collections.singletonList(btcPos))
                .thenReturn(Collections.emptyList());

        when(instrumentConfigRepository.findByInstrument("BTC-USD")).thenReturn(Optional.of(config));

        // Initial check metrics
        when(marginService.calculateAccountEquity(user)).thenReturn(new BigDecimal("-50.0000"));
        when(marginService.calculateAccountMaintenanceMargin(user)).thenReturn(new BigDecimal("4.0000"));
        when(marginService.calculatePositionValue(btcPos)).thenReturn(new BigDecimal("40.0000"));
        when(marginService.calculateMaintenanceMargin(btcPos, config)).thenReturn(new BigDecimal("4.0000"));

        // Execute liquidation in EMERGENCY state
        liquidationService.liquidateAccount(userId, RiskThresholdService.RiskState.EMERGENCY);

        // Verification of Negative Balance Prevention:
        // PnL realized = 1.0 * (executionPrice - 100).
        // Since proposed balance is deep negative, verify that the balance was capped at exactly 0.0000.
        assertEquals(0, user.getBalance().compareTo(BigDecimal.ZERO), "User balance must be protected and capped at 0.0");

        // Verify that a liquidation event was logged and saved
        ArgumentCaptor<LiquidationEvent> eventCaptor = ArgumentCaptor.forClass(LiquidationEvent.class);
        verify(liquidationEventRepository, times(1)).save(eventCaptor.capture());
        
        LiquidationEvent capturedEvent = eventCaptor.getValue();
        assertTrue(capturedEvent.getReason().contains("Negative balance prevented"), "Deficit must be logged in event reason");
    }

    @Test
    void testVolatilitySpikeSimulation_MarginShock() {
        String instrument = "BTC-USD";
        BigDecimal oldPrice = new BigDecimal("100.0000");
        BigDecimal newPrice = new BigDecimal("60.0000"); // 40% sudden change

        when(valueOps.get("markPrice:" + instrument)).thenReturn(oldPrice.toString());
        
        InstrumentConfig config = InstrumentConfig.builder()
                .instrument(instrument)
                .volatility(new BigDecimal("0.0500"))
                .build();
        when(instrumentConfigRepository.findByInstrument(instrument)).thenReturn(Optional.of(config));
        when(valueOps.get("dynamicVolatility:" + instrument)).thenReturn("0.0500");

        // Process price update causing returns shock
        mtmService.updateMarkPrice(instrument, newPrice);

        // VolNew = 0.05 * 0.90 + 0.40 * 1.5 = 0.045 + 0.60 = 0.645 -> capped at maxCap (0.50)
        verify(valueOps, times(1)).set(eq("dynamicVolatility:" + instrument), eq("0.50"));
    }

    @Test
    void testLockConcurrency_SimultaneousActions() throws Exception {
        Long userId = 1L;
        User user = User.builder().id(userId).balance(new BigDecimal("100.00")).build();
        
        // Simulating simultaneous access lock queries:
        // findAndLockById must be called by both threads, and verify locking is used at method entries.
        when(userRepository.findAndLockById(userId)).thenReturn(Optional.of(user));
        when(positionRepository.findByUser(user)).thenReturn(Collections.emptyList());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCounter = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    liquidationService.liquidateAccount(userId, RiskThresholdService.RiskState.LIQUIDATION);
                    successCounter.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Pessimistic database lock must be requested by both executions to serialize them
        verify(userRepository, times(2)).findAndLockById(userId);
    }

    @Test
    void testPartialFills_LoopTerminationOnSafety() {
        Long userId = 1L;
        User user = User.builder()
                .id(userId)
                .balance(new BigDecimal("10.0000"))
                .tradingRestricted(true)
                .build();

        // 1. Long position BTC-USD: size = 4.0, entryPrice = 100, mark = 80. Value = 320.
        Position btcPos = Position.builder()
                .id(1L)
                .user(user)
                .instrument("BTC-USD")
                .direction("LONG")
                .quantity(new BigDecimal("4.0000"))
                .entryPrice(new BigDecimal("100.0000"))
                .markPrice(new BigDecimal("80.0000"))
                .build();

        InstrumentConfig config = InstrumentConfig.builder()
                .instrument("BTC-USD")
                .initialMarginRate(new BigDecimal("0.2000"))
                .maintenanceMarginRate(new BigDecimal("0.1000"))
                .liquidationPenaltyRate(new BigDecimal("0.0100"))
                .liquidityLimit(new BigDecimal("1000.0000"))
                .volatility(BigDecimal.ZERO)
                .build();

        when(userRepository.findAndLockById(userId)).thenReturn(Optional.of(user));
        when(positionRepository.findByUser(user)).thenReturn(Collections.singletonList(btcPos));
        when(instrumentConfigRepository.findByInstrument("BTC-USD")).thenReturn(Optional.of(config));

        // Initial recalculation metrics
        when(marginService.calculatePositionValue(btcPos)).thenReturn(new BigDecimal("320.0000"));
        when(marginService.calculateAccountEquity(user)).thenReturn(new BigDecimal("-10.0000"));
        when(marginService.calculateAccountMaintenanceMargin(user)).thenReturn(new BigDecimal("32.0000"));
        when(marginService.calculateMaintenanceMargin(btcPos, config)).thenReturn(new BigDecimal("32.0000"));

        // Recalculation after 1 step of partial liquidation (25% closed -> size becomes 3.0, value becomes 240)
        // Set equity to 50.0 and MM to 24.0 (Safe state: 50.0 > 24.0 * 1.05 = 25.2)
        doAnswer(invocation -> {
            btcPos.setQuantity(new BigDecimal("3.0000"));
            when(marginService.calculateAccountEquity(user)).thenReturn(new BigDecimal("50.0000"));
            when(marginService.calculateAccountMaintenanceMargin(user)).thenReturn(new BigDecimal("24.0000"));
            return null;
        }).when(marginService).recalculateUserPositionsAndMargin(user);

        // Run partial liquidation
        liquidationService.liquidateAccount(userId, RiskThresholdService.RiskState.LIQUIDATION);

        // Assert that the position was partially filled (25% step of 4.0 closed, leaving 3.0)
        assertEquals(0, btcPos.getQuantity().compareTo(new BigDecimal("3.0000")), "Should partially close 25% of the position size");

        // Verify that the loop exited after exactly 1 step because the account was verified as safe
        verify(positionRepository, times(1)).save(btcPos);
        assertFalse(user.isTradingRestricted(), "Trading restriction should be lifted when account becomes safe");
    }
}
