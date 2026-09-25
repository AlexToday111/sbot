package dev.workout.workout.application;

import dev.workout.common.*;
import dev.workout.exercise.domain.MetricType;
import dev.workout.workout.application.WorkoutDtos.SetInput;
import java.math.BigDecimal;

public final class SetEntry {
  private SetEntry() {}

  public static SetInput parse(MetricType type, long exerciseId, String text, boolean warmup) {
    String[] parts = text.strip().split("\\|", -1);
    if (parts.length > 3) throw new DomainException("Use metrics | RPE | notes.");
    String metrics = parts[0].strip();
    if (metrics.matches("(?i)^[wr]\\s+.*")) {
      warmup = metrics.substring(0, 1).equalsIgnoreCase("w");
      metrics = metrics.substring(1).strip();
    }
    var v = metrics.split("\\s+");
    BigDecimal weight = null, distance = null;
    Integer reps = null, duration = null;
    int min = type == MetricType.TIMED || type == MetricType.BODYWEIGHT ? 1 : 2;
    int max = type == MetricType.TIMED ? 1 : 2;
    if (v.length < min || v.length > max)
      throw new DomainException("Check the metric format shown above and try again.");
    switch (type) {
      case STRENGTH -> {
        weight = Checks.decimal(v[0]);
        reps = Checks.integer(v[1]);
      }
      case BODYWEIGHT -> {
        reps = Checks.integer(v[0]);
        if (v.length == 2) weight = Checks.decimal(v[1]);
      }
      case TIMED -> duration = Checks.integer(v[0]);
      case CARDIO -> {
        duration = Checks.integer(v[0]);
        distance = Checks.decimal(v[1]);
      }
    }
    var rpe =
        parts.length > 1 && !parts[1].isBlank() && !parts[1].strip().equals("-")
            ? Checks.decimal(parts[1])
            : null;
    var result =
        new SetInput(
            exerciseId,
            weight,
            reps,
            duration,
            distance,
            rpe,
            parts.length > 2 ? parts[2].strip() : null,
            warmup);
    SetValidation.validate(type, result);
    return result;
  }

  public static String help(MetricType type) {
    return I18n.t(
        switch (type) {
          case STRENGTH -> "weight_kg reps\nExample: 70 8";
          case BODYWEIGHT -> "reps [added_weight_kg]\nExample: 12 or 12 5";
          case CARDIO -> "duration_seconds distance_km\nExample: 1800 5";
          case TIMED -> "duration_seconds\nExample: 60";
        });
  }
}
