package com.rom.orderflow.inventory.workflow;

import com.rom.orderflow.inventory.error.*;
import com.rom.orderflow.inventory.kafka.event.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class InventoryWorkflowService {
    private final InventoryWorkflowTransactions transactions;

    public void handle(WorkflowEvent event) {
        event.validate();
        String type = event.resolvedType();
        if ("ORDER_CREATED".equals(type)) {
            validateItems(event.items());
            try {
                transactions.reserve(event);
            } catch (InventoryNotFoundException | InsufficientInventoryException | ReservedInventoryException ex) {
                // The failed reservation transaction has already rolled back.
                // Persist the business rejection and its result in a separate transaction.
                transactions.reject(event, ex.getMessage());
            }
        } else if ("INVENTORY_CONFIRM_REQUESTED".equals(type)) {
            transactions.confirm(event);
        } else if ("INVENTORY_RELEASE_REQUESTED".equals(type)) {
            transactions.release(event);
        } else {
            throw new IllegalArgumentException("Unsupported order event: " + type);
        }
    }

    private void validateItems(List<OrderItemEvent> items) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("Order items are required");
        Set<String> skus = new HashSet<>();
        for (OrderItemEvent item : items) {
            if (item == null || item.sku() == null || item.sku().isBlank()
                    || item.sku().length() > 100 || item.quantity() <= 0 || !skus.add(item.sku())) {
                throw new IllegalArgumentException("Items require distinct valid SKUs and positive quantities");
            }
        }
    }
}
