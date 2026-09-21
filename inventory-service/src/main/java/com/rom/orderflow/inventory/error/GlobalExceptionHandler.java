package com.rom.orderflow.inventory.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InventoryNotFoundException.class)
    public ResponseEntity<?> handleNotFound(InventoryNotFoundException ex) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "timestamp", OffsetDateTime.now().toString(),
                        "status", 404,
                        "error", "Not Found",
                        "message", ex.getMessage()
                ));
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ResponseEntity<?> handleDuplicateSku(DuplicateSkuException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", OffsetDateTime.now().toString(),
                        "status", 409,
                        "error", "Conflict",
                        "message", ex.getMessage()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(
            MethodArgumentNotValidException ex
    ) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error ->
                        error.getField() + ": " + error.getDefaultMessage()
                )
                .orElse("Validation failed");

        return ResponseEntity.badRequest()
                .body(Map.of(
                        "timestamp", OffsetDateTime.now().toString(),
                        "status", 400,
                        "error", "Bad Request",
                        "message", message
                ));
    }

    @ExceptionHandler(InsufficientInventoryException.class)
    public ResponseEntity<?> handleInsufficientInventory(
            InsufficientInventoryException ex
    ) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", OffsetDateTime.now().toString(),
                        "status", 409,
                        "error", "Conflict",
                        "message", ex.getMessage()
                ));
    }

    @ExceptionHandler(ReservedInventoryException.class)
    public ResponseEntity<?> handleReservedInventory(
            ReservedInventoryException ex
    ) {

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", OffsetDateTime.now().toString(),
                        "status", 409,
                        "error", "Conflict",
                        "message", ex.getMessage()
                ));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<?> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", OffsetDateTime.now().toString(),
                        "status", 409,
                        "error", "Conflict",
                        "message", "Inventory was modified by another request. Please retry."
                ));
    }
}