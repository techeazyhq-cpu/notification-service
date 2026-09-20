package com.techeazy.notification.persistence;

import com.techeazy.notification.domain.NotificationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, UUID> {
    Optional<NotificationRequest> findByIdAndClientId(UUID id, UUID clientId);

    Optional<NotificationRequest> findByClientIdAndIdempotencyKey(UUID clientId, String idempotencyKey);

    Page<NotificationRequest> findByClientIdOrderByCreatedAtDesc(UUID clientId, Pageable pageable);
}
