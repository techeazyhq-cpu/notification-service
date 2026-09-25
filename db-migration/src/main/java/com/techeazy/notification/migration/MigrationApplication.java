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

package com.techeazy.notification.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A job, not a server: runs one command, then the process exits with that command's status. */
@SpringBootApplication
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationApplication {

    private static final String DEFAULT_PASSWORD = "notification";

    @Bean
    MigrationService migrationService(MigrationProperties props, Environment env) {
        rejectDefaultPasswordOutsideLocal(props, env);
        return new MigrationService(props);
    }

    /**
     * Refuses to run against the password this project ships in {@code docker-compose.yml} unless the {@code local}
     * Spring profile is active. This job's role owns the schema (see ADR-012), so this default matters even more
     * than the services' own DML-only one. Deliberately not shared with {@code notification-core}'s equivalent
     * check (InsecureDefaults): this module is independent of it by design.
     */
    private static void rejectDefaultPasswordOutsideLocal(MigrationProperties props, Environment env) {
        boolean local = Arrays.asList(env.getActiveProfiles()).contains("local");
        if (!local && DEFAULT_PASSWORD.equals(props.getPassword())) {
            throw new IllegalStateException("migration.password (DB_PASSWORD) is still set to this project's shipped "
                    + "development default (\"" + DEFAULT_PASSWORD + "\"). Set a real value before running outside local "
                    + "development, or run with the 'local' Spring profile if this really is local development.");
        }
    }

    /** Standard output as a writer, for command results (kept apart from logging). */
    @Bean
    PrintWriter commandOutput() {
        return new PrintWriter(new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8), true);
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(MigrationApplication.class, args)));
    }
}
