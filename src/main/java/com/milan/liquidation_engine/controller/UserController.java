package com.milan.liquidation_engine.controller;

import com.milan.liquidation_engine.dto.DepositRequest;
import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.UserRepository;
import com.milan.liquidation_engine.service.AuditLogService;
import com.milan.liquidation_engine.service.MarginService;
import com.milan.liquidation_engine.service.RiskThresholdService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final MarginService marginService;
    private final RiskThresholdService riskThresholdService;
    private final AuditLogService auditLogService;

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody User user) {
        if (user.getBalance() == null) {
            user.setBalance(BigDecimal.ZERO);
        }
        user.setCreatedAt(LocalDateTime.now());
        user.setTradingRestricted(false);
        User saved = userRepository.save(user);
        auditLogService.logEvent(saved.getId(), "USER_CREATED", "SUCCESS", "User created with username: " + saved.getUsername());
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/deposit")
    @Transactional
    public ResponseEntity<User> deposit(@RequestBody DepositRequest request) {
        // Concurrency Lock user
        User user = userRepository.findAndLockById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + request.getUserId()));

        BigDecimal amount = request.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive.");
        }

        user.setBalance(user.getBalance().add(amount));
        
        // Recalculate and evaluate risk status (might lift trading restrictions if deposit makes account safe)
        marginService.recalculateUserPositionsAndMargin(user);
        riskThresholdService.evaluateAccountRisk(user);

        User saved = userRepository.save(user);
        auditLogService.logEvent(saved.getId(), "DEPOSIT", "SUCCESS", "Deposited " + amount + ". New balance: " + saved.getBalance());

        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}/margin")
    public ResponseEntity<Map<String, Object>> getMarginStatus(@PathVariable Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        BigDecimal equity = marginService.calculateAccountEquity(user);
        BigDecimal im = marginService.calculateAccountInitialMargin(user);
        BigDecimal mm = marginService.calculateAccountMaintenanceMargin(user);
        BigDecimal available = marginService.calculateAccountAvailableMargin(user);
        RiskThresholdService.RiskState state = riskThresholdService.evaluateAccountRisk(user);

        Map<String, Object> status = new HashMap<>();
        status.put("userId", user.getId());
        status.put("username", user.getUsername());
        status.put("balance", user.getBalance());
        status.put("equity", equity);
        status.put("initialMargin", im);
        status.put("maintenanceMargin", mm);
        status.put("availableMargin", available);
        status.put("riskState", state.toString());
        status.put("tradingRestricted", user.isTradingRestricted());

        return ResponseEntity.ok(status);
    }
}
