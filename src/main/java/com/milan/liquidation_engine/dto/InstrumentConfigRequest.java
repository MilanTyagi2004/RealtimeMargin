package com.milan.liquidation_engine.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class InstrumentConfigRequest {
    private String instrument;
    private BigDecimal initialMarginRate;
    private BigDecimal maintenanceMarginRate;
    private BigDecimal liquidationPenaltyRate;
    private BigDecimal liquidityLimit;
    private BigDecimal volatility;
}
