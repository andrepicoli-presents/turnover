package com.example.turnover.model.enums;

public enum WorkOrderType {

    // Critical path: INSPECTION must complete before CLEANING and REPAIR can begin
    INSPECTION(4),
    CLEANING(8),
    REPAIR(24);

    private final int slaHours;

    WorkOrderType(int slaHours) {
        this.slaHours = slaHours;
    }

    public int getSlaHours() {
        return slaHours;
    }
}
