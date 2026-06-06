package com.milan.liquidation_engine.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "positions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String instrument;

    private String direction; // "LONG" or "SHORT"

    @Column(precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(precision = 18, scale = 4)
    private BigDecimal entryPrice;

    @Column(precision = 18, scale = 4)
    private BigDecimal markPrice;

    @Column(precision = 18, scale = 4)
    private BigDecimal pnl;

    @Column(precision = 18, scale = 4)
    private BigDecimal marginUsed;

    @Column(precision = 18, scale = 4)
    private BigDecimal liquidationPrice;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
}