package com.example.turnover.controller;

import com.example.turnover.model.entity.Turnover;
import com.example.turnover.model.entity.WorkOrder;
import com.example.turnover.model.enums.TurnoverStatus;
import com.example.turnover.model.enums.WorkOrderStatus;
import com.example.turnover.model.enums.WorkOrderType;
import com.example.turnover.repository.TurnoverRepository;
import com.example.turnover.repository.WorkOrderRepository;
import com.example.turnover.service.TurnoverService;
import com.example.turnover.service.WorkOrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API to drive the POC demo.
 *
 * Typical demo flow:
 *   1. POST /turnovers/moveout?propertyId=PROP-LIVE-1          → tenant leaves, INSPECTION work order created
 *   2. GET  /turnovers/{id}/workorders                          → see work orders
 *   3. POST /turnovers/{id}/workorders/{woId}/complete          → complete INSPECTION → CLEANING + REPAIR unlocked
 *   4. POST /turnovers/{id}/workorders/{woId}/complete (x2)    → complete CLEANING and REPAIR
 *   5. GET  /turnovers/{id}/kpi                                 → see full KPI report
 *
 * Shortcut:
 *   POST /turnovers/simulate?scenario=bottleneck   → pre-seeded historical data showing the problem
 *   POST /turnovers/simulate?scenario=optimized    → pre-seeded data showing the improvement
 */
@RestController
@RequestMapping("/turnovers")
public class TurnoverController {

    private final TurnoverService turnoverService;
    private final WorkOrderService workOrderService;
    private final TurnoverRepository turnoverRepository;
    private final WorkOrderRepository workOrderRepository;

    public TurnoverController(TurnoverService turnoverService,
                              WorkOrderService workOrderService,
                              TurnoverRepository turnoverRepository,
                              WorkOrderRepository workOrderRepository) {
        this.turnoverService = turnoverService;
        this.workOrderService = workOrderService;
        this.turnoverRepository = turnoverRepository;
        this.workOrderRepository = workOrderRepository;
    }

    /** Trigger a tenant move-out — starts the event-driven turnover pipeline */
    @PostMapping("/moveout")
    public ResponseEntity<Turnover> moveOut(@RequestParam String propertyId) {
        Turnover turnover = turnoverService.handleMoveOut(propertyId);
        return ResponseEntity.ok(turnover);
    }

    /** List all work orders for a turnover */
    @GetMapping("/{turnoverId}/workorders")
    public List<WorkOrder> workOrders(@PathVariable UUID turnoverId) {
        return workOrderRepository.findByTurnoverId(turnoverId);
    }

    /** Complete a work order — triggers downstream events (Kafka analogy) */
    @PostMapping("/{turnoverId}/workorders/{workOrderId}/complete")
    public ResponseEntity<WorkOrder> completeWorkOrder(@PathVariable UUID turnoverId,
                                                       @PathVariable UUID workOrderId) {
        WorkOrder wo = workOrderService.complete(workOrderId);
        return ResponseEntity.ok(wo);
    }

    /**
     * Simulate a full scenario with pre-built historical data.
     *
     * scenario=bottleneck : REPAIR runs 20h over SLA — typical "before" state
     * scenario=optimized  : CLEANING + REPAIR run in parallel, both within SLA — "after" state
     */
    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulate(@RequestParam String scenario) {
        return switch (scenario) {
            case "bottleneck" -> ResponseEntity.ok(seedBottleneck());
            case "optimized"  -> ResponseEntity.ok(seedOptimized());
            default -> ResponseEntity.badRequest().body(Map.of("error", "Unknown scenario. Use: bottleneck | optimized"));
        };
    }

    // -------------------------------------------------------------------------
    // Simulation helpers — build backdated records to tell the KPI story
    // -------------------------------------------------------------------------

    /**
     * Before state: sequential work orders, REPAIR wildly exceeds SLA.
     *
     * Timeline (hours from move-out):
     *   0h  → move-out
     *   0h  → INSPECTION starts  (SLA: 4h)
     *   6h  → INSPECTION done    (+2h over SLA — already a warning sign)
     *   6h  → CLEANING starts    (SLA: 8h)  ← starts only after inspection (bottleneck)
     *   16h → CLEANING done      (+2h over SLA)
     *   16h → REPAIR starts      (SLA: 24h) ← starts only after cleaning (double bottleneck)
     *   60h → REPAIR done        (+20h over SLA)
     *   ─────────────────────────────────────────
     *   Total cycle: 60h | KPI target: ≤36h | Variance: +24h
     */
    private Map<String, Object> seedBottleneck() {
        LocalDateTime moveOut = LocalDateTime.now().minusHours(60);

        Turnover t = new Turnover();
        t.setPropertyId("PROP-BOTTLENECK-" + System.currentTimeMillis());
        t.setStartedAt(moveOut);
        t.setStatus(TurnoverStatus.COMPLETED);
        t.setCompletedAt(moveOut.plusHours(60));
        turnoverRepository.save(t);

        workOrderRepository.save(buildWo(t.getId(), WorkOrderType.INSPECTION,
                moveOut, moveOut.plusHours(6)));

        workOrderRepository.save(buildWo(t.getId(), WorkOrderType.CLEANING,
                moveOut.plusHours(6), moveOut.plusHours(16)));

        workOrderRepository.save(buildWo(t.getId(), WorkOrderType.REPAIR,
                moveOut.plusHours(16), moveOut.plusHours(60)));

        return Map.of(
                "scenario", "bottleneck",
                "turnoverId", t.getId(),
                "propertyId", t.getPropertyId(),
                "message", "Sequential process: REPAIR blocked until CLEANING finished. REPAIR ran 20h over SLA.",
                "cycleTimeHours", 60,
                "kpiTargetHours", 36,
                "varianceHours", 24
        );
    }

    /**
     * After state: INSPECTION gates CLEANING + REPAIR which then run in parallel.
     *
     * Timeline (hours from move-out):
     *   0h  → move-out
     *   0h  → INSPECTION starts  (SLA: 4h)
     *   3h  → INSPECTION done    (within SLA ✓)
     *   3h  → CLEANING starts    (SLA: 8h)  ← parallel
     *   3h  → REPAIR starts      (SLA: 24h) ← parallel (event fan-out)
     *   10h → CLEANING done      (within SLA ✓)
     *   26h → REPAIR done        (within SLA ✓)
     *   ─────────────────────────────────────────
     *   Total cycle: 26h | KPI target: ≤36h | Savings: 34h vs bottleneck
     */
    private Map<String, Object> seedOptimized() {
        LocalDateTime moveOut = LocalDateTime.now().minusHours(26);

        Turnover t = new Turnover();
        t.setPropertyId("PROP-OPTIMIZED-" + System.currentTimeMillis());
        t.setStartedAt(moveOut);
        t.setStatus(TurnoverStatus.COMPLETED);
        t.setCompletedAt(moveOut.plusHours(26));
        turnoverRepository.save(t);

        workOrderRepository.save(buildWo(t.getId(), WorkOrderType.INSPECTION,
                moveOut, moveOut.plusHours(3)));

        // CLEANING and REPAIR run in parallel after INSPECTION
        workOrderRepository.save(buildWo(t.getId(), WorkOrderType.CLEANING,
                moveOut.plusHours(3), moveOut.plusHours(10)));

        workOrderRepository.save(buildWo(t.getId(), WorkOrderType.REPAIR,
                moveOut.plusHours(3), moveOut.plusHours(26)));

        return Map.of(
                "scenario", "optimized",
                "turnoverId", t.getId(),
                "propertyId", t.getPropertyId(),
                "message", "Parallel CLEANING + REPAIR after INSPECTION. All work orders within SLA.",
                "cycleTimeHours", 26,
                "kpiTargetHours", 36,
                "savedHours", 34
        );
    }

    private WorkOrder buildWo(UUID turnoverId, WorkOrderType type,
                               LocalDateTime start, LocalDateTime end) {
        WorkOrder wo = new WorkOrder();
        wo.setTurnoverId(turnoverId);
        wo.setType(type);
        wo.setStatus(WorkOrderStatus.COMPLETED);
        wo.setStartedAt(start);
        wo.setSlaDeadline(start.plusHours(type.getSlaHours()));
        wo.setCompletedAt(end);
        return wo;
    }
}
