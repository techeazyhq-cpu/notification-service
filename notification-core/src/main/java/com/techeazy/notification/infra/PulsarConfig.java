package com.techeazy.notification.infra;

import com.techeazy.notification.config.NotificationProperties;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PulsarConfig {

    @Bean(destroyMethod = "close")
    public PulsarClient pulsarClient(NotificationProperties props) throws PulsarClientException {
        return PulsarClient.builder().serviceUrl(props.getPulsar().getServiceUrl()).build();
    }
}
