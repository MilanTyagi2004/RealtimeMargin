package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.dto.AuditLogEvent;
import com.milan.liquidation_engine.entity.AuditLog;
import com.milan.liquidation_engine.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logEvent(Long userId, String event, String status, String details) {
        AuditLogEvent eventDto = AuditLogEvent.builder()
                .userId(userId)
                .event(event)
                .status(status)
                .details(details)
                .build();

        try {
            kafkaTemplate.send("audit-logs", eventDto);
            log.debug("Sent AuditLogEvent to Kafka: {}", eventDto);
        } catch (Exception e) {
            log.warn("Kafka is unavailable. Falling back to synchronous database write for audit log. Error: {}", e.getMessage());
            try {
                AuditLog logEntity = AuditLog.builder()
                        .userId(userId)
                        .event(event)
                        .status(status)
                        .details(details)
                        .createdAt(LocalDateTime.now())
                        .build();
                auditLogRepository.save(logEntity);
            } catch (Exception ex) {
                log.error("Failsafe database write for audit log failed: {}", ex.getMessage(), ex);
            }
        }
    }
}
