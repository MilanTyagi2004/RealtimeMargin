package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.Position;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.PositionRepository;
import com.milan.liquidation_engine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import com.milan.liquidation_engine.repository.InstrumentConfigRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class MtmService {

    private final PositionRepository positionRepository;
    private final UserRepository userRepository;
    private final MarginService marginService;
    private final RiskThresholdService riskThresholdService;
    private final LiquidationService liquidationService;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate redisTemplate;
    private final InstrumentConfigRepository instrumentConfigRepository;

    @Transactional
    public void updateMarkPrice(String instrument, BigDecimal newMarkPrice) {
        updateMarkPrice(instrument, newMarkPrice, null);
    }

    @Transactional
    public void updateMarkPrice(String instrument, BigDecimal newMarkPrice, String eventId) {
        log.info("MTM price update received for instrument: {}, price: {}, eventId: {}", instrument, newMarkPrice, eventId);

        // Fetch old mark price to calculate return percentage and dynamic volatility
        String oldPriceStr = null;
        try {
            oldPriceStr = redisTemplate.opsForValue().get("markPrice:" + instrument);
        } catch (RuntimeException e) {
            log.warn("Failed to fetch old mark price from Redis: {}", e.getMessage());
        }

        if (oldPriceStr != null) {
            try {
                BigDecimal oldPrice = new BigDecimal(oldPriceStr);
                if (oldPrice.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal priceDiff = newMarkPrice.subtract(oldPrice).abs();
                    BigDecimal returnPct = priceDiff.divide(oldPrice, 8, RoundingMode.HALF_UP);

                    BigDecimal baseVol = instrumentConfigRepository.findByInstrument(instrument)
                            .map(config -> config.getVolatility())
                            .orElse(new BigDecimal("0.05"));

                    BigDecimal oldVol = baseVol;
                    String oldVolStr = redisTemplate.opsForValue().get("dynamicVolatility:" + instrument);
                    if (oldVolStr != null) {
                        oldVol = new BigDecimal(oldVolStr);
                    }

                    BigDecimal decayFactor = new BigDecimal("0.90");
                    BigDecimal shockMultiplier = new BigDecimal("1.5");
                    BigDecimal volNew = oldVol.multiply(decayFactor).add(returnPct.multiply(shockMultiplier));

                    BigDecimal maxCap = new BigDecimal("0.50");
                    if (volNew.compareTo(baseVol) < 0) {
                        volNew = baseVol;
                    }
                    if (volNew.compareTo(maxCap) > 0) {
                        volNew = maxCap;
                    }

                    redisTemplate.opsForValue().set("dynamicVolatility:" + instrument, volNew.toString());
                    log.info("Updated dynamic volatility for {}: {}", instrument, volNew);
                }
            } catch (Exception e) {
                log.warn("Failed to process dynamic volatility update: {}", e.getMessage());
            }
        }

        // Cache the latest mark price in Redis for fast global access
        try {
            redisTemplate.opsForValue().set("markPrice:" + instrument, newMarkPrice.toString());
        } catch (RuntimeException e) {
            log.warn("Failed to cache mark price in Redis for {}: {}", instrument, e.getMessage());
        }

        List<Position> positions = positionRepository.findByInstrument(instrument);
        if (positions.isEmpty()) {
            return;
        }

        for (Position position : positions) {
            User user = position.getUser();
            
            // Concurrency: lock the user record to prevent race conditions during updates
            User lockedUser = userRepository.findAndLockById(user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + user.getId()));

            // Update mark price of target position
            position.setMarkPrice(newMarkPrice);
            positionRepository.save(position);

            // Recalculate margins and PnL
            marginService.recalculateUserPositionsAndMargin(lockedUser);

            // Evaluate risk state
            RiskThresholdService.RiskState state = riskThresholdService.evaluateAccountRisk(lockedUser);
            
            if (state == RiskThresholdService.RiskState.LIQUIDATION || state == RiskThresholdService.RiskState.EMERGENCY) {
                log.warn("Account {} is in {} state. Triggering liquidation.", lockedUser.getId(), state);
                try {
                    liquidationService.liquidateAccount(lockedUser.getId(), state, eventId);
                } catch (Exception e) {
                    log.error("Error liquidating account {}: {}", lockedUser.getId(), e.getMessage(), e);
                    auditLogService.logEvent(lockedUser.getId(), "LIQUIDATION_FAILED", "ERROR", e.getMessage());
                }
            } else {
                userRepository.save(lockedUser);
            }
        }
    }
}
