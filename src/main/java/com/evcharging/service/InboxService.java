package com.evcharging.service;

import com.evcharging.entity.User;
import com.evcharging.entity.UserNotification;
import com.evcharging.exception.BookingNotFoundException;
import com.evcharging.repository.UserNotificationRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InboxService {
    private final UserNotificationRepository notifications;
    public InboxService(UserNotificationRepository notifications) { this.notifications = notifications; }

    // The notice and its booking must commit or roll back together; no external service is involved.
    @Transactional(propagation = Propagation.MANDATORY)
    public void bookingNotice(User user, String message) {
        notifications.save(new UserNotification(user, message));
    }
    public List<Notice> list(Long userId) {
        return notifications.findTop100ByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(n -> new Notice(n.getId(), n.getMessage(), n.getCreatedAt(), n.isRead())).toList();
    }
    @Transactional
    public void markRead(Long userId, Long id) {
        notifications.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BookingNotFoundException("Notification not found"))
                .markRead();
    }
    public record Notice(Long id, String message, Instant createdAt, boolean read) {}
}
