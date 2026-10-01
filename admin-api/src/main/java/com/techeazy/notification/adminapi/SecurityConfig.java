/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.adminapi;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import com.techeazy.notification.adminapi.auth.AdminAuthService;
import com.techeazy.notification.adminapi.auth.BearerTokenFilter;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Administrators sign in at {@code /api/admin/auth/login} (password, plus a TOTP code when two-factor authentication is
 * enabled) and then send the returned session token as a Bearer token. Accounts live in the database (see ADR-005);
 * the intended production setup for many administrators is OIDC with role-based access (see ADR-001).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    private static final String VIEWER = "VIEWER";
    private static final String OPERATOR = "OPERATOR";
    private static final String ADMIN = "ADMIN";
    private static final String API = "/api/**";

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @SuppressWarnings("java:S5804")
    UserDetailsService noPasswordLogin() {
        return username -> {
            throw new UsernameNotFoundException("Password login is handled by /api/admin/auth/login");
        };
    }

    /**
     * CSRF protection is off on purpose: the API is stateless (no cookies) and every request carries an explicit
     * Authorization header set by the SPA, so a forged cross-site request has no credentials to ride on.
     */
    @Bean
    @SuppressWarnings("java:S4502") // reviewed: stateless header-authenticated API, see Javadoc
    SecurityFilterChain chain(HttpSecurity http, AdminAuthService auth) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // Boot forwards failures (400/404/409...) to /error; without this the fallback denyAll turns them into 403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/admin/auth/login").permitAll()
                        // Every role manages its own account: password, two-factor, sign out.
                        .requestMatchers("/api/admin/auth/**").hasRole(VIEWER)
                        // Managing other administrators is always an ADMIN action, GET included.
                        .requestMatchers("/api/admin/administrators/**").hasRole(ADMIN)
                        // Day-to-day operational recovery actions: OPERATOR, not full ADMIN (see ADR-015).
                        .requestMatchers(HttpMethod.POST, "/api/admin/dead-letters/reprocess").hasRole(OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/api/admin/messages/*/retry").hasRole(OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/api/admin/privacy/erasure").hasRole(OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/api/admin/billing/accounts/*/credit").hasRole(OPERATOR)
                        // Everything else: read is VIEWER, write is ADMIN (configuration: clients, providers, rate
                        // limits, templates, billing plans/accounts/invoices).
                        .requestMatchers(HttpMethod.GET, API).hasRole(VIEWER)
                        .requestMatchers(API).hasRole(ADMIN)
                        .anyRequest().denyAll())
                .addFilterBefore(new BearerTokenFilter(auth), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    @Bean
    CorsConfigurationSource cors(@Value("${admin.cors-origins:http://localhost:5173}") List<String> origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration(API, c);
        return src;
    }
}
