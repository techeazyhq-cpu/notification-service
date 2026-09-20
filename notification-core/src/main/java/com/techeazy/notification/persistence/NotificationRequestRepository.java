package com.techeazy.notification.persistence;

import com.techeazy.notification.domain.NotificationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRequestRepository
        extends JpaRepository<NotificationRequest, UUID>, JpaSpecificationExecutor<NotificationRequest> {

    Optional<NotificationRequest> findByIdAndClientId(UUID id, UUID clientId);

    Optional<NotificationRequest> findByClientIdAndIdempotencyKey(UUID clientId, String idempotencyKey);

    long countByClientIdAndCreatedAtAfter(UUID clientId, Instant since);
}
