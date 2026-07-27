package com.opshub.hub.api;

import com.opshub.hub.application.HubTokenValidator;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
@EnableWebSocket
public class HubWebSocketConfig implements WebSocketConfigurer {
    private static final Pattern HUB_ID_PATTERN = Pattern.compile("/ws/v1/hubs/([0-9a-fA-F-]{36})");

    private final HubWebSocketHandler hubWebSocketHandler;
    private final HubTokenValidator hubTokenValidator;

    public HubWebSocketConfig(HubWebSocketHandler hubWebSocketHandler, HubTokenValidator hubTokenValidator) {
        this.hubWebSocketHandler = hubWebSocketHandler;
        this.hubTokenValidator = hubTokenValidator;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hubWebSocketHandler, "/ws/v1/hubs/{hubId}")
                .addInterceptors(new HubHandshakeInterceptor(hubTokenValidator))
                .setAllowedOrigins("*");
    }

    static class HubHandshakeInterceptor implements HandshakeInterceptor {
        private final HubTokenValidator hubTokenValidator;

        HubHandshakeInterceptor(HubTokenValidator hubTokenValidator) {
            this.hubTokenValidator = hubTokenValidator;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                        WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String token = extractToken(request);
            if (!hubTokenValidator.isValid(token)) {
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
            UUID hubId = extractHubId(request);
            if (hubId == null) {
                response.setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
                return false;
            }
            attributes.put(HubWebSocketHandler.HUB_ID_ATTRIBUTE, hubId);
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        }

        private String extractToken(ServerHttpRequest request) {
            String header = request.getHeaders().getFirst("X-Hub-Token");
            if (header != null) {
                return header;
            }
            if (request instanceof ServletServerHttpRequest servletRequest) {
                return servletRequest.getServletRequest().getParameter("token");
            }
            return null;
        }

        private UUID extractHubId(ServerHttpRequest request) {
            Matcher matcher = HUB_ID_PATTERN.matcher(request.getURI().getPath());
            if (!matcher.find()) {
                return null;
            }
            try {
                return UUID.fromString(matcher.group(1));
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }
}
