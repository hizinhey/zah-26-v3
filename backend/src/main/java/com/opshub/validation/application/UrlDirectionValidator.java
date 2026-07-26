package com.opshub.validation.application;

import com.opshub.validation.domain.FieldFinding;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

@Component
public class UrlDirectionValidator {
    private static final String FIELD_NAME = "redirectUrl";
    private static final String VALIDATOR_TYPE = "url-direction";
    private static final Set<String> RESERVED_SCHEMES = Set.of(
            "file", "data", "javascript", "jar", "classpath", "intent", "content", "about", "blob"
    );

    public FieldFinding validate(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "A redirect URL is required");
        }
        if (rawValue.chars().anyMatch(Character::isWhitespace)) {
            return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The redirect URL must not contain raw whitespace");
        }
        try {
            URI uri = new URI(rawValue);
            if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The redirect URL must be absolute");
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (RESERVED_SCHEMES.contains(scheme)) {
                return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The redirect URL uses a reserved scheme");
            }
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getRawAuthority() == null) {
                return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The redirect URL must include a host");
            }
            if (!("http".equals(scheme) || "https".equals(scheme))
                    && !scheme.matches("[a-z][a-z0-9+.-]*")) {
                return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The redirect deeplink scheme is unsafe");
            }
            String host = uri.getHost();
            if (rawValue.regionMatches(true, 0, "stg-", 0, 4)
                    || (host != null && host.regionMatches(true, 0, "stg-", 0, 4))) {
                return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "Staging URLs are not allowed");
            }
            return FieldFinding.passed(FIELD_NAME, VALIDATOR_TYPE);
        } catch (URISyntaxException exception) {
            return FieldFinding.failed(FIELD_NAME, VALIDATOR_TYPE, "The redirect URL is malformed");
        }
    }
}
