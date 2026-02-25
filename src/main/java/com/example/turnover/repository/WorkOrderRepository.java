package com.example.turnover.repository;

import com.example.turnover.model.entity.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID> {
    List<WorkOrder> findByTurnoverId(UUID turnoverId);
}