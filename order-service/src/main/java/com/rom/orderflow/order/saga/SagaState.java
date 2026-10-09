package com.rom.orderflow.order.saga;

public enum SagaState {
    WAITING_RESERVATION, WAITING_CONFIRMATION, COMPENSATING,
    COMPLETED, CANCELLED, MANUAL_RECOVERY
}
