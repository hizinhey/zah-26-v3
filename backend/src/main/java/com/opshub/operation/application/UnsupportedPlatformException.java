package com.opshub.operation.application;

public class UnsupportedPlatformException extends RuntimeException {
    public UnsupportedPlatformException(String platform) {
        super("Only ANDROID official accounts are supported, got: " + platform);
    }
}
