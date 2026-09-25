package dev.workout.workout.domain;

import dev.workout.exercise.domain.MetricType;
import java.math.*;
import java.time.*;

public final class WorkoutMath {
  public record Statistics(
      long durationSeconds, int exercises, int sets, long repetitions, BigDecimal volume) {}

  private WorkoutMath() {}

  public static BigDecimal volume(MetricType type, ExerciseSet s) {
    return type == MetricType.STRENGTH && s.weight != null && s.repetitions != null && !s.voided
        ? s.weight.multiply(BigDecimal.valueOf(s.repetitions))
        : BigDecimal.ZERO;
  }

  public static BigDecimal oneRm(ExerciseSet s) {
    if (s.weight == null || s.repetitions == null || s.repetitions == 0 || s.voided)
      return BigDecimal.ZERO;
    if (s.repetitions == 1) return s.weight;
    return s.weight
        .multiply(
            BigDecimal.ONE.add(
                BigDecimal.valueOf(s.repetitions)
                    .divide(BigDecimal.valueOf(30), 8, RoundingMode.HALF_UP)))
        .setScale(3, RoundingMode.HALF_UP);
  }

  public static Statistics summary(WorkoutSession s, Instant now) {
    int exercises = 0, sets = 0;
    long reps = 0;
    BigDecimal volume = BigDecimal.ZERO;
    for (SessionExercise e : s.exercises) {
      if (!e.recordedSets().isEmpty()) exercises++;
      for (ExerciseSet set : e.recordedSets()) {
        sets++;
        reps += set.repetitions == null ? 0 : set.repetitions;
        volume = volume.add(volume(e.metricType, set));
      }
    }
    return new Statistics(
        Math.max(
            0,
            Duration.between(
                        s.startedAt,
                        s.finishedAt == null
                            ? (s.pausedAt == null ? now : s.pausedAt)
                            : s.finishedAt)
                    .getSeconds()
                - s.pausedSeconds),
        exercises,
        sets,
        reps,
        volume);
  }
}
