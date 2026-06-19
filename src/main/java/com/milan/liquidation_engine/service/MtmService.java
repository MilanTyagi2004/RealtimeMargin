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
        updateMarkPrice(instrument, newMarkPrice, null, null, null, "PRIMARY");
    }

    @Transactional
    public void updateMarkPrice(String instrument, BigDecimal newMarkPrice, String eventId) {
        updateMarkPrice(instrument, newMarkPrice, eventId, null, null, "PRIMARY");
    }

    @Transactional
    public void updateMarkPrice(String instrument, BigDecimal newMarkPrice, String eventId, Long sequenceNumber, Long timestamp, String feedSource) {
        log.info("MTM price update received for instrument: {}, price: {}, eventId: {}, seq: {}, ts: {}, feed: {}", 
                instrument, newMarkPrice, eventId, sequenceNumber, timestamp, feedSource);

        // 1. Duplicate market events handling (Deduplication)
        if (eventId != null && !eventId.trim().isEmpty()) {
            String duplicateKey = "priceEventProcessed:" + eventId;
            Boolean isNew = redisTemplate.opsForValue().setIfAbsent(duplicateKey, "PROCESSED", java.time.Duration.ofMinutes(10));
            if (Boolean.FALSE.equals(isNew)) {
                log.warn("Duplicate price event detected for eventId: {}. Skipping MTM update.", eventId);
                return;
            }
        }

        // 2. Exchange Failover Feed check
        String source = feedSource != null ? feedSource.toUpperCase() : "PRIMARY";
        if ("BACKUP".equals(source)) {
            String lastActiveStr = redisTemplate.opsForValue().get("feedLastActive:primary");
            long currentTime = System.currentTimeMillis();
            long lastActive = 0;
            if (lastActiveStr != null) {
                try {
                    lastActive = Long.parseLong(lastActiveStr);
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse last active timestamp for primary feed: {}", lastActiveStr);
                }
            }
            // If primary is active within the last 5 seconds, skip backup feed update
            if (currentTime - lastActive <= 5000) {
                log.info("Primary feed is active (last active: {} ms ago). Skipping backup feed price update for {}.", 
                        (currentTime - lastActive), instrument);
                return;
            } else {
                log.warn("Primary feed inactive (last active: {} ms ago). Accepting backup feed price update for {}.", 
                        (currentTime - lastActive), instrument);
            }
        } else if ("PRIMARY".equals(source)) {
            try {
                redisTemplate.opsForValue().set("feedLastActive:primary", String.valueOf(System.currentTimeMillis()));
            } catch (Exception e) {
                log.warn("Failed to update primary feed active timestamp in Redis: {}", e.getMessage());
            }
        }

        // 3. Out-of-Order / Sequence Validation
        if (sequenceNumber != null) {
            String seqKey = "priceSequence:" + instrument;
            String cachedSeqStr = redisTemplate.opsForValue().get(seqKey);
            if (cachedSeqStr != null) {
                try {
                    long cachedSeq = Long.parseLong(cachedSeqStr);
                    if (sequenceNumber <= cachedSeq) {
                        log.warn("Out-of-order or stale price sequence received for {}. Sequence: {}, Cached Sequence: {}. Skipping.", 
                                instrument, sequenceNumber, cachedSeq);
                        return;
                    }
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse cached sequence number for {}: {}", instrument, cachedSeqStr);
                }
            }
        }

        if (timestamp != null) {
            String tsKey = "priceTimestamp:" + instrument;
            String cachedTsStr = redisTemplate.opsForValue().get(tsKey);
            if (cachedTsStr != null) {
                try {
                    long cachedTs = Long.parseLong(cachedTsStr);
                    if (timestamp <= cachedTs) {
                        log.warn("Out-of-order or stale price timestamp received for {}. Timestamp: {}, Cached Timestamp: {}. Skipping.", 
                                instrument, timestamp, cachedTs);
                        return;
                    }
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse cached timestamp for {}: {}", instrument, cachedTsStr);
                }
            }
        }

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
            // Cache the latest sequence and timestamp in Redis
            if (sequenceNumber != null) {
                redisTemplate.opsForValue().set("priceSequence:" + instrument, sequenceNumber.toString());
            }
            if (timestamp != null) {
                redisTemplate.opsForValue().set("priceTimestamp:" + instrument, timestamp.toString());
            }
        } catch (RuntimeException e) {
            log.warn("Failed to cache mark price / sequence details in Redis for {}: {}", instrument, e.getMessage());
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
