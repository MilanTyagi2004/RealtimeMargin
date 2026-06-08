package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskThresholdService {

    private final MarginService marginService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    // Configurable factors (could be externalized, but setting defaults)
    private static final BigDecimal MARGIN_CALL_FACTOR = new BigDecimal("1.20");
    private static final BigDecimal EMERGENCY_FACTOR = new BigDecimal("0.80");

    public enum RiskState {
        SAFE,
        MARGIN_CALL,
        LIQUIDATION,
        EMERGENCY
    }

    public RiskState evaluateAccountRisk(User user) {
        BigDecimal equity = marginService.calculateAccountEquity(user);
        BigDecimal mm = marginService.calculateAccountMaintenanceMargin(user);

        if (mm.compareTo(BigDecimal.ZERO) == 0) {
            if (user.isTradingRestricted()) {
                user.setTradingRestricted(false);
                userRepository.save(user);
            }
            return RiskState.SAFE;
        }

        BigDecimal marginCallThreshold = mm.multiply(MARGIN_CALL_FACTOR);
        BigDecimal emergencyThreshold = mm.multiply(EMERGENCY_FACTOR);

        if (equity.compareTo(emergencyThreshold) <= 0) {
            restrictTradingIfNeeded(user, "Emergency Threshold Violated. Equity=" + equity + ", EmergencyThreshold=" + emergencyThreshold);
            return RiskState.EMERGENCY;
        } else if (equity.compareTo(mm) <= 0) {
            restrictTradingIfNeeded(user, "Liquidation Threshold Violated. Equity=" + equity + ", MaintenanceMargin=" + mm);
            return RiskState.LIQUIDATION;
        } else if (equity.compareTo(marginCallThreshold) < 0) {
            restrictTradingIfNeeded(user, "Margin Call Threshold Violated. Equity=" + equity + ", MarginCallThreshold=" + marginCallThreshold);
            return RiskState.MARGIN_CALL;
        } else {
            if (user.isTradingRestricted()) {
                user.setTradingRestricted(false);
                userRepository.save(user);
                auditLogService.logEvent(user.getId(), "MARGIN_SAFE", "SUCCESS", "Account returned to safe state. Equity=" + equity + ", MM=" + mm);
            }
            return RiskState.SAFE;
        }
    }

    private void restrictTradingIfNeeded(User user, String reason) {
        if (!user.isTradingRestricted()) {
            user.setTradingRestricted(true);
            userRepository.save(user);
            auditLogService.logEvent(user.getId(), "TRADING_RESTRICTED", "WARNING", reason);
            log.warn("Trading restricted for user {}: {}", user.getId(), reason);
        }
    }
}
