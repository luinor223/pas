package com.abclogistics.pas.notification.listener;

import com.abclogistics.pas.common.events.EventHeaders;
import com.abclogistics.pas.notification.event.EventEnvelope;
import com.abclogistics.pas.notification.service.NotificationCategories;
import com.abclogistics.pas.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The `notification-service` consumer group on `pas.events` — the events registry §4 lists it
 * against, and no others.
 *
 * <p>Two rules the tests pin. **Filter before deserializing:** the `event_type` header exists so a
 * record this service does not handle costs a header read, not a parse; `pas.events` carries every
 * event in the system and most of them are not ours. **Never swallow:** a record that cannot be
 * processed is rethrown so the error handler can route it to `pas.events.DLT`, because a swallowed
 * failure commits the offset and loses the event silently, and a retried-for-ever one blocks the
 * partition for every document that shares it.
 */
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
        String eventType = EventHeaders.of(record, EventHeaders.EVENT_TYPE);
        if (!NotificationCategories.handles(eventType)) {
            return;
        }
        notifications.fanOut(EventEnvelope.from(record, objectMapper));
    }
}
