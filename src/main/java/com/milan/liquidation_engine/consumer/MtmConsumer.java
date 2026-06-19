package com.milan.liquidation_engine.consumer;

import com.milan.liquidation_engine.dto.PriceUpdateRequest;
import com.milan.liquidation_engine.service.MtmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MtmConsumer {

    private final MtmService mtmService;

    @KafkaListener(topics = {"market-data", "market-data-backup"}, groupId = "liquidation-engine-group")
    public void consume(PriceUpdateRequest request, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.info("Consumed PriceUpdateRequest from Kafka topic {}: {}", topic, request);
        try {
            if (request.getInstrument() != null && request.getMarkPrice() != null) {
                String feedSource = "market-data-backup".equals(topic) ? "BACKUP" : "PRIMARY";
                mtmService.updateMarkPrice(
                        request.getInstrument(),
                        request.getMarkPrice(),
                        request.getEventId(),
                        request.getSequenceNumber(),
                        request.getTimestamp(),
                        feedSource
                );
            } else {
                log.warn("Invalid PriceUpdateRequest received: {}", request);
            }
        } catch (Exception e) {
            log.error("Failed to process MTM price update from Kafka: {}", e.getMessage(), e);
        }
    }
}
