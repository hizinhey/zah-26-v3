package com.opshub.validation.application;

import com.opshub.validation.domain.FieldFinding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

@Component
public class ThumbnailValidator {
    private static final String FIELD_NAME = "thumbnailUrl";
    private static final String VALIDATOR_TYPE = "thumbnail";
    private final HttpClient client;
    private final Duration requestTimeout;
    private final int maxBytes;
    private final int maxRedirects;

    @Autowired
    public ThumbnailValidator(ThumbnailValidationProperties properties) {
        properties.validate();
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = properties.getRequestTimeout();
        this.maxBytes = properties.getMaxBytes();
        this.maxRedirects = properties.getMaxRedirects();
    }

    public ThumbnailValidator(Duration connectTimeout, Duration requestTimeout, int maxBytes, int maxRedirects) {
        this(HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build(),
                requestTimeout, maxBytes, maxRedirects);
    }

    ThumbnailValidator(HttpClient client, Duration requestTimeout, int maxBytes, int maxRedirects) {
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.maxBytes = maxBytes;
        this.maxRedirects = maxRedirects;
    }

    public FieldFinding validate(String rawValue) {
        URI current;
        try {
            if (rawValue == null || rawValue.isBlank() || rawValue.chars().anyMatch(Character::isWhitespace)) {
                return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail URL must be an absolute HTTP URL without raw whitespace");
            }
            current = URI.create(rawValue);
            if (!isHttpUrl(current)) {
                return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail URL must be an absolute HTTP URL without raw whitespace");
            }
        } catch (IllegalArgumentException exception) {
            return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail URL is malformed");
        }

        try {
            for (int redirects = 0; ; ) {
                HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(current).GET().timeout(requestTimeout).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    try (InputStream ignored = response.body()) {
                        String location = response.headers().firstValue("Location").orElse(null);
                        if (location == null || redirects >= maxRedirects) {
                            return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail redirect limit was exceeded");
                        }
                        try {
                            current = current.resolve(location);
                        } catch (IllegalArgumentException exception) {
                            return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail redirect location is malformed");
                        }
                        if (!isHttpUrl(current)) {
                            return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail redirect must use HTTP or HTTPS");
                        }
                        redirects++;
                        continue;
                    }
                }
                if (status < 200 || status >= 300) {
                    try (InputStream ignored = response.body()) {
                        return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail request did not succeed");
                    }
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if (!contentType.startsWith("image/")) {
                    try (InputStream ignored = response.body()) {
                        return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail response is not an image");
                    }
                }
                if (response.headers().firstValueAsLong("Content-Length").orElse(0) > maxBytes) {
                    try (InputStream ignored = response.body()) {
                        return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail exceeds the size limit");
                    }
                }
                byte[] bytes;
                try (InputStream body = response.body()) {
                    bytes = readAtMost(body, maxBytes);
                }
                try {
                    if (ImageIO.read(new java.io.ByteArrayInputStream(bytes)) == null) {
                        return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail cannot be decoded as an image");
                    }
                } catch (IOException exception) {
                    return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail cannot be decoded as an image");
                }
                return FieldFinding.passed(FIELD_NAME, VALIDATOR_TYPE);
            }
        } catch (PayloadTooLargeException exception) {
            return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail exceeds the size limit");
        } catch (java.net.http.HttpTimeoutException exception) {
            return FieldFinding.unableToCheck(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail request timed out");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return FieldFinding.unableToCheck(FIELD_NAME, VALIDATOR_TYPE, "The thumbnail could not be retrieved");
        }
    }

    private byte[] readAtMost(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int total = 0;
        for (int read; (read = input.read(buffer)) != -1; ) {
            total += read;
            if (total > limit) {
                throw new PayloadTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private boolean isHttpUrl(URI uri) {
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
    }

    private static final class PayloadTooLargeException extends IOException {
    }
}
