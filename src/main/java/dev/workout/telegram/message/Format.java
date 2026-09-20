package dev.workout.telegram.message;

import dev.workout.workout.application.WorkoutDtos.*;
import java.math.BigDecimal;

public final class Format {
  private Format() {}

  public static String n(BigDecimal n) {
    return n == null ? "–" : n.stripTrailingZeros().toPlainString();
  }

  public static String duration(long seconds) {
    return seconds >= 3600
        ? (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m"
        : seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
  }

  public static String set(SetView s) {
    String result =
        s.durationSeconds() != null
            ? duration(s.durationSeconds())
                + (s.distance() != null ? " · " + n(s.distance()) + " km" : "")
            : (s.weight() != null ? n(s.weight()) + " kg × " : "") + s.repetitions() + " reps";
    return result + (s.rpe() == null ? "" : " · RPE " + n(s.rpe()));
  }

  public static String summary(Summary s) {
    return "Time: "
        + duration(s.durationSeconds())
        + "\nExercises: "
        + s.exercises()
        + " · Sets: "
        + s.sets()
        + "\nRepetitions: "
        + s.repetitions()
        + "\nStrength volume: "
        + n(s.volume())
        + " kg";
  }
}
