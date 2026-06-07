package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.InstrumentConfig;
import com.milan.liquidation_engine.entity.Position;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.InstrumentConfigRepository;
import com.milan.liquidation_engine.repository.PositionRepository;
import com.milan.liquidation_engine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PositionService {

    private final UserRepository userRepository;
    private final PositionRepository positionRepository;
    private final InstrumentConfigRepository instrumentConfigRepository;
    private final MarginService marginService;
    private final RiskThresholdService riskThresholdService;
    private final AuditLogService auditLogService;

    private static final BigDecimal MAX_SLIPPAGE_RATE = new BigDecimal("0.01"); // 1%

    @Transactional
    public Position processOrder(Long userId, String instrument, String direction, BigDecimal quantity,
                                 BigDecimal proposedPrice, String clientOrderId) {

        // Idempotency check
        if (clientOrderId != null) {
            // Check if clientOrderId has already been processed
            // Simulating by logging and checking via audit logs (if we want, we can check or just record)
            auditLogService.logEvent(userId, "ORDER_RECEIVED", "RECEIVED", "ClientOrderId: " + clientOrderId);
        }

        // Concurrency lock: Pessimistic write lock on user record
        User user = userRepository.findAndLockById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // Get instrument config
        InstrumentConfig config = instrumentConfigRepository.findByInstrument(instrument)
                .orElseThrow(() -> new IllegalArgumentException("Instrument not configured: " + instrument));

        // 1. Trading Restriction Check (Margin Call block)
        if (user.isTradingRestricted()) {
            // We only allow reducing positions if restricted, not opening or increasing
            // Let's determine if this order reduces risk
            boolean isRiskReducing = isRiskReducingOrder(user, instrument, direction, quantity);
            if (!isRiskReducing) {
                auditLogService.logEvent(userId, "ORDER_REJECTED", "REJECTED", "Trading restricted due to margin warning.");
                throw new IllegalStateException("Trading restricted. Only risk-reducing orders (closing/reducing positions) are allowed.");
            }
        }

        // 2. Execution Safety - Liquidity Check
        if (quantity.compareTo(config.getLiquidityLimit()) > 0) {
            auditLogService.logEvent(userId, "ORDER_REJECTED", "REJECTED", "Order quantity " + quantity + " exceeds liquidity limit " + config.getLiquidityLimit());
            throw new IllegalArgumentException("Order quantity exceeds available liquidity limit of " + config.getLiquidityLimit());
        }

        // 3. Execution Safety - Slippage Check
        // Set a mark price for simulation (if no positions, we set it to proposedPrice, else use existing mark price)
        BigDecimal currentMarkPrice = proposedPrice; // fallback
        Optional<Position> existingPosOpt = positionRepository.findByUserAndInstrument(user, instrument);
        if (existingPosOpt.isPresent()) {
            currentMarkPrice = existingPosOpt.get().getMarkPrice();
        }

        BigDecimal priceDiff = proposedPrice.subtract(currentMarkPrice).abs();
        BigDecimal slippageRate = priceDiff.divide(currentMarkPrice, 8, RoundingMode.HALF_UP);
        if (slippageRate.compareTo(MAX_SLIPPAGE_RATE) > 0) {
            auditLogService.logEvent(userId, "ORDER_REJECTED", "REJECTED", "Slippage check failed. Rate: " + slippageRate + " > limit " + MAX_SLIPPAGE_RATE);
            throw new IllegalArgumentException("Execution price slippage too high. Limit: 1%");
        }

        // 4. Update or Create Position
        Position finalPosition = null;
        if (existingPosOpt.isPresent()) {
            Position existing = existingPosOpt.get();
            if (existing.getDirection().equalsIgnoreCase(direction)) {
                // Increase position size: average up/down entry price
                BigDecimal newQty = existing.getQuantity().add(quantity);
                BigDecimal existingCost = existing.getQuantity().multiply(existing.getEntryPrice());
                BigDecimal newCost = quantity.multiply(proposedPrice);
                BigDecimal avgEntryPrice = existingCost.add(newCost).divide(newQty, 8, RoundingMode.HALF_UP);

                existing.setQuantity(newQty);
                existing.setEntryPrice(avgEntryPrice);
                existing.setMarkPrice(proposedPrice); // update mark price to execution price
                finalPosition = positionRepository.save(existing);
            } else {
                // Opposite direction: Reduce, close, or reverse position
                BigDecimal existingQty = existing.getQuantity();
                BigDecimal realizedPnL;
                if (existing.getDirection().equalsIgnoreCase("LONG")) {
                    realizedPnL = quantity.min(existingQty).multiply(proposedPrice.subtract(existing.getEntryPrice()));
                } else {
                    realizedPnL = quantity.min(existingQty).multiply(existing.getEntryPrice().subtract(proposedPrice));
                }

                user.setBalance(user.getBalance().add(realizedPnL));

                if (quantity.compareTo(existingQty) < 0) {
                    // Partial close
                    existing.setQuantity(existingQty.subtract(quantity));
                    existing.setMarkPrice(proposedPrice);
                    finalPosition = positionRepository.save(existing);
                    auditLogService.logEvent(userId, "PARTIAL_CLOSE", "SUCCESS", "Closed " + quantity + " " + instrument + " realized PnL: " + realizedPnL);
                } else if (quantity.compareTo(existingQty) == 0) {
                    // Full close
                    positionRepository.delete(existing);
                    auditLogService.logEvent(userId, "FULL_CLOSE", "SUCCESS", "Fully closed " + instrument + " realized PnL: " + realizedPnL);
                } else {
                    // Reverse position
                    BigDecimal remainingQty = quantity.subtract(existingQty);
                    positionRepository.delete(existing);
                    auditLogService.logEvent(userId, "FULL_CLOSE", "SUCCESS", "Fully closed " + instrument + " realized PnL: " + realizedPnL);

                    Position reversePos = Position.builder()
                            .user(user)
                            .instrument(instrument)
                            .direction(direction)
                            .quantity(remainingQty)
                            .entryPrice(proposedPrice)
                            .markPrice(proposedPrice)
                            .pnl(BigDecimal.ZERO)
                            .marginUsed(BigDecimal.ZERO)
                            .liquidationPrice(BigDecimal.ZERO)
                            .build();
                    finalPosition = positionRepository.save(reversePos);
                    auditLogService.logEvent(userId, "POSITION_REVERSED", "SUCCESS", "Reversed position to " + direction + " with qty " + remainingQty);
                }
            }
        } else {
            // New Position
            Position newPos = Position.builder()
                    .user(user)
                    .instrument(instrument)
                    .direction(direction)
                    .quantity(quantity)
                    .entryPrice(proposedPrice)
                    .markPrice(proposedPrice)
                    .pnl(BigDecimal.ZERO)
                    .marginUsed(BigDecimal.ZERO)
                    .liquidationPrice(BigDecimal.ZERO)
                    .build();
            finalPosition = positionRepository.save(newPos);
            auditLogService.logEvent(userId, "POSITION_OPENED", "SUCCESS", "Opened " + direction + " " + quantity + " " + instrument + " at " + proposedPrice);
        }

        // 5. Margin Validation
        marginService.recalculateUserPositionsAndMargin(user);
        BigDecimal availableMargin = marginService.calculateAccountAvailableMargin(user);

        if (availableMargin.compareTo(BigDecimal.ZERO) < 0) {
            // Rollback! Margin requirement violated.
            auditLogService.logEvent(userId, "ORDER_REJECTED", "REJECTED", "Insufficient margin. Available margin would be: " + availableMargin);
            throw new IllegalStateException("Insufficient margin to complete this trade. Available margin: " + availableMargin);
        }

        // 6. Evaluate Risk State
        riskThresholdService.evaluateAccountRisk(user);
        userRepository.save(user);

        auditLogService.logEvent(userId, "ORDER_PROCESSED", "SUCCESS", "Order ClientOrderId: " + clientOrderId + " executed successfully.");
        return finalPosition;
    }

    private boolean isRiskReducingOrder(User user, String instrument, String direction, BigDecimal quantity) {
        Optional<Position> existingOpt = positionRepository.findByUserAndInstrument(user, instrument);
        if (existingOpt.isEmpty()) {
            return false;
        }
        Position existing = existingOpt.get();
        // If order direction is opposite to existing position, it's risk-reducing (reduces position size)
        return !existing.getDirection().equalsIgnoreCase(direction);
    }
}
