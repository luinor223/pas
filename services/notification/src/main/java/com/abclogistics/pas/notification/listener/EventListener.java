package com.abclogistics.pas.notification.listener;

import com.abclogistics.pas.common.events.EventHeaders;
import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.notification.service.NotificationCategories;
import com.abclogistics.pas.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Consumes notification event types from {@code pas.events}. */
@Component
public class EventListener {

    private final NotificationService notifications;
    private final ObjectMapper objectMapper;

    public EventListener(NotificationService notifications, ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "pas.events", groupId = "notification-service",
            containerFactory = "kafkaListenerContainerFactory",
            autoStartup = "${notification.kafka.listener-enabled:true}")
    public void onEvent(ConsumerRecord<String, String> record) {
        // Ignore event types owned by other consumers.
        if (!NotificationCategories.handles(EventHeaders.required(record, EventHeaders.EVENT_TYPE))) {
            return;
        }
        notifications.fanOut(EventEnvelope.from(record, objectMapper));
    }
}
