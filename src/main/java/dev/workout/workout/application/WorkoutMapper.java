package dev.workout.workout.application;

import dev.workout.workout.application.WorkoutDtos.*;
import dev.workout.workout.domain.*;
import java.time.Instant;

public final class WorkoutMapper {
  private WorkoutMapper() {}

  public static SetView set(ExerciseSet s) {
    return new SetView(
        s.id, s.setNumber, s.weight, s.repetitions, s.durationSeconds, s.distance, s.rpe, s.notes);
  }

  public static SessionView session(WorkoutSession s, Instant now) {
    var stats = WorkoutMath.summary(s, now);
    return new SessionView(
        s.id,
        s.templateId,
        s.name,
        s.status.name(),
        s.startedAt,
        s.finishedAt,
        s.currentPosition,
        s.exercises.stream()
            .map(
                e ->
                    new ExerciseView(
                        e.id,
                        e.exerciseId,
                        e.name,
                        e.metricType,
                        e.position,
                        e.targetSets,
                        e.targetReps,
                        e.targetWeight,
                        e.restSeconds,
                        e.recordedSets().stream().map(WorkoutMapper::set).toList()))
            .toList(),
        new Summary(
            stats.durationSeconds(),
            stats.exercises(),
            stats.sets(),
            stats.repetitions(),
            stats.volume()));
  }
}
