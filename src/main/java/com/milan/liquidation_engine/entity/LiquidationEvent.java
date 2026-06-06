package com.milan.liquidation_engine.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "liquidation_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiquidationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String instrument;

    private String action; // e.g. "PARTIAL", "FULL"

    @Column(precision = 18, scale = 4)
    private BigDecimal quantityLiquidated;

    @Column(precision = 18, scale = 4)
    private BigDecimal price;

    @Column(precision = 18, scale = 4)
    private BigDecimal slippage;

    @Column(precision = 18, scale = 4)
    private BigDecimal penalty;

    @Column(length = 1000)
    private String marginSnapshot;

    private String reason;

    private LocalDateTime timestamp;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
}