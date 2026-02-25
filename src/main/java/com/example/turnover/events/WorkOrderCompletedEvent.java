package com.example.turnover.events;

import com.example.turnover.model.enums.WorkOrderType;

import java.util.UUID;

/**
 * Simulates a Kafka message on topic: workorder.completed
 *
 * In a real system this would be a Kafka record published by WorkOrderService
 * and consumed by TurnoverService (possibly in a separate microservice).
 * Spring's ApplicationEventPublisher gives us the same decoupling within a single JVM.
 */
public class WorkOrderCompletedEvent {

    private final UUID turnoverId;
    private final String workOrderId;
    private final WorkOrderType type;

    public WorkOrderCompletedEvent(UUID turnoverId, String workOrderId, WorkOrderType type) {
        this.turnoverId = turnoverId;
        this.workOrderId = workOrderId;
        this.type = type;
    }

    public UUID getTurnoverId() {
        return turnoverId;
    }

    public String getWorkOrderId() {
        return workOrderId;
    }

    public WorkOrderType getType() {
        return type;
    }
}
