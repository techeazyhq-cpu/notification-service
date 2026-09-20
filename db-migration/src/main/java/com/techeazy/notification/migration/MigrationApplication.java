package com.techeazy.notification.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** A job, not a server: runs one command, then the process exits with that command's status. */
@SpringBootApplication
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationApplication {

    @Bean
    MigrationService migrationService(MigrationProperties props) {
        return new MigrationService(props);
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
