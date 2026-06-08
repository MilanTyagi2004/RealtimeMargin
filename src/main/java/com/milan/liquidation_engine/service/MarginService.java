package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.InstrumentConfig;
import com.milan.liquidation_engine.entity.Position;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.InstrumentConfigRepository;
import com.milan.liquidation_engine.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MarginService {

    private final InstrumentConfigRepository instrumentConfigRepository;
    private final PositionRepository positionRepository;

    private static final int PRECISION = 8;
    private static final int DISPLAY_PRECISION = 4;

    public BigDecimal calculatePositionValue(Position position) {
        if (position.getQuantity() == null || position.getMarkPrice() == null) {
            return BigDecimal.ZERO;
        }
        return position.getQuantity().multiply(position.getMarkPrice());
    }

    public BigDecimal calculatePositionPnL(Position position) {
        if (position.getQuantity() == null || position.getEntryPrice() == null || position.getMarkPrice() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = position.getMarkPrice().subtract(position.getEntryPrice());
        if ("SHORT".equalsIgnoreCase(position.getDirection())) {
            diff = diff.negate();
        }
        return position.getQuantity().multiply(diff);
    }

    public BigDecimal calculateInitialMargin(Position position, InstrumentConfig config) {
        BigDecimal posValue = calculatePositionValue(position);
        // Volatility support: adjusts margin rate based on volatility
        BigDecimal baseRate = config.getInitialMarginRate();
        BigDecimal volatilityAdj = config.getVolatility();
        BigDecimal effectiveRate = baseRate.add(volatilityAdj);
        return posValue.multiply(effectiveRate);
    }

    public BigDecimal calculateMaintenanceMargin(Position position, InstrumentConfig config) {
        BigDecimal posValue = calculatePositionValue(position);
        return posValue.multiply(config.getMaintenanceMarginRate());
    }

    public void recalculateUserPositionsAndMargin(User user) {
        List<Position> positions = positionRepository.findByUser(user);
        BigDecimal totalPnL = BigDecimal.ZERO;
        BigDecimal totalMaintenanceMargin = BigDecimal.ZERO;

        for (Position pos : positions) {
            InstrumentConfig config = instrumentConfigRepository.findByInstrument(pos.getInstrument())
                    .orElseThrow(() -> new IllegalArgumentException("No config found for instrument: " + pos.getInstrument()));

            BigDecimal pnl = calculatePositionPnL(pos);
            BigDecimal value = calculatePositionValue(pos);
            BigDecimal initialMargin = calculateInitialMargin(pos, config);
            BigDecimal maintenanceMargin = calculateMaintenanceMargin(pos, config);

            pos.setPnl(pnl.setScale(DISPLAY_PRECISION, RoundingMode.HALF_UP));
            pos.setMarginUsed(initialMargin.setScale(DISPLAY_PRECISION, RoundingMode.HALF_UP));
            
            totalPnL = totalPnL.add(pnl);
            totalMaintenanceMargin = totalMaintenanceMargin.add(maintenanceMargin);

            // Solve for Liquidation Price
            BigDecimal liqPrice = calculateLiquidationPrice(user, pos, config, positions);
            pos.setLiquidationPrice(liqPrice.setScale(DISPLAY_PRECISION, RoundingMode.HALF_UP));

            positionRepository.save(pos);
        }

        user.setPositions(positions);
    }

    public BigDecimal calculateAccountEquity(User user) {
        BigDecimal balance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal totalPnL = BigDecimal.ZERO;
        List<Position> positions = positionRepository.findByUser(user);
        for (Position pos : positions) {
            totalPnL = totalPnL.add(calculatePositionPnL(pos));
        }
        return balance.add(totalPnL);
    }

    public BigDecimal calculateAccountMaintenanceMargin(User user) {
        BigDecimal totalMM = BigDecimal.ZERO;
        List<Position> positions = positionRepository.findByUser(user);
        for (Position pos : positions) {
            InstrumentConfig config = instrumentConfigRepository.findByInstrument(pos.getInstrument())
                    .orElse(null);
            if (config != null) {
                totalMM = totalMM.add(calculateMaintenanceMargin(pos, config));
            }
        }
        return totalMM;
    }

    public BigDecimal calculateAccountInitialMargin(User user) {
        BigDecimal totalIM = BigDecimal.ZERO;
        List<Position> positions = positionRepository.findByUser(user);
        for (Position pos : positions) {
            InstrumentConfig config = instrumentConfigRepository.findByInstrument(pos.getInstrument())
                    .orElse(null);
            if (config != null) {
                totalIM = totalIM.add(calculateInitialMargin(pos, config));
            }
        }
        return totalIM;
    }

    public BigDecimal calculateAccountAvailableMargin(User user) {
        BigDecimal equity = calculateAccountEquity(user);
        BigDecimal marginUsed = calculateAccountInitialMargin(user);
        return equity.subtract(marginUsed);
    }

    private BigDecimal calculateLiquidationPrice(User user, Position targetPos, InstrumentConfig targetConfig, List<Position> allPositions) {
        if (targetPos.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal balance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal otherPnL = BigDecimal.ZERO;
        BigDecimal otherMM = BigDecimal.ZERO;

        for (Position pos : allPositions) {
            if (pos.getId().equals(targetPos.getId())) {
                continue;
            }
            InstrumentConfig config = instrumentConfigRepository.findByInstrument(pos.getInstrument()).orElse(null);
            if (config != null) {
                otherPnL = otherPnL.add(calculatePositionPnL(pos));
                otherMM = otherMM.add(calculateMaintenanceMargin(pos, config));
            }
        }

        BigDecimal qty = targetPos.getQuantity();
        BigDecimal entryPrice = targetPos.getEntryPrice();
        BigDecimal mmRate = targetConfig.getMaintenanceMarginRate();

        if ("LONG".equalsIgnoreCase(targetPos.getDirection())) {
            // Formula: P_liq = (otherMM + qty * entryPrice - balance - otherPnL) / (qty * (1 - mmRate))
            BigDecimal numerator = otherMM
                    .add(qty.multiply(entryPrice))
                    .subtract(balance)
                    .subtract(otherPnL);
            BigDecimal denominator = qty.multiply(BigDecimal.ONE.subtract(mmRate));
            if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal liqPrice = numerator.divide(denominator, PRECISION, RoundingMode.HALF_UP);
            return liqPrice.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : liqPrice;
        } else {
            // Formula: P_liq = (balance + otherPnL + qty * entryPrice - otherMM) / (qty * (1 + mmRate))
            BigDecimal numerator = balance
                    .add(otherPnL)
                    .add(qty.multiply(entryPrice))
                    .subtract(otherMM);
            BigDecimal denominator = qty.multiply(BigDecimal.ONE.add(mmRate));
            if (denominator.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal liqPrice = numerator.divide(denominator, PRECISION, RoundingMode.HALF_UP);
            return liqPrice.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : liqPrice;
        }
    }
}
