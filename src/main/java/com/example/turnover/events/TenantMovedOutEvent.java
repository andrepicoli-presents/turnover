package com.example.turnover.events;

public class TenantMovedOutEvent {
    private final String propertyId;

    public TenantMovedOutEvent(String propertyId) {
        this.propertyId = propertyId;
    }

    public String getPropertyId() {
        return propertyId;
    }
}
