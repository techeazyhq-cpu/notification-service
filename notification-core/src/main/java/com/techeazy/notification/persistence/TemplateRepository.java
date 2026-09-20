package com.techeazy.notification.persistence;

import com.techeazy.notification.domain.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateRepository extends JpaRepository<Template, UUID> {

    Optional<Template> findByClientIdAndName(UUID clientId, String name);

    Optional<Template> findByClientIdIsNullAndName(String name);

    /** What a client can see and use: its own templates plus the shared ones. */
    List<Template> findByClientIdOrClientIdIsNullOrderByNameAsc(UUID clientId);

    List<Template> findByClientIdIsNullOrderByNameAsc();

    long countByClientId(UUID clientId);
}
