package com.opshub.operation.application;

public record SaveOaCommand(
        String platform,
        String oaName,
        String thumbnailUrl,
        String content,
        String buttonText,
        String redirectUrl
) {
}
