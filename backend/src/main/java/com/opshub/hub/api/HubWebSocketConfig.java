package com.opshub.hub.api;

import com.opshub.hub.application.HubConnectionService;
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
            attributes.put(HubWebSocketHandler.HUB_PLATFORM_ATTRIBUTE, extractPlatform(request));
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

        /**
         * Reads which platform the connecting Local Hub serves, so job dispatch can filter to
         * only that Hub's platform - without this, an ANDROID Hub could be offered a WEB job and
         * crash rendering an unknown template id. Defaults to ANDROID for Hubs that predate this
         * header (every Hub before WEB support existed).
         *
         * <p>Every value this method can return is normalized via
         * {@link HubConnectionService#normalizePlatform} - always exactly "ANDROID" or "WEB",
         * never a raw/malformed header value - so the platform stashed in this handshake's
         * session attributes (and later used for both writes like markOnline/heartbeat and reads
         * like offerNextJob/renewActiveLease) can never disagree with itself.
         */
        private String extractPlatform(ServerHttpRequest request) {
            String header = request.getHeaders().getFirst("X-Hub-Platform");
            if (header != null) {
                return HubConnectionService.normalizePlatform(header);
            }
            if (request instanceof ServletServerHttpRequest servletRequest) {
                String param = servletRequest.getServletRequest().getParameter("platform");
                if (param != null) {
                    return HubConnectionService.normalizePlatform(param);
                }
            }
            return "ANDROID";
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
