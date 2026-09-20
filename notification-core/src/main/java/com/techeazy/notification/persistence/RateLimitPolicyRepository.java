package com.techeazy.notification.persistence;

import com.techeazy.notification.domain.RateLimitPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RateLimitPolicyRepository extends JpaRepository<RateLimitPolicy, UUID> {
    List<RateLimitPolicy> findByEnabledTrue();
}
