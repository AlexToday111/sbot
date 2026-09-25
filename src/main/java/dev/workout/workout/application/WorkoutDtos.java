package dev.workout.workout.application;

import dev.workout.exercise.domain.MetricType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class WorkoutDtos {
  private WorkoutDtos() {}

  public record Target(
      long exerciseId,
      Integer sets,
      Integer reps,
      BigDecimal weight,
      Integer restSeconds,
      Integer durationSeconds,
      BigDecimal distance,
      List<SetPlan> plan) {
    public Target(
        long exerciseId, Integer sets, Integer reps, BigDecimal weight, Integer restSeconds) {
      this(exerciseId, sets, reps, weight, restSeconds, null, null, List.of());
    }
  }

  public record SetPlan(
      Integer reps,
      BigDecimal weight,
      Integer durationSeconds,
      BigDecimal distance,
      boolean warmup) {}

  public record TemplateInput(String name, String description, List<Target> exercises) {}

  public record TemplateItem(
      long exerciseId,
      String name,
      MetricType metricType,
      int position,
      Integer targetSets,
      Integer targetReps,
      BigDecimal targetWeight,
      Integer restSeconds,
      Integer targetDuration,
      BigDecimal targetDistance,
      List<SetPlan> plan) {
    public TemplateItem(
        long exerciseId,
        String name,
        MetricType metricType,
        int position,
        Integer targetSets,
        Integer targetReps,
        BigDecimal targetWeight,
        Integer restSeconds) {
      this(
          exerciseId,
          name,
          metricType,
          position,
          targetSets,
          targetReps,
          targetWeight,
          restSeconds,
          null,
          null,
          List.of());
    }
  }

  public record TemplateView(
      long id, String name, String description, List<TemplateItem> exercises) {}

  public record SetInput(
      long sessionExerciseId,
      BigDecimal weight,
      Integer repetitions,
      Integer durationSeconds,
      BigDecimal distance,
      BigDecimal rpe,
      String notes,
      boolean warmup) {
    public SetInput(
        long sessionExerciseId,
        BigDecimal weight,
        Integer repetitions,
        Integer durationSeconds,
        BigDecimal distance,
        BigDecimal rpe,
        String notes) {
      this(sessionExerciseId, weight, repetitions, durationSeconds, distance, rpe, notes, false);
    }
  }

  public record SetView(
      long id,
      int number,
      BigDecimal weight,
      Integer repetitions,
      Integer durationSeconds,
      BigDecimal distance,
      BigDecimal rpe,
      String notes,
      boolean warmup) {}

  public record ExerciseView(
      long id,
      long exerciseId,
      String name,
      MetricType metricType,
      int position,
      Integer targetSets,
      Integer targetReps,
      BigDecimal targetWeight,
      Integer restSeconds,
      List<SetView> sets,
      Integer targetDuration,
      BigDecimal targetDistance,
      List<SetPlan> plan,
      boolean skipped) {}

  public record Summary(
      long durationSeconds, int exercises, int sets, long repetitions, BigDecimal volume) {}

  public record SessionView(
      long id,
      Long templateId,
      String name,
      String status,
      Instant startedAt,
      Instant finishedAt,
      int currentPosition,
      List<ExerciseView> exercises,
      Summary summary,
      Instant pausedAt,
      long pausedSeconds,
      Instant restUntil) {}
}
