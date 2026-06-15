package com.milan.liquidation_engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiquidationBroadcastEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String instrument;
    private String action;
    private BigDecimal quantityLiquidated;
    private BigDecimal price;
    private BigDecimal penalty;
    private String reason;
    private LocalDateTime timestamp;
}
