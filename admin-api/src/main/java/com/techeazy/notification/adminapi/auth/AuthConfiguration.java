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

package com.techeazy.notification.adminapi.auth;

import com.techeazy.notification.config.InsecureDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

/** Wires administrator authentication and creates the first administrator from configuration when none exists. */
@Configuration
@EnableScheduling
public class AuthConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(AuthConfiguration.class);
    private static final String DEFAULT_TWO_FACTOR_KEY = "development-only-change-me";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin";

    @Bean
    AdminAuthService adminAuthService(Environment env, JdbcClient jdbc, PasswordEncoder encoder,
                                      @Value("${admin.two-factor-key:" + DEFAULT_TWO_FACTOR_KEY + "}") String twoFactorKey,
                                      @Value("${admin.issuer:Notification Admin}") String issuer,
                                      @Value("${admin.session-idle-minutes:30}") long idleMinutes,
                                      @Value("${admin.session-max-hours:12}") long maxHours,
                                      @Value("${admin.max-failed-attempts:5}") int maxFailedAttempts,
                                      @Value("${admin.lockout-minutes:15}") long lockoutMinutes) {
        InsecureDefaults.reject(env, "admin.two-factor-key", twoFactorKey, DEFAULT_TWO_FACTOR_KEY);
        SecureRandom random = new SecureRandom();
        AuthSettings settings = new AuthSettings(issuer, Duration.ofMinutes(idleMinutes), Duration.ofHours(maxHours),
                maxFailedAttempts, Duration.ofMinutes(lockoutMinutes));
        return new AdminAuthService(new JdbcAdminUserStore(jdbc), new JdbcSessionStore(jdbc), encoder,
                new SecretCipher(twoFactorKey, random), new Totp(random), Clock.systemUTC(), settings, random);
    }

    @Bean
    ApplicationRunner createFirstAdministrator(Environment env, AdminAuthService auth,
                                               @Value("${admin.username}") String username,
                                               @Value("${admin.password}") String password) {
        return args -> {
            auth.bootstrap(username, password, () -> InsecureDefaults.reject(env, "admin.password", password, DEFAULT_ADMIN_PASSWORD));
            LOG.info("Administrator accounts are stored in the database; ADMIN_USERNAME/ADMIN_PASSWORD only seed the first one");
        };
    }

    @Bean
    SessionCleaner sessionCleaner(AdminAuthService auth) {
        return new SessionCleaner(auth);
    }

    /** Removes expired sessions so the table does not grow without bound. */
    static class SessionCleaner {

        private final AdminAuthService auth;

        SessionCleaner(AdminAuthService auth) {
            this.auth = auth;
        }

        @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT10M")
        void purge() {
            int removed = auth.purgeExpiredSessions();
            if (removed > 0) {
                LOG.info("Removed {} expired admin sessions", removed);
            }
        }
    }
}
