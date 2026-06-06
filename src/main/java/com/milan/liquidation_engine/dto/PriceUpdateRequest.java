package com.milan.liquidation_engine.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class PriceUpdateRequest {
    private String instrument;
    private BigDecimal markPrice;
}
