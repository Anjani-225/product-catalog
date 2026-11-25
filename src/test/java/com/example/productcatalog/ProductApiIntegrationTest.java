package com.example.productcatalog;

import com.example.productcatalog.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProductApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("product_catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        productRepository.deleteAll();
    }

    @Test
    void createsAndRetrievesProduct() throws Exception {
        JsonNode created = create("SKU-001", "Desk lamp", "Lighting", "29.99");

        mockMvc.perform(get("/api/v1/products/{id}", created.get("id").asLong()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andExpect(jsonPath("$.price").value(29.99))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void updatesProductWithExpectedVersion() throws Exception {
        JsonNode created = create("SKU-002", "Chair", "Furniture", "79.00");

        mockMvc.perform(put("/api/v1/products/{id}", created.get("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("SKU-002", "Ergonomic chair", "Furniture", "99.00", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ergonomic chair"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void deletesProduct() throws Exception {
        long id = create("SKU-003", "Mug", "Kitchen", "12.50").get("id").asLong();

        mockMvc.perform(delete("/api/v1/products/{id}", id)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/products/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsDuplicateSku() throws Exception {
        create("DUP-001", "First", "Office", "10.00");

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("DUP-001", "Second", "Office", "11.00", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void returnsNotFoundForUnknownProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products/999999")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/products/999999")).andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("", "", "Office", "-1.00", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.sku").exists())
                .andExpect(jsonPath("$.validationErrors.name").exists())
                .andExpect(jsonPath("$.validationErrors.price").exists());
    }

    @Test
    void filtersByCategoryPriceAndCombinedRanges() throws Exception {
        create("F-001", "Low office", "Office", "10.00");
        create("F-002", "High office", "Office", "50.00");
        create("F-003", "Kitchen", "Kitchen", "30.00");

        mockMvc.perform(get("/api/v1/products").param("category", "Office"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/products").param("minPrice", "25").param("maxPrice", "40"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("F-003"));
        mockMvc.perform(get("/api/v1/products").param("category", "Office")
                        .param("minPrice", "25").param("maxPrice", "60"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("F-002"));
    }

    @Test
    void paginatesWithDeterministicTieBreaker() throws Exception {
        long first = create("P-001", "Same name", "Office", "10.00").get("id").asLong();
        long second = create("P-002", "Same name", "Office", "20.00").get("id").asLong();
        long third = create("P-003", "Same name", "Office", "30.00").get("id").asLong();

        String pageZero = mockMvc.perform(get("/api/v1/products").param("sort", "name")
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2)).andReturn().getResponse().getContentAsString();
        String pageOne = mockMvc.perform(get("/api/v1/products").param("sort", "name")
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        List<Long> ids = List.of(
                objectMapper.readTree(pageZero).at("/content/0/id").asLong(),
                objectMapper.readTree(pageZero).at("/content/1/id").asLong(),
                objectMapper.readTree(pageOne).at("/content/0/id").asLong());
        assertThat(ids).containsExactly(first, second, third);
    }

    @Test
    void rejectsInvalidListParameters() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("minPrice", "20").param("maxPrice", "10"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/products").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/products").param("sort", "description"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/products").param("minPrice", "1.001"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/products").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsStaleUpdateEvenAfterEarlierTransactionCompleted() throws Exception {
        JsonNode created = create("LOCK-001", "Original", "Office", "10.00");
        long id = created.get("id").asLong();
        String originalVersionUpdate = productJson("LOCK-001", "First update", "Office", "11.00", 0);

        mockMvc.perform(put("/api/v1/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(originalVersionUpdate))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(put("/api/v1/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(originalVersionUpdate))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void databaseConstraintsRejectInvalidRowsWithoutApiValidation() {
        insertDirectly("DB-001", "10.00", "USD");

        assertThatThrownBy(() -> insertDirectly("DB-001", "12.00", "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDirectly("DB-NEG", "-0.01", "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDirectly("DB-EUR", "10.00", "EUR"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private JsonNode create(String sku, String name, String category, String price) throws Exception {
        String response = mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(sku, name, category, price, null)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/products/")))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String productJson(String sku, String name, String category, String price, Integer version)
            throws Exception {
        var product = new java.util.LinkedHashMap<String, Object>();
        product.put("sku", sku);
        product.put("name", name);
        product.put("description", "Test product");
        product.put("category", category);
        product.put("price", price);
        product.put("currency", "USD");
        if (version != null) {
            product.put("version", version);
        }
        return objectMapper.writeValueAsString(product);
    }

    private void insertDirectly(String sku, String price, String currency) {
        jdbcTemplate.update("""
                INSERT INTO products
                    (sku, name, description, category, price, currency, created_at, updated_at, version)
                VALUES (?, 'Direct insert', NULL, 'Test', ?::numeric, ?, now(), now(), 0)
                """, sku, price, currency);
    }
}
