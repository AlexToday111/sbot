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
    return result + (s.rpe() == null ? "" : " · RPE " + n(s.rpe()));
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
