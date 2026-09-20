package com.techeazy.notification.dispatcher.provider;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.List;

/** GET /actuator/providerhealth: circuit breaker state per provider, as seen by this dispatcher instance. */
@Component
@Endpoint(id = "providerhealth")
public class ProviderHealthEndpoint {

    private final ProviderRegistry registry;

    public ProviderHealthEndpoint(ProviderRegistry registry) {
        this.registry = registry;
    }

    @ReadOperation
    public List<ProviderRegistry.BreakerStatus> breakers() {
        return registry.breakerStatus();
    }
}
