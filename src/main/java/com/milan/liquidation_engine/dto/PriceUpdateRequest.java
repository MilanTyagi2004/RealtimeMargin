package com.milan.liquidation_engine.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class PriceUpdateRequest {
    private String eventId;
    private String instrument;
    private BigDecimal markPrice;
    private Long sequenceNumber;
    private Long timestamp;
}
