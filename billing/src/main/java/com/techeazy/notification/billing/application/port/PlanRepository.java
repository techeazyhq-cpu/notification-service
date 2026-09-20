package com.techeazy.notification.billing.application.port;

import com.techeazy.notification.billing.domain.Plan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanRepository {

    Plan save(Plan plan);

    Optional<Plan> findById(UUID id);

    Optional<Plan> findByName(String name);

    List<Plan> findAll();
}
