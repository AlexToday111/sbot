package dev.workout.workout.domain;

import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<WorkoutTemplate, Long> {
  List<WorkoutTemplate> findByUserIdAndDeletedFalseOrderByUpdatedAtDesc(long userId, Pageable page);
}
