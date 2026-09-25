package dev.workout.analytics.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordRepository extends JpaRepository<PersonalRecord, Long> {
  void deleteByUserId(long userId);

  Optional<PersonalRecord> findByUserIdAndExerciseId(long userId, long exerciseId);
}
