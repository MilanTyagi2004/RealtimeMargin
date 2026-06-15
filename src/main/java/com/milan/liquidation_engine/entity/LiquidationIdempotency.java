package com.milan.liquidation_engine.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "liquidation_idempotencies", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"eventId", "userId"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiquidationIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false)
    private Long userId;

    private LocalDateTime processedAt;
}
