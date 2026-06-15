package com.milan.liquidation_engine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String event;
    private String status;
    private String details;
}
