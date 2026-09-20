package dev.workout;

import static org.assertj.core.api.Assertions.*;

import dev.workout.common.DomainException;
import dev.workout.exercise.domain.MetricType;
import dev.workout.workout.application.SetValidation;
import dev.workout.workout.application.WorkoutDtos.SetInput;
import dev.workout.workout.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkoutMathTest {
  @Test
  void summaryCountsOnlyRecordedExercisesAndStrengthVolume() {
    var session = new WorkoutSession();
    session.startedAt = Instant.parse("2026-09-18T10:00:00Z");
    session.finishedAt = session.startedAt.plusSeconds(4320);
    var bench = new SessionExercise();
    bench.metricType = MetricType.STRENGTH;
    bench.sets.add(set("70", 8));
    var undone = set("90", 10);
    undone.voided = true;
    bench.sets.add(undone);
    var pushups = new SessionExercise();
    pushups.metricType = MetricType.BODYWEIGHT;
    pushups.sets.add(set("5", 12));
    var untouched = new SessionExercise();
    untouched.metricType = MetricType.STRENGTH;
    session.exercises.addAll(java.util.List.of(bench, pushups, untouched));
    var result = WorkoutMath.summary(session, Instant.now());
    assertThat(result.durationSeconds()).isEqualTo(4320);
    assertThat(result.sets()).isEqualTo(2);
    assertThat(result.exercises()).isEqualTo(2);
    assertThat(result.repetitions()).isEqualTo(20);
    assertThat(result.volume()).isEqualByComparingTo("560");
  }

  @Test
  void epleyHandlesZeroRepsActualSinglesAndWorkingSets() {
    assertThat(WorkoutMath.oneRm(set("70", 8))).isEqualByComparingTo("88.667");
    assertThat(WorkoutMath.oneRm(set("100", 1))).isEqualByComparingTo("100");
    assertThat(WorkoutMath.oneRm(set("100", 0))).isZero();
  }

  @Test
  void rejectsInvalidAndCrossTypeMetrics() {
    assertThatThrownBy(
            () ->
                SetValidation.validate(
                    MetricType.STRENGTH,
                    new SetInput(1, new BigDecimal("-1"), 8, null, null, null, null)))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () ->
                SetValidation.validate(
                    MetricType.STRENGTH, new SetInput(1, BigDecimal.ONE, 8, 60, null, null, null)))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () ->
                SetValidation.validate(
                    MetricType.TIMED, new SetInput(1, null, null, 0, null, null, null)))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(
            () ->
                SetValidation.validate(
                    MetricType.CARDIO, new SetInput(1, null, null, 600, null, null, null)))
        .isInstanceOf(DomainException.class);
    SetValidation.validate(
        MetricType.BODYWEIGHT,
        new SetInput(1, null, 12, null, null, new BigDecimal("8.5"), "Good"));
    SetValidation.validate(
        MetricType.CARDIO, new SetInput(1, null, null, 1800, new BigDecimal("5"), null, null));
  }

  private ExerciseSet set(String weight, int reps) {
    var s = new ExerciseSet();
    s.weight = new BigDecimal(weight);
    s.repetitions = reps;
    return s;
  }
}
