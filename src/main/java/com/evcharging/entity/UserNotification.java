package com.evcharging.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_notifications", indexes = @Index(name = "idx_notification_user_created", columnList = "user_id,created_at"))
public class UserNotification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false, length = 300)
    private String message;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "is_read", nullable = false)
    private boolean read;

    protected UserNotification() {}
    public UserNotification(User user, String message) { this.user = user; this.message = message; }
    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isRead() { return read; }
    public void markRead() { read = true; }
}
