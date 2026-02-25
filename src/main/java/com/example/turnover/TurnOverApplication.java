package com.example.turnover;

import com.example.turnover.model.entity.Turnover;
import com.example.turnover.model.entity.WorkOrder;
import com.example.turnover.model.enums.TurnoverStatus;
import com.example.turnover.model.enums.WorkOrderStatus;
import com.example.turnover.model.enums.WorkOrderType;
import com.example.turnover.repository.TurnoverRepository;
import com.example.turnover.repository.WorkOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Seeds two historical turnovers on startup to immediately demonstrate the KPI story:
 *
 *  PROP-BEFORE  → bottleneck scenario: sequential work orders, 60h cycle (24h over KPI target)
 *  PROP-AFTER   → optimised scenario:  parallel work orders,   26h cycle (10h under KPI target)
 *
 * Use GET /turnovers/kpi/summary to see the aggregate impact at a glance.
 */
@SpringBootApplication
public class TurnOverApplication {

    private static final Logger log = LoggerFactory.getLogger(TurnOverApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TurnOverApplication.class, args);
    }

    @Bean
    CommandLineRunner seedDemoData(TurnoverRepository turnoverRepo, WorkOrderRepository workOrderRepo) {
        return args -> {
            seedBottleneckScenario(turnoverRepo, workOrderRepo);
            seedOptimizedScenario(turnoverRepo, workOrderRepo);
            log.info("==========================================================");
            log.info("  POC data seeded. Try:");
            log.info("  GET  /turnovers/kpi/summary          → aggregate KPIs");
            log.info("  POST /turnovers/moveout?propertyId=X → start live flow");
            log.info("  POST /turnovers/simulate?scenario=bottleneck|optimized");
            log.info("==========================================================");
        };
    }

    /**
     * Before state: fully sequential — each step waits for the previous to finish.
     *
     * 0h  move-out
     * 0h  INSPECTION (SLA 4h)  → done at 6h  (+2h over SLA)
     * 6h  CLEANING   (SLA 8h)  → done at 16h (+2h over SLA)
     * 16h REPAIR     (SLA 24h) → done at 60h (+20h over SLA) ← bottleneck
     * ──────────────────────────────────────────
     * cycle = 60h  | KPI target = 36h | variance = +24h
     */
    private void seedBottleneckScenario(TurnoverRepository turnoverRepo, WorkOrderRepository workOrderRepo) {
        LocalDateTime moveOut = LocalDateTime.now().minusDays(4);

        Turnover t = new Turnover();
        t.setPropertyId("PROP-BEFORE");
        t.setStartedAt(moveOut);
        t.setStatus(TurnoverStatus.COMPLETED);
        t.setCompletedAt(moveOut.plusHours(60));
        turnoverRepo.save(t);

        workOrderRepo.save(buildCompleted(t.getId(), WorkOrderType.INSPECTION, moveOut, moveOut.plusHours(6)));
        workOrderRepo.save(buildCompleted(t.getId(), WorkOrderType.CLEANING,   moveOut.plusHours(6), moveOut.plusHours(16)));
        workOrderRepo.save(buildCompleted(t.getId(), WorkOrderType.REPAIR,     moveOut.plusHours(16), moveOut.plusHours(60)));

        log.info("[SEED] PROP-BEFORE (bottleneck): id={} cycleTime=60h", t.getId());
    }

    /**
     * After state: INSPECTION gates CLEANING + REPAIR, which then execute in parallel.
     *
     * 0h  move-out
     * 0h  INSPECTION (SLA 4h)  → done at 3h  (within SLA ✓)
     * 3h  CLEANING   (SLA 8h)  → done at 10h (within SLA ✓)  ← parallel
     * 3h  REPAIR     (SLA 24h) → done at 26h (within SLA ✓)  ← parallel
     * ──────────────────────────────────────────
     * cycle = 26h  | KPI target = 36h | saved 34h vs bottleneck
     */
    private void seedOptimizedScenario(TurnoverRepository turnoverRepo, WorkOrderRepository workOrderRepo) {
        LocalDateTime moveOut = LocalDateTime.now().minusHours(30);

        Turnover t = new Turnover();
        t.setPropertyId("PROP-AFTER");
        t.setStartedAt(moveOut);
        t.setStatus(TurnoverStatus.COMPLETED);
        t.setCompletedAt(moveOut.plusHours(26));
        turnoverRepo.save(t);

        workOrderRepo.save(buildCompleted(t.getId(), WorkOrderType.INSPECTION, moveOut, moveOut.plusHours(3)));
        workOrderRepo.save(buildCompleted(t.getId(), WorkOrderType.CLEANING,   moveOut.plusHours(3), moveOut.plusHours(10)));
        workOrderRepo.save(buildCompleted(t.getId(), WorkOrderType.REPAIR,     moveOut.plusHours(3), moveOut.plusHours(26)));

        log.info("[SEED] PROP-AFTER (optimized):   id={} cycleTime=26h", t.getId());
    }

    private WorkOrder buildCompleted(UUID turnoverId, WorkOrderType type,
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
