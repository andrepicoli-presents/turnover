package com.example.turnover.model.entity;

import com.example.turnover.model.enums.WorkOrderStatus;
import com.example.turnover.model.enums.WorkOrderType;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID turnoverId;

    @Enumerated(EnumType.STRING)
    private WorkOrderType type;

    @Enumerated(EnumType.STRING)
    private WorkOrderStatus status;

    private LocalDateTime startedAt;
    private LocalDateTime slaDeadline;
    private LocalDateTime completedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTurnoverId() {
        return turnoverId;
    }

    public void setTurnoverId(UUID turnoverId) {
        this.turnoverId = turnoverId;
    }

    public WorkOrderType getType() {
        return type;
    }

    public void setType(WorkOrderType type) {
        this.type = type;
    }

    public WorkOrderStatus getStatus() {
        return status;
    }

    public void setStatus(WorkOrderStatus status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getSlaDeadline() {
        return slaDeadline;
    }

    public void setSlaDeadline(LocalDateTime slaDeadline) {
        this.slaDeadline = slaDeadline;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}