package com.milan.liquidation_engine.consumer;

import com.milan.liquidation_engine.dto.AuditLogEvent;
import com.milan.liquidation_engine.entity.AuditLog;
import com.milan.liquidation_engine.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class AuditLogConsumer {

    private final AuditLogRepository auditLogRepository;

    @KafkaListener(topics = "audit-logs", groupId = "liquidation-engine-group")
    public void consume(AuditLogEvent event) {
        log.info("Consumed AuditLogEvent from Kafka: {}", event);
        try {
            AuditLog logEntity = AuditLog.builder()
                    .userId(event.getUserId())
                    .event(event.getEvent())
                    .status(event.getStatus())
                    .details(event.getDetails())
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogRepository.save(logEntity);
            log.debug("Successfully saved audit log for user: {}", event.getUserId());
        } catch (Exception e) {
            log.error("Failed to persist consumed AuditLogEvent: {}", e.getMessage(), e);
        }
    }
}
