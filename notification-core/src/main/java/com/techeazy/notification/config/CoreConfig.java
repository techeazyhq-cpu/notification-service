package com.techeazy.notification.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
@EntityScan("com.techeazy.notification.domain")
@EnableJpaRepositories("com.techeazy.notification.persistence")
@EnableScheduling
public class CoreConfig {
}
