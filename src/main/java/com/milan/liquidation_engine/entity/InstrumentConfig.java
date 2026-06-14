package com.milan.liquidation_engine.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;

@Entity
@Table(name = "instrument_configs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstrumentConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String instrument;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal initialMarginRate;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal maintenanceMarginRate;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal liquidationPenaltyRate;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal liquidityLimit;

    @Column(precision = 18, scale = 4, nullable = false)
    private BigDecimal volatility; // e.g. 0.05 for 5% volatility scale factor
}
