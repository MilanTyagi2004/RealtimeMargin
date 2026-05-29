package com.milan.liquidation_engine.entity;

import jakarta.persistence.*;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;

@Entity
@Table(name = "positions")
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String instrument;

    private String direction;

    private Double quantity;

    private Double entryPrice;

    private Double markPrice;

    private Double pnl;

    private Double marginUsed;

    private Double liquidationPrice;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
}