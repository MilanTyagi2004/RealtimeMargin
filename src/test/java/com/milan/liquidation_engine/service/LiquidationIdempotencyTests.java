package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.InstrumentConfig;
import com.milan.liquidation_engine.entity.Position;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.InstrumentConfigRepository;
import com.milan.liquidation_engine.repository.LiquidationEventRepository;
import com.milan.liquidation_engine.repository.PositionRepository;
import com.milan.liquidation_engine.repository.UserRepository;
import com.milan.liquidation_engine.repository.LiquidationIdempotencyRepository;
import com.milan.liquidation_engine.entity.LiquidationIdempotency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class LiquidationIdempotencyTests {

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

        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void testLiquidateAccount_SuccessOnFirstCall() {
        Long userId = 1L;
        String eventId = "event-123";
        User user = User.builder().id(userId).balance(new BigDecimal("10.0000")).build();

        // Mock Redis setIfAbsent to return true (isNew)
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        // Mock DB exists check to return false
        when(idempotencyRepository.existsByEventIdAndUserId(eventId, userId)).thenReturn(false);
        // Mock user lookup
        when(userRepository.findAndLockById(userId)).thenReturn(Optional.of(user));
        // Mock positions to return empty list to exit liquidation loop immediately
        when(positionRepository.findByUser(user)).thenReturn(Collections.emptyList());

        liquidationService.liquidateAccount(userId, RiskThresholdService.RiskState.LIQUIDATION, eventId);

        // Verify Redis cache checked/set
        verify(valueOps, times(1)).setIfAbsent(eq("liquidationProcessed:" + eventId + ":" + userId), eq("PROCESSED"), any());
        // Verify DB exists checked
        verify(idempotencyRepository, times(1)).existsByEventIdAndUserId(eventId, userId);
        // Verify idempotency record saved
        verify(idempotencyRepository, times(1)).save(any(LiquidationIdempotency.class));
        // Verify main flow processed
        verify(userRepository, times(1)).findAndLockById(userId);
    }

    @Test
    void testLiquidateAccount_DuplicateRedisHit() {
        Long userId = 1L;
        String eventId = "event-123";

        // Mock Redis setIfAbsent to return false (already exists)
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        liquidationService.liquidateAccount(userId, RiskThresholdService.RiskState.LIQUIDATION, eventId);

        // Verify Redis cache checked
        verify(valueOps, times(1)).setIfAbsent(eq("liquidationProcessed:" + eventId + ":" + userId), eq("PROCESSED"), any());
        // Verify execution short-circuited: DB check NOT called, user lock NOT called
        verify(idempotencyRepository, never()).existsByEventIdAndUserId(any(), any());
        verify(userRepository, never()).findAndLockById(any());
    }

    @Test
    void testLiquidateAccount_DuplicateDbHit() {
        Long userId = 1L;
        String eventId = "event-123";

        // Mock Redis setIfAbsent to return true (isNew)
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        // Mock DB exists check to return true
        when(idempotencyRepository.existsByEventIdAndUserId(eventId, userId)).thenReturn(true);

        liquidationService.liquidateAccount(userId, RiskThresholdService.RiskState.LIQUIDATION, eventId);

        // Verify DB exists checked
        verify(idempotencyRepository, times(1)).existsByEventIdAndUserId(eventId, userId);
        // Verify execution short-circuited: user lock NOT called, idempotency record NOT saved
        verify(idempotencyRepository, never()).save(any());
        verify(userRepository, never()).findAndLockById(any());
    }

    @Test
    void testLiquidateAccount_DataIntegrityViolationFallback() {
        Long userId = 1L;
        String eventId = "event-123";

        // Mock Redis setIfAbsent to return true (isNew)
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        // Mock DB exists check to return false
        when(idempotencyRepository.existsByEventIdAndUserId(eventId, userId)).thenReturn(false);
        // Mock save to throw unique constraint violation exception
        when(idempotencyRepository.save(any(LiquidationIdempotency.class))).thenThrow(new DataIntegrityViolationException("Unique constraint violation"));

        liquidationService.liquidateAccount(userId, RiskThresholdService.RiskState.LIQUIDATION, eventId);

        // Verify DB exists checked
        verify(idempotencyRepository, times(1)).existsByEventIdAndUserId(eventId, userId);
        // Verify save attempted
        verify(idempotencyRepository, times(1)).save(any());
        // Verify execution short-circuited on database exception: user lock NOT called
        verify(userRepository, never()).findAndLockById(any());
    }

    @Test
    void testLiquidateAccount_NullEventIdBypassesChecks() {
        Long userId = 1L;
        User user = User.builder().id(userId).balance(new BigDecimal("10.0000")).build();

        when(userRepository.findAndLockById(userId)).thenReturn(Optional.of(user));
        when(positionRepository.findByUser(user)).thenReturn(Collections.emptyList());

        liquidationService.liquidateAccount(userId, RiskThresholdService.RiskState.LIQUIDATION, null);

        // Verify checks were bypassed: Redis and DB never checked
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any());
        verify(idempotencyRepository, never()).existsByEventIdAndUserId(anyString(), anyLong());
        // Verify main flow processed
        verify(userRepository, times(1)).findAndLockById(userId);
    }
}
