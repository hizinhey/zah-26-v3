package com.opshub.hub.application;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails application startup if the Hub shared token is still the literal insecure default
 * ({@code dev-hub-token}, see {@code application.yml}'s {@code opshub.hub.shared-token}) while a
 * {@code prod} profile is active (I1). This codebase does not otherwise define a profile-based
 * dev/prod distinction (no other profile is active by default in dev or in the test suite, and
 * none is required to run either) - only an explicitly activated {@code prod} profile
 * (e.g. {@code spring.profiles.active=prod}) triggers this guard, so it never fires for local
 * development or the automated test suite, both of which run with no active profile.
 */
@Component
public class HubTokenStartupGuard implements InitializingBean {
    static final String DEFAULT_TOKEN = "dev-hub-token";
    static final String PROD_PROFILE = "prod";

    private final HubProperties hubProperties;
    private final Environment environment;

    public HubTokenStartupGuard(HubProperties hubProperties, Environment environment) {
        this.hubProperties = hubProperties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean isProd = environment.acceptsProfiles(org.springframework.core.env.Profiles.of(PROD_PROFILE));
        if (isProd && DEFAULT_TOKEN.equals(hubProperties.getSharedToken())) {
            throw new IllegalStateException(
                    "opshub.hub.shared-token is still the insecure default ('" + DEFAULT_TOKEN + "') while the '"
                            + PROD_PROFILE + "' profile is active. Set OPSHUB_HUB_TOKEN to a real secret before "
                            + "starting in production.");
        }
    }
}
