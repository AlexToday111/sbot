package dev.workout.telegram.message;

import static dev.workout.common.I18n.t;

import dev.workout.workout.application.WorkoutDtos.*;
import java.math.BigDecimal;

public final class Format {
  private Format() {}

  public static String n(BigDecimal n) {
    return n == null ? "–" : n.stripTrailingZeros().toPlainString();
  }

  public static String duration(long seconds) {
    return seconds >= 3600
        ? (seconds / 3600) + t("h ") + ((seconds % 3600) / 60) + t("m")
        : seconds >= 60 ? (seconds / 60) + t("m ") + (seconds % 60) + t("s") : seconds + t("s");
  }

  public static String set(SetView s) {
    String result =
        s.durationSeconds() != null
            ? duration(s.durationSeconds())
                + (s.distance() != null ? " · " + n(s.distance()) + t(" km") : "")
            : (s.weight() != null ? n(s.weight()) + t(" kg × ") : "")
                + s.repetitions()
                + t(" reps");
    return (s.warmup() ? t("Warmup · ") : "")
        + result
        + (s.rpe() == null ? "" : " · RPE " + n(s.rpe()));
  }

  public static String targets(Target x) {
    return (x.sets() == null ? "" : x.sets() + t(" sets"))
        + (x.reps() == null ? "" : " × " + x.reps() + t(" reps"))
        + (x.weight() == null ? "" : " @ " + n(x.weight()) + t(" kg"))
        + (x.durationSeconds() == null ? "" : " · " + duration(x.durationSeconds()))
        + (x.distance() == null ? "" : " · " + n(x.distance()) + t(" km"))
        + (x.plan() == null || x.plan().isEmpty()
            ? ""
            : " · " + t("Individual sets: ") + x.plan().size());
  }

  public static String plan(SetPlan p) {
    return (p.warmup() ? t("Warmup · ") : t("Working · "))
        + (p.durationSeconds() != null
            ? duration(p.durationSeconds())
                + (p.distance() == null ? "" : " · " + n(p.distance()) + t(" km"))
            : (p.weight() == null ? "" : n(p.weight()) + t(" kg × ")) + p.reps() + t(" reps"));
  }

  public static String summary(Summary s) {
    return t("Time: ")
        + duration(s.durationSeconds())
        + t("\nExercises: ")
        + s.exercises()
        + t(" · Sets: ")
        + s.sets()
        + t("\nRepetitions: ")
        + s.repetitions()
        + t("\nStrength volume: ")
        + n(s.volume())
        + t(" kg");
  }
}
