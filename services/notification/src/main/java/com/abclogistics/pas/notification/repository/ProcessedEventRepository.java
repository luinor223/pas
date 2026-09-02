package com.abclogistics.pas.notification.repository;

import com.abclogistics.pas.notification.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Deliberately <b>not</b> a {@code JpaRepository}: {@link #claim} is the only writer, and
 * inheriting CRUD would hand every caller {@code save} and {@code deleteAll} beside it. The
 * surface is declared so adding to it is a deliberate act; a reflection test pins the absence.
 */
public interface ProcessedEventRepository extends Repository<ProcessedEvent, UUID> {

    /**
     * Claims the event, or returns 0 because another consumer already owns it. Check-then-act
     * left a window between the read and the insert; the primary key closes it here instead.
     */
    @Modifying
    @Query(value = "insert into notification.processed_event(event_id) values (:eventId) "
            + "on conflict (event_id) do nothing", nativeQuery = true)
    int claim(@Param("eventId") UUID eventId);

    boolean existsById(UUID eventId);
}
