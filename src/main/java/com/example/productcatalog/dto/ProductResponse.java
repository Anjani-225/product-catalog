package com.example.productcatalog.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        String category,
        BigDecimal price,
        String currency,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
}
