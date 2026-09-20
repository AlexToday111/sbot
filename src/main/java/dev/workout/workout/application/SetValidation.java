package dev.workout.workout.application;

import dev.workout.common.*;
import dev.workout.exercise.domain.MetricType;
import dev.workout.workout.application.WorkoutDtos.SetInput;

public final class SetValidation {
  private SetValidation() {}

  public static void validate(MetricType type, SetInput s) {
    if (s == null) throw new DomainException("Provide set metrics.");
    Checks.range(s.weight(), 0, 2000, "Weight");
    Checks.range(s.repetitions(), 0, 1000, "Repetitions");
    Checks.range(s.durationSeconds(), 1, 604800, "Duration in seconds");
    Checks.range(s.distance(), 0, 10000, "Distance in km");
    Checks.range(s.rpe(), 1, 10, "RPE");
    Checks.text(s.notes(), 500);
    Checks.scale(s.weight(), 3, "Weight");
    Checks.scale(s.distance(), 3, "Distance");
    Checks.scale(s.rpe(), 1, "RPE");
    switch (type) {
      case STRENGTH -> {
        if (s.weight() == null || s.repetitions() == null)
          throw new DomainException("Enter weight and repetitions.");
        strengthOnly(s);
      }
      case BODYWEIGHT -> {
        if (s.repetitions() == null) throw new DomainException("Enter repetitions.");
        strengthOnly(s);
      }
      case CARDIO -> {
        if (s.durationSeconds() == null || s.distance() == null)
          throw new DomainException("Enter duration and distance.");
        timedOnly(s);
      }
      case TIMED -> {
        if (s.durationSeconds() == null) throw new DomainException("Enter duration in seconds.");
        timedOnly(s);
        if (s.distance() != null)
          throw new DomainException("Timed exercises only accept duration.");
      }
    }
  }

  private static void strengthOnly(SetInput s) {
    if (s.durationSeconds() != null || s.distance() != null)
      throw new DomainException("This exercise accepts weight and repetitions.");
  }

  private static void timedOnly(SetInput s) {
    if (s.weight() != null || s.repetitions() != null)
      throw new DomainException("This exercise does not accept weight or repetitions.");
  }
}
