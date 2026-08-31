package com.abclogistics.pas.notification.repository;

import com.abclogistics.pas.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
            select n from Notification n
            where n.recipientUserId = :recipient
              and (:unreadOnly = false or n.readAt is null)
            order by n.createdAt desc
            """)
    Page<Notification> inboxOf(@Param("recipient") UUID recipient,
                               @Param("unreadOnly") boolean unreadOnly,
                               Pageable pageable);

    long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);

    List<Notification> findByEventId(UUID eventId);
}
