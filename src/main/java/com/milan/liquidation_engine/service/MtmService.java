package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.Position;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.PositionRepository;
import com.milan.liquidation_engine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

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

    @Transactional
    public void updateMarkPrice(String instrument, BigDecimal newMarkPrice) {
        log.info("MTM price update received for instrument: {}, price: {}", instrument, newMarkPrice);

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
                    liquidationService.liquidateAccount(lockedUser.getId(), state);
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
