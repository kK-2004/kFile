package com.kk.project.repo;

import com.kk.project.entity.DeadlineReminderMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeadlineReminderMessageRepository extends JpaRepository<DeadlineReminderMessage, Long> {
    Optional<DeadlineReminderMessage> findByIdempotencyKey(String idempotencyKey);
}
