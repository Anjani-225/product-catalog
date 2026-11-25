package com.example.productcatalog.specification;

import com.example.productcatalog.entity.Product;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<Product> withFilters(String category, BigDecimal minPrice,
                                                      BigDecimal maxPrice) {
        return Specification.allOf(
                category == null ? null : (root, query, builder) ->
                        builder.equal(root.get("category"), category),
                minPrice == null ? null : (root, query, builder) ->
                        builder.greaterThanOrEqualTo(root.get("price"), minPrice),
                maxPrice == null ? null : (root, query, builder) ->
                        builder.lessThanOrEqualTo(root.get("price"), maxPrice)
        );
    }
}
