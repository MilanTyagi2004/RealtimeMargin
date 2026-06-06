package com.milan.liquidation_engine.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderRequest {
    private Long userId;
    private String instrument;
    private String direction; // "LONG" or "SHORT"
    private BigDecimal quantity;
    private BigDecimal price;
    private String clientOrderId;
}
