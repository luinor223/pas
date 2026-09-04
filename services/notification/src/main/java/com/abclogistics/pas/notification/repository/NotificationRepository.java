package com.abclogistics.pas.notification.repository;

import com.abclogistics.pas.notification.domain.Notification;
import com.abclogistics.pas.notification.domain.NotificationCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Filters the caller's inbox by unread state and category. */
    @Query("""
            select n from Notification n
            where n.recipientUserId = :recipient
              and (:unreadOnly = false or n.readAt is null)
              and (:category is null or n.category = :category)
            order by n.createdAt desc, n.id desc
            """)
    Page<Notification> inboxOf(@Param("recipient") UUID recipient,
                               @Param("unreadOnly") boolean unreadOnly,
                               @Param("category") NotificationCategory category,
                               Pageable pageable);

    /** Marks only the caller's unread rows. */
    @Modifying
    @Query("""
            update Notification n set n.readAt = :readAt
            where n.recipientUserId = :recipient and n.readAt is null
            """)
    int markAllReadFor(@Param("recipient") UUID recipient, @Param("readAt") Instant readAt);

    /** The null guard preserves the first read time under concurrent requests. */
    @Modifying
    @Query("""
            update Notification n set n.readAt = :readAt
            where n.id = :id and n.recipientUserId = :recipient and n.readAt is null
            """)
    int markReadFor(@Param("id") UUID id, @Param("recipient") UUID recipient,
                    @Param("readAt") Instant readAt);

    long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);

    /** Returns unread category counters in one query. */
    @Query("""
            select n.category as category, count(n) as total
            from Notification n
            where n.recipientUserId = :recipient and n.readAt is null
            group by n.category
            """)
    List<CategoryCount> countUnreadByCategoryFor(@Param("recipient") UUID recipient);

    interface CategoryCount {
        NotificationCategory getCategory();
        long getTotal();
    }

    List<Notification> findByEventId(UUID eventId);
}
