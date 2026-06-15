package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.InstrumentConfig;
import com.milan.liquidation_engine.entity.Position;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.InstrumentConfigRepository;
import com.milan.liquidation_engine.repository.LiquidationEventRepository;
import com.milan.liquidation_engine.repository.PositionRepository;
import com.milan.liquidation_engine.repository.UserRepository;
import com.milan.liquidation_engine.repository.LiquidationIdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class LiquidationServiceTests {

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

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void testFindOptimalLiquidationTarget_ConcentrationRisk() {
        User user = User.builder().id(1L).balance(new BigDecimal("10.0000")).build();

        // Position 1: BTC-USD - high concentration (value=90, equity=100)
        Position btcPos = Position.builder()
                .id(1L)
                .user(user)
                .instrument("BTC-USD")
                .direction("LONG")
                .quantity(new BigDecimal("1.0000"))
                .markPrice(new BigDecimal("90.0000"))
                .build();

        // Position 2: ETH-USD - low concentration (value=10, equity=100)
        Position ethPos = Position.builder()
                .id(2L)
                .user(user)
                .instrument("ETH-USD")
                .direction("LONG")
                .quantity(new BigDecimal("1.0000"))
                .markPrice(new BigDecimal("10.0000"))
                .build();

        InstrumentConfig btcConfig = InstrumentConfig.builder()
                .instrument("BTC-USD")
                .maintenanceMarginRate(new BigDecimal("0.1000"))
                .liquidityLimit(new BigDecimal("1000.0000"))
                .volatility(new BigDecimal("0.0200"))
                .build();

        InstrumentConfig ethConfig = InstrumentConfig.builder()
                .instrument("ETH-USD")
                .maintenanceMarginRate(new BigDecimal("0.1000"))
                .liquidityLimit(new BigDecimal("1000.0000"))
                .volatility(new BigDecimal("0.0200"))
                .build();

        when(instrumentConfigRepository.findByInstrument("BTC-USD")).thenReturn(Optional.of(btcConfig));
        when(instrumentConfigRepository.findByInstrument("ETH-USD")).thenReturn(Optional.of(ethConfig));

        when(marginService.calculateAccountEquity(user)).thenReturn(new BigDecimal("100.0000"));

        when(marginService.calculatePositionValue(btcPos)).thenReturn(new BigDecimal("90.0000"));
        when(marginService.calculatePositionValue(ethPos)).thenReturn(new BigDecimal("10.0000"));

        // MM contribution: BTC = 9.0, ETH = 1.0
        when(marginService.calculateMaintenanceMargin(btcPos, btcConfig)).thenReturn(new BigDecimal("9.0000"));
        when(marginService.calculateMaintenanceMargin(ethPos, ethConfig)).thenReturn(new BigDecimal("1.0000"));

        // Evaluate
        Position optimal = liquidationService.findOptimalLiquidationTarget(user, Arrays.asList(btcPos, ethPos), RiskThresholdService.RiskState.LIQUIDATION);

        assertNotNull(optimal);
        assertEquals("BTC-USD", optimal.getInstrument(), "Should prioritize high-concentration position (BTC-USD)");
    }

    @Test
    void testFindOptimalLiquidationTarget_LiquidityConstraint() {
        User user = User.builder().id(1L).balance(new BigDecimal("100.0000")).build();

        // Position 1: BTC-USD - high MM but very illiquid (low limit -> high slippage)
        Position btcPos = Position.builder()
                .id(1L)
                .user(user)
                .instrument("BTC-USD")
                .direction("LONG")
                .quantity(new BigDecimal("1.0000"))
                .markPrice(new BigDecimal("100.0000"))
                .build();

        // Position 2: ETH-USD - similar value but highly liquid (high limit -> low slippage)
        Position ethPos = Position.builder()
                .id(2L)
                .user(user)
                .instrument("ETH-USD")
                .direction("LONG")
                .quantity(new BigDecimal("1.0000"))
                .markPrice(new BigDecimal("100.0000"))
                .build();

        InstrumentConfig btcConfig = InstrumentConfig.builder()
                .instrument("BTC-USD")
                .maintenanceMarginRate(new BigDecimal("0.1000"))
                .liquidityLimit(new BigDecimal("10.0000")) // very low limit
                .volatility(new BigDecimal("0.1000"))
                .build();

        InstrumentConfig ethConfig = InstrumentConfig.builder()
                .instrument("ETH-USD")
                .maintenanceMarginRate(new BigDecimal("0.1000"))
                .liquidityLimit(new BigDecimal("10000.0000")) // high limit
                .volatility(new BigDecimal("0.0200"))
                .build();

        when(instrumentConfigRepository.findByInstrument("BTC-USD")).thenReturn(Optional.of(btcConfig));
        when(instrumentConfigRepository.findByInstrument("ETH-USD")).thenReturn(Optional.of(ethConfig));

        when(marginService.calculateAccountEquity(user)).thenReturn(new BigDecimal("200.0000")); // no concentration

        when(marginService.calculatePositionValue(btcPos)).thenReturn(new BigDecimal("100.0000"));
        when(marginService.calculatePositionValue(ethPos)).thenReturn(new BigDecimal("100.0000"));

        when(marginService.calculateMaintenanceMargin(btcPos, btcConfig)).thenReturn(new BigDecimal("10.0000"));
        when(marginService.calculateMaintenanceMargin(ethPos, ethConfig)).thenReturn(new BigDecimal("10.0000"));

        // Evaluate
        Position optimal = liquidationService.findOptimalLiquidationTarget(user, Arrays.asList(btcPos, ethPos), RiskThresholdService.RiskState.LIQUIDATION);

        assertNotNull(optimal);
        assertEquals("ETH-USD", optimal.getInstrument(), "Should prioritize highly liquid position (ETH-USD) over illiquid one");
    }
}
