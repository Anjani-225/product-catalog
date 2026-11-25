package com.example.productcatalog.service;

import com.example.productcatalog.dto.PageResponse;
import com.example.productcatalog.dto.ProductCreateRequest;
import com.example.productcatalog.dto.ProductResponse;
import com.example.productcatalog.dto.ProductUpdateRequest;
import com.example.productcatalog.entity.Product;
import com.example.productcatalog.exception.InvalidRequestException;
import com.example.productcatalog.exception.ProductNotFoundException;
import com.example.productcatalog.exception.StaleProductVersionException;
import com.example.productcatalog.repository.ProductRepository;
import com.example.productcatalog.specification.ProductSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

@Service
public class ProductService {

    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "sku", "name", "category", "price", "createdAt", "updatedAt");

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse create(ProductCreateRequest request) {
        Product product = new Product(trim(request.sku()), trim(request.name()),
                trimToNull(request.description()), trim(request.category()), request.price(),
                request.currency().toUpperCase(Locale.ROOT));
        return toResponse(productRepository.saveAndFlush(product));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(String category, BigDecimal minPrice,
                                               BigDecimal maxPrice, int page, int size,
                                               String sort, String direction) {
        validateListRequest(category, minPrice, maxPrice, page, size, sort, direction);
        Sort.Direction sortDirection = Sort.Direction.fromString(direction);
        Sort ordering = Sort.by(sortDirection, sort);
        if (!"id".equals(sort)) {
            ordering = ordering.and(Sort.by(sortDirection, "id"));
        }
        Page<ProductResponse> result = productRepository.findAll(
                        ProductSpecifications.withFilters(trimToNull(category), minPrice, maxPrice),
                        PageRequest.of(page, size, ordering))
                .map(ProductService::toResponse);
        return PageResponse.from(result);
    }

    @Transactional
    public ProductResponse update(long id, ProductUpdateRequest request) {
        Product product = find(id);
        if (!product.getVersion().equals(request.version())) {
            throw new StaleProductVersionException(id, request.version(), product.getVersion());
        }
        product.updateDetails(trim(request.sku()), trim(request.name()),
                trimToNull(request.description()), trim(request.category()), request.price(),
                request.currency().toUpperCase(Locale.ROOT));
        return toResponse(productRepository.saveAndFlush(product));
    }

    @Transactional
    public void delete(long id) {
        Product product = find(id);
        productRepository.delete(product);
        productRepository.flush();
    }

    private Product find(long id) {
        return productRepository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    private static void validateListRequest(String category, BigDecimal minPrice, BigDecimal maxPrice,
                                            int page, int size, String sort, String direction) {
        if (page < 0) {
            throw new InvalidRequestException("page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw new InvalidRequestException("size must be between 1 and 100");
        }
        if (category != null && (category.isBlank() || category.strip().length() > 120)) {
            throw new InvalidRequestException("category must contain between 1 and 120 characters");
        }
        validatePriceFilter("minPrice", minPrice);
        validatePriceFilter("maxPrice", maxPrice);
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new InvalidRequestException("minPrice must not exceed maxPrice");
        }
        if (!SORT_FIELDS.contains(sort)) {
            throw new InvalidRequestException("sort must be one of " + SORT_FIELDS);
        }
        if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) {
            throw new InvalidRequestException("direction must be asc or desc");
        }
    }

    private static void validatePriceFilter(String name, BigDecimal value) {
        if (value == null) {
            return;
        }
        int integerDigits = value.precision() - value.scale();
        if (value.signum() < 0 || value.scale() > 2 || integerDigits > 17) {
            throw new InvalidRequestException(
                    name + " must be non-negative with at most 17 integer and 2 fractional digits");
        }
    }

    private static ProductResponse toResponse(Product product) {
        return new ProductResponse(product.getId(), product.getSku(), product.getName(),
                product.getDescription(), product.getCategory(), product.getPrice(),
                product.getCurrency(), product.getCreatedAt(), product.getUpdatedAt(),
                product.getVersion());
    }

    private static String trim(String value) {
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
