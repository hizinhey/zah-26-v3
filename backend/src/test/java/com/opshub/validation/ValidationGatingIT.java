package com.opshub.validation;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ValidationGatingIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static HttpServer imageServer;
    private static String thumbnailUrl;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void startImageServer() throws IOException {
        imageServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        imageServer.createContext("/thumbnail", ValidationGatingIT::png);
        imageServer.start();
        thumbnailUrl = "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/thumbnail";
    }

    @AfterAll
    static void stopImageServer() {
        imageServer.stop(0);
    }

    @Test
    void persistsPassedFindingsForTheRequestedRevisionAndEnablesGeneration() throws Exception {
        String operationId = createOperation("MOB-301");
        replaceOas(operationId, "Expected header\\nExpected body");

        mockMvc.perform(post("/api/v1/operations/{id}/validate", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceRevision").value(2))
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.canGenerate").value(true))
                .andExpect(jsonPath("$.generateDisabledReasons").isEmpty())
                .andExpect(jsonPath("$.findings[0].status").value("PASSED"));
    }

    @Test
    void returnsAGenerateDisabledReasonWhenAnyFieldIsNotPassed() throws Exception {
        String operationId = createOperation("MOB-302");
        replaceOas(operationId, "Header without a body");

        mockMvc.perform(post("/api/v1/operations/{id}/validate", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.canGenerate").value(false))
                .andExpect(jsonPath("$.generateDisabledReasons[0]").value("Resolve every failed, warning, or unavailable validation finding before generating tests."));
    }

    @Test
    void blocksGenerationWhenTheOperationHasNoOfficialAccounts() throws Exception {
        String operationId = createOperation("MOB-303");

        mockMvc.perform(post("/api/v1/operations/{id}/validate", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.canGenerate").value(false));
    }

    private String createOperation(String jiraId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/operations")
                        .contentType(APPLICATION_JSON)
                        .content("{\"jiraId\":\"" + jiraId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private void replaceOas(String operationId, String content) throws Exception {
        mockMvc.perform(put("/api/v1/operations/{id}/oas", operationId)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":1,"oas":[
                                  {"platform":"ANDROID","oaName":"Account","thumbnailUrl":"%s","content":"%s","buttonText":"Open now","redirectUrl":"https://example.test/offer"}
                                ]}
                                """.formatted(thumbnailUrl, content)))
                .andExpect(status().isOk());
    }

    private static void png(HttpExchange exchange) throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] bytes = output.toByteArray();
        exchange.getResponseHeaders().add("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
