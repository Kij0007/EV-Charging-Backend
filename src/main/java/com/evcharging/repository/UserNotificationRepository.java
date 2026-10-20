package com.evcharging.repository;

import com.evcharging.entity.UserNotification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {
    List<UserNotification> findTop100ByUserIdOrderByCreatedAtDescIdDesc(Long userId);
    Optional<UserNotification> findByIdAndUserId(Long id, Long userId);
}
