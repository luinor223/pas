package com.abclogistics.pas.notification.listener;

import com.abclogistics.pas.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The `notification-service` consumer group on `pas.events` — every event in registry §4 (§4's
 * Consumers column). Records are filtered on the `event_type` header before the payload is
 * deserialized, and one that can never be processed goes to `pas.events.DLT` so the offset
 * advances rather than blocking the partition.
 */
@Component
public class EventListener {

    private final NotificationService notifications;

    public EventListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @KafkaListener(topics = "pas.events", groupId = "notification-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onEvent(ConsumerRecord<String, String> record) {
        throw new UnsupportedOperationException("Phase B: filter on headers, then fan out");
    }
}
