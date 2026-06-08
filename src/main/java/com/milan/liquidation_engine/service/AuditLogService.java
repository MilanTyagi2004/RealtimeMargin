package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.AuditLog;
import com.milan.liquidation_engine.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEvent(Long userId, String event, String status, String details) {
        AuditLog log = AuditLog.builder()
                .userId(userId)
                .event(event)
                .status(status)
                .details(details)
                .createdAt(LocalDateTime.now())
                .build();
        auditLogRepository.save(log);
    }
}
