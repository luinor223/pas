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

    /**
     * The bell list. {@code unreadOnly} and {@code category} are the two Figma tab axes (§8:
     * All / Unread / Approvals / E-signature / Expiring) and combine — "unread approvals" is a
     * tab the user can reach — so they are ANDed rather than alternatives. Scoped to the caller
     * in the query itself, not filtered afterwards: there is no path to another user's inbox.
     */
    @Query("""
            select n from Notification n
            where n.recipientUserId = :recipient
              and (:unreadOnly = false or n.readAt is null)
              and (:category is null or n.category = :category)
            order by n.createdAt desc
            """)
    Page<Notification> inboxOf(@Param("recipient") UUID recipient,
                               @Param("unreadOnly") boolean unreadOnly,
                               @Param("category") NotificationCategory category,
                               Pageable pageable);

    /**
     * "Mark all as read". Scoped to the recipient <em>in the update</em> — the one write here
     * that could reach beyond the caller — and guarded on {@code read_at is null}, which is
     * what makes it idempotent: a row already read keeps the moment it was first read.
     */
    @Modifying
    @Query("""
            update Notification n set n.readAt = :readAt
            where n.recipientUserId = :recipient and n.readAt is null
            """)
    int markAllReadFor(@Param("recipient") UUID recipient, @Param("readAt") Instant readAt);

    long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);

    /** One row per category, for the Figma tab counters — one group-by, not a query per tab. */
    @Query("""
            select n.category as category, count(n) as total
            from Notification n where n.recipientUserId = :recipient
            group by n.category
            """)
    List<CategoryCount> countByCategoryFor(@Param("recipient") UUID recipient);

    interface CategoryCount {
        NotificationCategory getCategory();
        long getTotal();
    }

    List<Notification> findByEventId(UUID eventId);
}
