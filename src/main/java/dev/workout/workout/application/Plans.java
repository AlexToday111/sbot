package dev.workout.workout.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workout.common.*;
import dev.workout.exercise.domain.MetricType;
import dev.workout.workout.application.WorkoutDtos.*;
import java.util.*;

public final class Plans {
  private static final ObjectMapper JSON = new ObjectMapper();

  private Plans() {}

  public static List<SetPlan> read(String value) {
    try {
      return JSON.readValue(value == null ? "[]" : value, new TypeReference<List<SetPlan>>() {});
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid stored set plan", ex);
    }
  }

  public static String write(List<SetPlan> value) {
    try {
      return JSON.writeValueAsString(value == null ? List.of() : value);
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot serialize set plan", ex);
    }
  }

  public static void validate(MetricType type, Target t) {
    Checks.range(t.durationSeconds(), 1, 604800, "Duration in seconds");
    Checks.range(t.distance(), 0, 10000, "Distance");
    Checks.scale(t.distance(), 3, "Distance");
    if ((type == MetricType.STRENGTH || type == MetricType.BODYWEIGHT)
            && (t.durationSeconds() != null || t.distance() != null)
        || (type == MetricType.CARDIO || type == MetricType.TIMED)
            && (t.reps() != null || t.weight() != null)
        || type == MetricType.TIMED && t.distance() != null)
      throw new DomainException("Targets do not match the exercise type.");
    if (t.plan() != null) {
      if (t.plan().size() > 100) throw new DomainException("Maximum 100 sets per exercise.");
      for (var p : t.plan()) {
        if (p == null) throw new DomainException("Provide set metrics.");
        SetValidation.validate(
            type,
            new SetInput(
                1,
                p.weight(),
                p.reps(),
                p.durationSeconds(),
                p.distance(),
                null,
                null,
                p.warmup()));
      }
    }
  }

  public static Target target(TemplateItem e) {
    return new Target(
        e.exerciseId(),
        e.targetSets(),
        e.targetReps(),
        e.targetWeight(),
        e.restSeconds(),
        e.targetDuration(),
        e.targetDistance(),
        e.plan());
  }
}
