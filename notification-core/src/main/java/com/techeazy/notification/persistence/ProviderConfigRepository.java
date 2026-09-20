package com.techeazy.notification.persistence;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProviderConfigRepository extends JpaRepository<ProviderConfig, UUID> {
    List<ProviderConfig> findByChannelAndEnabledTrueOrderByPriorityAsc(Channel channel);
}
