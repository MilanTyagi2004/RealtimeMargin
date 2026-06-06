package com.milan.liquidation_engine.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class DepositRequest {
    private Long userId;
    private BigDecimal amount;
}
