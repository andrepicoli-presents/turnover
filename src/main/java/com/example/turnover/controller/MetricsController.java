package com.example.turnover.controller;

import com.example.turnover.model.entity.Turnover;
import com.example.turnover.model.entity.WorkOrder;
import com.example.turnover.model.enums.TurnoverStatus;
import com.example.turnover.model.enums.WorkOrderStatus;
import com.example.turnover.repository.TurnoverRepository;
import com.example.turnover.repository.WorkOrderRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/turnovers")
public class MetricsController {

    private static final int KPI_TARGET_HOURS = 36;

    private final TurnoverRepository turnoverRepository;
    private final WorkOrderRepository workOrderRepository;

    public MetricsController(TurnoverRepository turnoverRepository,
                             WorkOrderRepository workOrderRepository) {
        this.turnoverRepository = turnoverRepository;
        this.workOrderRepository = workOrderRepository;
    }

    /**
     * Full KPI report for a single turnover.
     *
     * Key metrics:
     *  - cycleTimeHours    : total time from move-out to ready (or current elapsed if in progress)
     *  - slaBreached       : true if cycle time exceeds the 36h KPI target
     *  - bottleneck        : the work order type that caused the most delay relative to its SLA
     *  - workOrders        : per-step SLA compliance breakdown
     */
    @GetMapping("/{id}/kpi")
    public Map<String, Object> kpi(@PathVariable UUID id) {
        Turnover turnover = turnoverRepository.findById(id).orElseThrow();
        List<WorkOrder> orders = workOrderRepository.findByTurnoverId(id);

        LocalDateTime reference = turnover.getCompletedAt() != null
                ? turnover.getCompletedAt()
                : LocalDateTime.now();

        long cycleTimeHours = Duration.between(turnover.getStartedAt(), reference).toHours();
        boolean slaBreached = cycleTimeHours > KPI_TARGET_HOURS;

        List<Map<String, Object>> workOrderKpis = orders.stream()
                .map(wo -> buildWorkOrderKpi(wo))
                .toList();

        Map<String, Object> bottleneck = workOrderKpis.stream()
                .filter(m -> (long) m.get("overrunHours") > 0)
                .max(Comparator.comparingLong(m -> (long) m.get("overrunHours")))
                .orElse(null);

        long completedOnTime = workOrderKpis.stream().filter(m -> (boolean) m.get("onTime")).count();
        long totalCompleted = workOrderKpis.stream()
                .filter(m -> WorkOrderStatus.COMPLETED.name().equals(m.get("status"))).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("propertyId", turnover.getPropertyId());
        result.put("turnoverId", turnover.getId());
        result.put("status", turnover.getStatus());
        result.put("cycleTimeHours", cycleTimeHours);
        result.put("kpiTargetHours", KPI_TARGET_HOURS);
        result.put("slaBreached", slaBreached);
        result.put("varianceHours", cycleTimeHours - KPI_TARGET_HOURS);
        result.put("workOrdersOnTimePct", totalCompleted > 0 ? (completedOnTime * 100 / totalCompleted) : null);
        result.put("bottleneck", bottleneck);
        result.put("workOrders", workOrderKpis);
        return result;
    }

    /** Summary KPI across all turnovers — useful for showing trend/evolution to the client */
    @GetMapping("/kpi/summary")
    public Map<String, Object> summary() {
        List<Turnover> all = turnoverRepository.findAll();
        List<Turnover> completed = all.stream()
                .filter(t -> t.getStatus() == TurnoverStatus.COMPLETED && t.getCompletedAt() != null)
                .toList();

        OptionalDouble avgCycle = completed.stream()
                .mapToLong(t -> Duration.between(t.getStartedAt(), t.getCompletedAt()).toHours())
                .average();

        long withinKpi = completed.stream()
                .filter(t -> Duration.between(t.getStartedAt(), t.getCompletedAt()).toHours() <= KPI_TARGET_HOURS)
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTurnovers", all.size());
        result.put("completedTurnovers", completed.size());
        result.put("inProgressTurnovers", all.size() - completed.size());
        result.put("avgCycleTimeHours", avgCycle.isPresent() ? Math.round(avgCycle.getAsDouble()) : null);
        result.put("kpiTargetHours", KPI_TARGET_HOURS);
        result.put("withinKpiCount", withinKpi);
        result.put("kpiCompliancePct", completed.isEmpty() ? null : (withinKpi * 100 / completed.size()));
        return result;
    }

    private Map<String, Object> buildWorkOrderKpi(WorkOrder wo) {
        LocalDateTime ref = wo.getCompletedAt() != null ? wo.getCompletedAt() : LocalDateTime.now();
        long actualHours = Duration.between(wo.getStartedAt(), ref).toHours();
        long slaHours = wo.getType().getSlaHours();
        long overrunHours = Math.max(0, actualHours - slaHours);
        boolean onTime = wo.getStatus() == WorkOrderStatus.COMPLETED && overrunHours == 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", wo.getType());
        m.put("status", wo.getStatus());
        m.put("slaHours", slaHours);
        m.put("actualHours", actualHours);
        m.put("overrunHours", overrunHours);
        m.put("onTime", onTime);
        m.put("slaDeadline", wo.getSlaDeadline());
        m.put("completedAt", wo.getCompletedAt());
        return m;
    }
}
