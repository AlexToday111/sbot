package dev.workout.workout.application;

import dev.workout.exercise.domain.MetricType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class WorkoutDtos {
  private WorkoutDtos() {}

  public record Target(
      long exerciseId, Integer sets, Integer reps, BigDecimal weight, Integer restSeconds) {}

  public record TemplateInput(String name, String description, List<Target> exercises) {}

  public record TemplateItem(
      long exerciseId,
      String name,
      MetricType metricType,
      int position,
      Integer targetSets,
      Integer targetReps,
      BigDecimal targetWeight,
      Integer restSeconds) {}

  public record TemplateView(
      long id, String name, String description, List<TemplateItem> exercises) {}

  public record SetInput(
      long sessionExerciseId,
      BigDecimal weight,
      Integer repetitions,
      Integer durationSeconds,
      BigDecimal distance,
      BigDecimal rpe,
      String notes) {}

  public record SetView(
      long id,
      int number,
      BigDecimal weight,
      Integer repetitions,
      Integer durationSeconds,
      BigDecimal distance,
      BigDecimal rpe,
      String notes) {}

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
      List<SetView> sets) {}

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
      Summary summary) {}
}
