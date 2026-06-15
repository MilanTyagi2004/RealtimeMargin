package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.dto.LiquidationBroadcastEvent;
import com.milan.liquidation_engine.entity.InstrumentConfig;
import com.milan.liquidation_engine.entity.LiquidationEvent;
import com.milan.liquidation_engine.entity.Position;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.InstrumentConfigRepository;
import com.milan.liquidation_engine.repository.LiquidationEventRepository;
import com.milan.liquidation_engine.repository.PositionRepository;
import com.milan.liquidation_engine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LiquidationService {

    private final UserRepository userRepository;
    private final PositionRepository positionRepository;
    private final InstrumentConfigRepository instrumentConfigRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final LiquidationEventRepository liquidationEventRepository;
    private final MarginService marginService;
    private final RiskThresholdService riskThresholdService;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redisTemplate;

    // Simulation parameters
    private static final BigDecimal NORMAL_SLIPPAGE = new BigDecimal("0.02"); // 2%
    private static final BigDecimal EMERGENCY_SLIPPAGE = new BigDecimal("0.05"); // 5%
    private static final BigDecimal LIQUIDATION_STEP = new BigDecimal("0.25"); // 25% steps

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void liquidateAccount(Long userId, RiskThresholdService.RiskState riskState) {
        log.warn("Initiating liquidation routine for user: {}, state: {}", userId, riskState);

        // Lock the user record
        User user = userRepository.findAndLockById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        int stepCount = 0;
        while (true) {
            stepCount++;
            List<Position> positions = positionRepository.findByUser(user);
            if (positions.isEmpty()) {
                log.info("No positions left to liquidate for user {}", userId);
                break;
            }

            // Find optimal position to liquidate based on risk-weighted slippage score
            Position targetPos = findOptimalLiquidationTarget(user, positions, riskState);

            if (targetPos == null) {
                break;
            }

            InstrumentConfig config = instrumentConfigRepository.findByInstrument(targetPos.getInstrument())
                    .orElseThrow(() -> new IllegalStateException("Config missing for " + targetPos.getInstrument()));

            // Capture initial margin snapshot for audit logging
            BigDecimal initialEquity = marginService.calculateAccountEquity(user);
            BigDecimal initialMM = marginService.calculateAccountMaintenanceMargin(user);
            String snapshot = String.format("Equity: %s, MM: %s, Balance: %s, Target Instrument: %s, PosQty: %s",
                    initialEquity, initialMM, user.getBalance(), targetPos.getInstrument(), targetPos.getQuantity());

            // Determine quantity to close
            BigDecimal quantityToClose;
            String actionType;

            if (riskState == RiskThresholdService.RiskState.EMERGENCY) {
                // Emergency: close entire position immediately
                quantityToClose = targetPos.getQuantity();
                actionType = "EMERGENCY_FULL_LIQUIDATION";
            } else {
                // Normal liquidation: close 25% step
                quantityToClose = targetPos.getQuantity().multiply(LIQUIDATION_STEP).setScale(4, RoundingMode.HALF_UP);
                actionType = "PARTIAL_LIQUIDATION";

                // If remaining quantity would be very small, close the whole position
                BigDecimal remainingQty = targetPos.getQuantity().subtract(quantityToClose);
                if (remainingQty.compareTo(new BigDecimal("0.01")) < 0) {
                    quantityToClose = targetPos.getQuantity();
                    actionType = "FULL_LIQUIDATION";
                }
            }

            // Calculate dynamic slippage rate based on position size relative to liquidity limit and volatility
            BigDecimal baseSlippage = (riskState == RiskThresholdService.RiskState.EMERGENCY) ? EMERGENCY_SLIPPAGE : NORMAL_SLIPPAGE;
            BigDecimal vol = config.getVolatility() != null ? config.getVolatility() : BigDecimal.ZERO;
            try {
                String cachedVol = redisTemplate.opsForValue().get("dynamicVolatility:" + config.getInstrument());
                if (cachedVol != null) {
                    vol = new BigDecimal(cachedVol);
                }
            } catch (Exception e) {
                // fallback gracefully
            }

            BigDecimal sizeRatio = BigDecimal.ZERO;
            BigDecimal targetPosValue = quantityToClose.multiply(targetPos.getMarkPrice());
            BigDecimal liquidityLimit = config.getLiquidityLimit();
            if (liquidityLimit != null && liquidityLimit.compareTo(BigDecimal.ZERO) > 0) {
                sizeRatio = targetPosValue.divide(liquidityLimit, 4, RoundingMode.HALF_UP);
            }

            BigDecimal slippageRate = baseSlippage
                    .multiply(BigDecimal.ONE.add(sizeRatio))
                    .multiply(BigDecimal.ONE.add(vol))
                    .setScale(4, RoundingMode.HALF_UP);

            // Cap slippage at 50% max to ensure pricing stays within bounds
            BigDecimal maxSlippage = new BigDecimal("0.50");
            if (slippageRate.compareTo(maxSlippage) > 0) {
                slippageRate = maxSlippage;
            }

            // Calculate execution price considering dynamic slippage
            BigDecimal markPrice = targetPos.getMarkPrice();
            BigDecimal executionPrice;
            if ("LONG".equalsIgnoreCase(targetPos.getDirection())) {
                executionPrice = markPrice.multiply(BigDecimal.ONE.subtract(slippageRate));
            } else {
                executionPrice = markPrice.multiply(BigDecimal.ONE.add(slippageRate));
            }

            // Calculate realized PnL
            BigDecimal pnl;
            if ("LONG".equalsIgnoreCase(targetPos.getDirection())) {
                pnl = quantityToClose.multiply(executionPrice.subtract(targetPos.getEntryPrice()));
            } else {
                pnl = quantityToClose.multiply(targetPos.getEntryPrice().subtract(executionPrice));
            }

            // Calculate liquidation penalty (1% of liquidated value goes to broker/liquidation fund)
            BigDecimal penaltyRate = config.getLiquidationPenaltyRate();
            BigDecimal liquidatedValue = quantityToClose.multiply(executionPrice);
            BigDecimal penalty = liquidatedValue.multiply(penaltyRate);

            // Total balance impact
            BigDecimal balanceImpact = pnl.subtract(penalty);
            BigDecimal currentBalance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
            BigDecimal proposedBalance = currentBalance.add(balanceImpact);

            // Negative Balance Prevention
            BigDecimal brokerAbsorbedLoss = BigDecimal.ZERO;
            if (proposedBalance.compareTo(BigDecimal.ZERO) < 0) {
                brokerAbsorbedLoss = proposedBalance.negate();
                proposedBalance = BigDecimal.ZERO;
                log.error("Negative balance prevented. Broker absorbed loss of: {} for user {}", brokerAbsorbedLoss, userId);
            }

            user.setBalance(proposedBalance);

            // Update position quantity
            if (quantityToClose.compareTo(targetPos.getQuantity()) >= 0) {
                positionRepository.delete(targetPos);
            } else {
                targetPos.setQuantity(targetPos.getQuantity().subtract(quantityToClose));
                positionRepository.save(targetPos);
            }

            // Save Liquidation Event
            String eventReason = String.format("%s of %s @ %s due to margin deficit.", actionType, targetPos.getInstrument(), executionPrice);
            if (brokerAbsorbedLoss.compareTo(BigDecimal.ZERO) > 0) {
                eventReason += " Negative balance prevented. Broker loss: " + brokerAbsorbedLoss;
            }

            LiquidationEvent event = LiquidationEvent.builder()
                    .user(user)
                    .instrument(targetPos.getInstrument())
                    .action(actionType)
                    .quantityLiquidated(quantityToClose)
                    .price(executionPrice)
                    .slippage(slippageRate)
                    .penalty(penalty)
                    .marginSnapshot(snapshot)
                    .reason(eventReason)
                    .timestamp(LocalDateTime.now())
                    .build();
            liquidationEventRepository.save(event);

            // Broadcast liquidation details asynchronously to Kafka
            try {
                LiquidationBroadcastEvent broadcastEvent = LiquidationBroadcastEvent.builder()
                        .userId(user.getId())
                        .instrument(targetPos.getInstrument())
                        .action(actionType)
                        .quantityLiquidated(quantityToClose)
                        .price(executionPrice)
                        .penalty(penalty)
                        .reason(eventReason)
                        .timestamp(event.getTimestamp())
                        .build();
                kafkaTemplate.send("liquidation-events", broadcastEvent);
                log.info("Successfully broadcast liquidation event to Kafka for user: {}", user.getId());
            } catch (Exception e) {
                log.warn("Failed to broadcast liquidation event to Kafka for user {}: {}", user.getId(), e.getMessage());
            }

            // Recalculate account margins
            marginService.recalculateUserPositionsAndMargin(user);
            userRepository.save(user);

            // Check if account returned to safe thresholds
            BigDecimal finalEquity = marginService.calculateAccountEquity(user);
            BigDecimal finalMM = marginService.calculateAccountMaintenanceMargin(user);
            
            // Safety buffer: Equity should be at least MM * 1.05 to stop liquidation
            BigDecimal safetyThreshold = finalMM.multiply(new BigDecimal("1.05"));

            log.info("Liquidation step {} completed. Equity: {}, SafetyThreshold: {}", stepCount, finalEquity, safetyThreshold);
            auditLogService.logEvent(userId, actionType, "SUCCESS",
                    String.format("Step %d: Closed %s %s. Equity is now %s (MM buffer: %s).",
                            stepCount, quantityToClose, targetPos.getInstrument(), finalEquity, safetyThreshold));

            if (finalMM.compareTo(BigDecimal.ZERO) == 0 || finalEquity.compareTo(safetyThreshold) >= 0) {
                log.info("Account is now safe. Stopping liquidation.");
                user.setTradingRestricted(false);
                userRepository.save(user);
                break;
            }

            // Prevent infinite loop in testing/simulation
            if (stepCount > 50) {
                log.error("Liquidation loop exceeded limit of 50. Aborting to prevent hangs.");
                break;
            }
        }
    }

    public Position findOptimalLiquidationTarget(User user, List<Position> positions, RiskThresholdService.RiskState riskState) {
        Position bestTarget = null;
        BigDecimal highestScore = BigDecimal.valueOf(-Double.MAX_VALUE);
        BigDecimal equity = marginService.calculateAccountEquity(user);

        for (Position pos : positions) {
            InstrumentConfig config = instrumentConfigRepository.findByInstrument(pos.getInstrument()).orElse(null);
            if (config == null) {
                continue;
            }

            BigDecimal posValue = marginService.calculatePositionValue(pos);
            BigDecimal maintenanceMargin = marginService.calculateMaintenanceMargin(pos, config);

            // 1. Base Risk Weight (Maintenance Margin)
            BigDecimal riskWeight = maintenanceMargin;

            // 2. Concentration Penalty adjustment
            BigDecimal concentrationPenaltyMultiplier = BigDecimal.ONE;
            if (equity.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal concentration = posValue.divide(equity, 4, RoundingMode.HALF_UP);
                if (concentration.compareTo(new BigDecimal("0.80")) > 0) {
                    BigDecimal excess = concentration.subtract(new BigDecimal("0.80"));
                    // Boost risk weight based on concentration excess
                    concentrationPenaltyMultiplier = BigDecimal.ONE.add(excess.multiply(new BigDecimal("5.0")));
                }
            }
            BigDecimal effectiveRisk = riskWeight.multiply(concentrationPenaltyMultiplier);

            // 3. Dynamic Slippage estimate
            BigDecimal baseSlippage = (riskState == RiskThresholdService.RiskState.EMERGENCY) ? EMERGENCY_SLIPPAGE : NORMAL_SLIPPAGE;
            BigDecimal vol = config.getVolatility() != null ? config.getVolatility() : BigDecimal.ZERO;
            try {
                String cachedVol = redisTemplate.opsForValue().get("dynamicVolatility:" + config.getInstrument());
                if (cachedVol != null) {
                    vol = new BigDecimal(cachedVol);
                }
            } catch (Exception e) {
                // fallback gracefully
            }

            BigDecimal sizeRatio = BigDecimal.ZERO;
            BigDecimal liquidityLimit = config.getLiquidityLimit();
            if (liquidityLimit != null && liquidityLimit.compareTo(BigDecimal.ZERO) > 0) {
                sizeRatio = posValue.divide(liquidityLimit, 4, RoundingMode.HALF_UP);
            }

            BigDecimal estimatedSlippage = baseSlippage
                    .multiply(BigDecimal.ONE.add(sizeRatio))
                    .multiply(BigDecimal.ONE.add(vol));

            if (estimatedSlippage.compareTo(BigDecimal.ZERO) <= 0) {
                estimatedSlippage = new BigDecimal("0.0001");
            }

            // Score = Effective Risk / Estimated Slippage
            BigDecimal score = effectiveRisk.divide(estimatedSlippage, 4, RoundingMode.HALF_UP);

            if (score.compareTo(highestScore) > 0) {
                highestScore = score;
                bestTarget = pos;
            }
        }

        return bestTarget;
    }
}
