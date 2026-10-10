package com.codesage.webhook;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findByDeliveryId(String deliveryId);

    boolean existsByDeliveryId(String deliveryId);
}
