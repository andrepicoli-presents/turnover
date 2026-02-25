package com.example.turnover.service;

import com.example.turnover.events.WorkOrderCompletedEvent;
import com.example.turnover.model.entity.WorkOrder;
import com.example.turnover.model.enums.WorkOrderStatus;
import com.example.turnover.repository.WorkOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

    private final WorkOrderRepository repository;
    private final ApplicationEventPublisher publisher;

    public WorkOrderService(WorkOrderRepository repository, ApplicationEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    /**
     * Marks a work order as completed and publishes an event.
     * Kafka analogy: producer publishes to topic "workorder.completed"
     *
     * WorkOrderService only knows about work orders — it has no direct dependency
     * on TurnoverService. The event bus (Kafka) decouples the two services completely.
     */
    public WorkOrder complete(UUID id) {
        WorkOrder wo = repository.findById(id).orElseThrow();
        wo.setStatus(WorkOrderStatus.COMPLETED);
        wo.setCompletedAt(LocalDateTime.now());
        repository.save(wo);

        log.info("[EVENT → workorder.completed] type={} workOrderId={} turnoverId={}", wo.getType(), wo.getId(), wo.getTurnoverId());
        publisher.publishEvent(new WorkOrderCompletedEvent(wo.getTurnoverId(), wo.getId(), wo.getType()));

        return wo;
    }
}
