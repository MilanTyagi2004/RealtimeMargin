package com.milan.liquidation_engine.consumer;

import com.milan.liquidation_engine.dto.PriceUpdateRequest;
import com.milan.liquidation_engine.service.MtmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class MtmConsumer {

    private final MtmService mtmService;

    @KafkaListener(topics = "market-data", groupId = "liquidation-engine-group")
    public void consume(PriceUpdateRequest request) {
        log.info("Consumed PriceUpdateRequest from Kafka: {}", request);
        try {
            if (request.getInstrument() != null && request.getMarkPrice() != null) {
                mtmService.updateMarkPrice(request.getInstrument(), request.getMarkPrice(), request.getEventId());
            } else {
                log.warn("Invalid PriceUpdateRequest received: {}", request);
            }
        } catch (Exception e) {
            log.error("Failed to process MTM price update from Kafka: {}", e.getMessage(), e);
        }
    }
}
