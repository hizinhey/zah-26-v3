package com.opshub.hub.application;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class HubTokenValidator {
    private final HubProperties properties;

    public HubTokenValidator(HubProperties properties) {
        this.properties = properties;
    }

    public boolean isValid(String presentedToken) {
        if (presentedToken == null) {
            return false;
        }
        byte[] expected = properties.getSharedToken().getBytes(StandardCharsets.UTF_8);
        byte[] presented = presentedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, presented);
    }
}
