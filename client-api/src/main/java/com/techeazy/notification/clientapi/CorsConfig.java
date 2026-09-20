package com.techeazy.notification.clientapi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Lets the client tracking UI (a different origin) call the API from a browser. This is a servlet filter ordered
 * before {@link ClientAuthFilter} on purpose: preflight requests carry no API key and must not be rejected, and 401/429
 * responses need CORS headers too or the browser hides their status from the UI.
 * Leave {@code client-api.cors-origins} empty to disable CORS (server-to-server callers do not need it).
 */
@Configuration
class CorsConfig {

    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(@Value("${client-api.cors-origins:}") List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> allowed = origins.stream().filter(o -> !o.isBlank()).toList();
        if (!allowed.isEmpty()) {
            config.setAllowedOrigins(allowed);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("X-API-Key", "Content-Type", "Idempotency-Key"));
            config.setExposedHeaders(List.of("Retry-After", "Content-Disposition", "Location"));
            config.setMaxAge(3600L);
        }
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/v1/**", config);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
