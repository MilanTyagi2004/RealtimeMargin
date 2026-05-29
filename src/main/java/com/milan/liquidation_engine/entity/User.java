package com.milan.liquidation_engine.entity;

import java.util.List;
import jakarta.persistence.OneToMany;
import jakarta.persistence.*;
import java.time.LocalDateTime;


@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;

    private String password;

    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "user")
    private List<Position> positions;

    @OneToMany(mappedBy = "user")
    private List<LiquidationEvent> liquidationEvents;

}
