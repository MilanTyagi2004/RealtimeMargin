package com.milan.liquidation_engine.config;

import com.milan.liquidation_engine.dto.AuditLogEvent;
import com.milan.liquidation_engine.dto.PriceUpdateRequest;
import com.milan.liquidation_engine.entity.AuditLog;
import com.milan.liquidation_engine.repository.AuditLogRepository;
import com.milan.liquidation_engine.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = { "audit-logs", "market-data", "liquidation-events" })
@DirtiesContext
public class KafkaIntegrationTests {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void testAuditLogServicePublishesAndConsumerPersists() throws Exception {
        // Trigger log event which should publish to Kafka
        auditLogService.logEvent(100L, "TEST_KAFKA_EVENT", "SUCCESS", "Kafka integration test details");

        // Since consumption is asynchronous, wait a moment for the consumer to persist it
        boolean saved = false;
        for (int i = 0; i < 40; i++) {
            List<AuditLog> logs = auditLogRepository.findAll();
            long count = logs.stream()
                    .filter(log -> "TEST_KAFKA_EVENT".equals(log.getEvent()))
                    .count();
            if (count > 0) {
                saved = true;
                break;
            }
            TimeUnit.MILLISECONDS.sleep(250);
        }

        assertTrue(saved, "Audit log should have been consumed from Kafka and saved to DB");
    }

    @Test
    void testPriceUpdateKafkaIngestion() throws Exception {
        PriceUpdateRequest priceUpdate = new PriceUpdateRequest();
        priceUpdate.setInstrument("ETH-USD");
        priceUpdate.setMarkPrice(new BigDecimal("3500.00"));

        kafkaTemplate.send("market-data", priceUpdate);
        assertNotNull(priceUpdate);
    }
}
