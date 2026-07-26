package com.opshub.validation.application;

import org.springframework.stereotype.Component;

@Component
public class ContentParser {
    public ParsedContent parse(String content) {
        if (content == null) {
            return new ParsedContent("", "");
        }

        int index = 0;
        while (index < content.length()) {
            int lineStart = index;
            while (index < content.length() && content.charAt(index) != '\n' && content.charAt(index) != '\r') {
                index++;
            }
            int lineEnd = index;
            if (!content.substring(lineStart, lineEnd).isBlank()) {
                int bodyStart = consumeLineSeparator(content, index);
                return new ParsedContent(content.substring(lineStart, lineEnd), content.substring(bodyStart));
            }
            index = consumeLineSeparator(content, index);
        }
        return new ParsedContent("", "");
    }

    private int consumeLineSeparator(String content, int index) {
        if (index < content.length() && content.charAt(index) == '\r') {
            index++;
        }
        if (index < content.length() && content.charAt(index) == '\n') {
            index++;
        }
        return index;
    }

    public record ParsedContent(String header, String body) {
    }
}
