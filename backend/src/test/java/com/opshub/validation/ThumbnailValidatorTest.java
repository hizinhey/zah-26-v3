package com.opshub.validation;

import com.opshub.validation.application.ThumbnailValidator;
import com.opshub.validation.domain.FieldStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ThumbnailValidatorTest {
    private HttpServer server;
    private String baseUrl;
    private ThumbnailValidator validator;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        validator = new ThumbnailValidator(Duration.ofSeconds(1), Duration.ofSeconds(1), 1_024, 5);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void rejectsMoreThanFiveRedirects() {
        for (int index = 0; index < 6; index++) {
            int next = index + 1;
            server.createContext("/redirect-" + index, exchange -> redirect(exchange, "/redirect-" + next));
        }
        server.createContext("/redirect-6", exchange -> image(exchange, png()));

        assertThat(validator.validate(baseUrl + "/redirect-0").status()).isEqualTo(FieldStatus.FAILED);
    }

    @Test
    void rejectsRedirectsOutsideHttp() {
        server.createContext("/bad-redirect", exchange -> redirect(exchange, "ftp://example.test/image.png"));

        assertThat(validator.validate(baseUrl + "/bad-redirect").status()).isEqualTo(FieldStatus.FAILED);
    }

    @Test
    void rejectsAResponseWhoseMimeTypeIsNotAnImage() {
        server.createContext("/text", exchange -> response(exchange, 200, "text/plain", "not an image".getBytes(StandardCharsets.UTF_8)));

        assertThat(validator.validate(baseUrl + "/text").status()).isEqualTo(FieldStatus.FAILED);
    }

    @Test
    void rejectsAnOversizedImagePayload() {
        server.createContext("/large", exchange -> response(exchange, 200, "image/png", new byte[1_025]));

        assertThat(validator.validate(baseUrl + "/large").status()).isEqualTo(FieldStatus.FAILED);
    }

    @Test
    void rejectsAnUndecodableImagePayload() {
        server.createContext("/broken", exchange -> response(exchange, 200, "image/png", "not a PNG".getBytes(StandardCharsets.UTF_8)));

        assertThat(validator.validate(baseUrl + "/broken").status()).isEqualTo(FieldStatus.FAILED);
    }

    @Test
    void reportsTimeoutsAsUnableToCheck() {
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(1_200);
                image(exchange, png());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(validator.validate(baseUrl + "/slow").status()).isEqualTo(FieldStatus.UNABLE_TO_CHECK);
    }

    @Test
    void acceptsADecodablePngImage() {
        server.createContext("/image", exchange -> image(exchange, png()));

        assertThat(validator.validate(baseUrl + "/image").status()).isEqualTo(FieldStatus.PASSED);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void image(HttpExchange exchange, byte[] bytes) throws IOException {
        response(exchange, 200, "image/png", bytes);
    }

    private static void response(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
