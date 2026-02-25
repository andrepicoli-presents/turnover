package com.example.turnover.service;

import com.example.turnover.events.TenantMovedOutEvent;
import com.example.turnover.events.TurnoverReadyForMoveInEvent;
import com.example.turnover.events.WorkOrderCompletedEvent;
import com.example.turnover.model.entity.Turnover;
import com.example.turnover.model.entity.WorkOrder;
import com.example.turnover.model.enums.TurnoverStatus;
import com.example.turnover.model.enums.WorkOrderStatus;
import com.example.turnover.model.enums.WorkOrderType;
import com.example.turnover.repository.TurnoverRepository;
import com.example.turnover.repository.WorkOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TurnoverService {

    private static final Logger log = LoggerFactory.getLogger(TurnoverService.class);

    private final TurnoverRepository turnoverRepository;
    private final WorkOrderRepository workOrderRepository;
    private final ApplicationEventPublisher publisher;

    public TurnoverService(TurnoverRepository turnoverRepository,
                           WorkOrderRepository workOrderRepository,
                           ApplicationEventPublisher publisher) {
        this.turnoverRepository = turnoverRepository;
        this.workOrderRepository = workOrderRepository;
        this.publisher = publisher;
    }

    /**
     * Entry point: tenant has vacated the property.
     * Kafka analogy: producer publishes to topic "tenant.moved-out"
     */
    public Turnover handleMoveOut(String propertyId) {
        if (turnoverRepository.findByPropertyIdAndStatus(propertyId, TurnoverStatus.IN_PROGRESS).isPresent()) {
            log.warn("Turnover already in progress for property {}", propertyId);
            return turnoverRepository.findByPropertyIdAndStatus(propertyId, TurnoverStatus.IN_PROGRESS).get();
        }

        Turnover turnover = new Turnover();
        turnover.setPropertyId(propertyId);
        turnover.setStartedAt(LocalDateTime.now());
        turnover.setStatus(TurnoverStatus.IN_PROGRESS);
        turnoverRepository.save(turnover);

        log.info("[EVENT → tenant.moved-out] property={}", propertyId);
        publisher.publishEvent(new TenantMovedOutEvent(propertyId));

        return turnover;
    }

    /**
     * Kafka analogy: consumer on topic "tenant.moved-out"
     *
     * Only INSPECTION is created here — it is the critical-path gate.
     * CLEANING and REPAIR cannot begin until the inspection report is available.
     * This is the main bottleneck in an unoptimised process: everything waits on inspection.
     */
    @EventListener
    public void onTenantMovedOut(TenantMovedOutEvent event) {
        Turnover turnover = turnoverRepository
                .findByPropertyIdAndStatus(event.getPropertyId(), TurnoverStatus.IN_PROGRESS)
                .orElseThrow();

        log.info("[CONSUMER ← tenant.moved-out] Creating INSPECTION work order for turnover={}", turnover.getId());
        createWorkOrder(turnover, WorkOrderType.INSPECTION);
    }

    /**
     * Kafka analogy: consumer on topic "workorder.completed"
     *
     * Implements the dependency chain:
     *   INSPECTION done  →  unlock CLEANING + REPAIR in parallel  (removes the sequential bottleneck)
     *   CLEANING done    →  check completion
     *   REPAIR done      →  check completion
     *
     * In Kafka this fan-out would be two independent consumer groups each receiving the same event,
     * allowing CLEANING and REPAIR to be dispatched concurrently to different vendor queues.
     */
    @EventListener
    public void onWorkOrderCompleted(WorkOrderCompletedEvent event) {
        if (event.getType() == WorkOrderType.INSPECTION) {
            Turnover turnover = turnoverRepository.findById(event.getTurnoverId()).orElseThrow();
            log.info("[CONSUMER ← workorder.completed] INSPECTION done — unlocking CLEANING + REPAIR in parallel for turnover={}", event.getTurnoverId());
            createWorkOrder(turnover, WorkOrderType.CLEANING);
            createWorkOrder(turnover, WorkOrderType.REPAIR);
        }

        checkTurnoverCompletion(event.getTurnoverId());
    }

    private void checkTurnoverCompletion(java.util.UUID turnoverId) {
        List<WorkOrder> orders = workOrderRepository.findByTurnoverId(turnoverId);
        boolean allDone = orders.stream().allMatch(o -> o.getStatus() == WorkOrderStatus.COMPLETED);

        if (allDone && orders.size() == WorkOrderType.values().length) {
            Turnover turnover = turnoverRepository.findById(turnoverId).orElseThrow();
            turnover.setStatus(TurnoverStatus.COMPLETED);
            turnover.setCompletedAt(LocalDateTime.now());
            turnoverRepository.save(turnover);

            long cycleHours = Duration.between(turnover.getStartedAt(), turnover.getCompletedAt()).toHours();
            log.info("[EVENT → property.ready-for-move-in] property={} cycleTime={}h", turnover.getPropertyId(), cycleHours);
            publisher.publishEvent(new TurnoverReadyForMoveInEvent(turnover.getPropertyId(), turnoverId, cycleHours));
        }
    }

    /**
     * Kafka analogy: consumer on topic "property.ready-for-move-in"
     *
     * In a real system a separate Listing Service microservice would receive this event
     * and immediately re-activate the property listing to minimise vacancy days.
     */
    @EventListener
    public void onTurnoverReadyForMoveIn(TurnoverReadyForMoveInEvent event) {
        log.info("[CONSUMER ← property.ready-for-move-in] Property {} is READY — cycle time: {}h (KPI target: ≤36h)",
                event.getPropertyId(), event.getCycleTimeHours());
    }

    private void createWorkOrder(Turnover turnover, WorkOrderType type) {
        WorkOrder wo = new WorkOrder();
        wo.setTurnoverId(turnover.getId());
        wo.setType(type);
        wo.setStatus(WorkOrderStatus.PENDING);
        wo.setStartedAt(LocalDateTime.now());
        wo.setSlaDeadline(LocalDateTime.now().plusHours(type.getSlaHours()));
        workOrderRepository.save(wo);
        log.info("[WORK ORDER CREATED] type={} slaDeadline={}h turnoverId={}", type, type.getSlaHours(), turnover.getId());
    }
}
