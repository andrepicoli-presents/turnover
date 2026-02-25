package com.example.turnover.events;

import java.util.UUID;

/**
 * Simulates a Kafka message on topic: property.ready-for-move-in
 *
 * Published when all work orders for a turnover are completed. In a real system,
 * a downstream Listing Service would consume this to re-activate the property listing.
 */
public class TurnoverReadyForMoveInEvent {

    private final String propertyId;
    private final UUID turnoverId;
    private final long cycleTimeHours;

    public TurnoverReadyForMoveInEvent(String propertyId, UUID turnoverId, long cycleTimeHours) {
        this.propertyId = propertyId;
        this.turnoverId = turnoverId;
        this.cycleTimeHours = cycleTimeHours;
    }

    public String getPropertyId() {
        return propertyId;
    }

    public UUID getTurnoverId() {
        return turnoverId;
    }

    public long getCycleTimeHours() {
        return cycleTimeHours;
    }
}
