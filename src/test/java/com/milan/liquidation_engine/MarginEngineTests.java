package com.milan.liquidation_engine;

import com.milan.liquidation_engine.entity.InstrumentConfig;
import com.milan.liquidation_engine.entity.Position;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.InstrumentConfigRepository;
import com.milan.liquidation_engine.repository.PositionRepository;
import com.milan.liquidation_engine.repository.UserRepository;
import com.milan.liquidation_engine.service.AuditLogService;
import com.milan.liquidation_engine.service.MarginService;
import com.milan.liquidation_engine.service.RiskThresholdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class MarginEngineTests {

    private InstrumentConfigRepository instrumentConfigRepository;
    private PositionRepository positionRepository;
    private UserRepository userRepository;
    private AuditLogService auditLogService;

    private MarginService marginService;
    private RiskThresholdService riskThresholdService;

    @BeforeEach
    void setUp() {
        instrumentConfigRepository = mock(InstrumentConfigRepository.class);
        positionRepository = mock(PositionRepository.class);
        userRepository = mock(UserRepository.class);
        auditLogService = mock(AuditLogService.class);

        marginService = new MarginService(instrumentConfigRepository, positionRepository);
        riskThresholdService = new RiskThresholdService(marginService, userRepository, auditLogService);
    }

    @Test
    void testCalculatePnL() {
        Position longPos = Position.builder()
                .direction("LONG")
                .quantity(new BigDecimal("10.0000"))
                .entryPrice(new BigDecimal("100.0000"))
                .markPrice(new BigDecimal("110.0000"))
                .build();

        Position shortPos = Position.builder()
                .direction("SHORT")
                .quantity(new BigDecimal("5.0000"))
                .entryPrice(new BigDecimal("100.0000"))
                .markPrice(new BigDecimal("110.0000"))
                .build();

        assertEquals(0, marginService.calculatePositionPnL(longPos).compareTo(new BigDecimal("100.0000")));
        assertEquals(0, marginService.calculatePositionPnL(shortPos).compareTo(new BigDecimal("-50.0000")));
    }

    @Test
    void testInitialMarginAndMaintenanceMargin() {
        Position pos = Position.builder()
                .quantity(new BigDecimal("10.0000"))
                .markPrice(new BigDecimal("100.0000"))
                .build();

        InstrumentConfig config = InstrumentConfig.builder()
                .initialMarginRate(new BigDecimal("0.1000"))
                .maintenanceMarginRate(new BigDecimal("0.0500"))
                .volatility(new BigDecimal("0.0200"))
                .build();

        // Value = 10 * 100 = 1000
        // IM = Value * (InitialMarginRate + Volatility) = 1000 * (0.10 + 0.02) = 120
        // MM = Value * MaintenanceMarginRate = 1000 * 0.05 = 50
        assertEquals(0, marginService.calculateInitialMargin(pos, config).compareTo(new BigDecimal("120.0000")));
        assertEquals(0, marginService.calculateMaintenanceMargin(pos, config).compareTo(new BigDecimal("50.0000")));
    }

    @Test
    void testAccountRiskThresholds() {
        User user = User.builder()
                .id(1L)
                .username("test_user")
                .balance(new BigDecimal("100.0000"))
                .tradingRestricted(false)
                .build();

        Position pos = Position.builder()
                .id(1L)
                .user(user)
                .instrument("BTC-USD")
                .direction("LONG")
                .quantity(new BigDecimal("2.0000"))
                .entryPrice(new BigDecimal("100.0000"))
                .markPrice(new BigDecimal("80.0000")) // Value = 160, PnL = -40, Equity = 60
                .build();

        InstrumentConfig config = InstrumentConfig.builder()
                .instrument("BTC-USD")
                .initialMarginRate(new BigDecimal("0.2000"))
                .maintenanceMarginRate(new BigDecimal("0.1000")) // MM = 16
                .volatility(BigDecimal.ZERO)
                .build();

        when(positionRepository.findByUser(user)).thenReturn(Collections.singletonList(pos));
        when(instrumentConfigRepository.findByInstrument("BTC-USD")).thenReturn(Optional.of(config));

        // Equity = 100 - 40 = 60
        // MM = 16
        // Margin Call Limit = 16 * 1.2 = 19.2
        // Since Equity (60) > Margin Call Limit (19.2), risk state should be SAFE
        assertEquals(RiskThresholdService.RiskState.SAFE, riskThresholdService.evaluateAccountRisk(user));

        // Now drop the mark price to 40
        // Value = 80, PnL = 2 * (40 - 100) = -120. Equity = 100 - 120 = -20
        // MM = 80 * 0.10 = 8
        // Equity is negative (-20) which is below MM (8) and below Emergency Limit (8 * 0.8 = 6.4)
        pos.setMarkPrice(new BigDecimal("40.0000"));
        assertEquals(RiskThresholdService.RiskState.EMERGENCY, riskThresholdService.evaluateAccountRisk(user));
    }

    @Test
    void testCalculateInitialMarginWithConcentrationRisk() {
        User user = User.builder()
                .id(2L)
                .username("concentration_user")
                .balance(new BigDecimal("100.0000"))
                .build();

        Position pos = Position.builder()
                .id(2L)
                .user(user)
                .instrument("BTC-USD")
                .direction("LONG")
                .quantity(new BigDecimal("0.9000"))
                .entryPrice(new BigDecimal("100.0000"))
                .markPrice(new BigDecimal("100.0000"))
                .build();

        InstrumentConfig config = InstrumentConfig.builder()
                .instrument("BTC-USD")
                .initialMarginRate(new BigDecimal("0.1000"))
                .maintenanceMarginRate(new BigDecimal("0.0500"))
                .volatility(BigDecimal.ZERO)
                .build();

        when(positionRepository.findByUser(user)).thenReturn(Collections.singletonList(pos));

        BigDecimal initialMargin = marginService.calculateInitialMargin(pos, config);

        assertEquals(0, initialMargin.compareTo(new BigDecimal("9.45000000")));
    }
}
