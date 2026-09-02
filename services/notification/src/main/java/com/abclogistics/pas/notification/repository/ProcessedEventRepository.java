package com.abclogistics.pas.notification.repository;

import com.abclogistics.pas.notification.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Claims the event, or returns 0 because another consumer already owns it. Check-then-act
     * left a window between the read and the insert; the primary key closes it here instead.
     */
    @Modifying
    @Query(value = "insert into notification.processed_event(event_id) values (:eventId) "
            + "on conflict (event_id) do nothing", nativeQuery = true)
    int claim(@Param("eventId") UUID eventId);
}
