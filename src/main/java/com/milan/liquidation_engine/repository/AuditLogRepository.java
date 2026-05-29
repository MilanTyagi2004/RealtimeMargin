package com.milan.liquidation_engine.repository;

import com.milan.liquidation_engine.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

}