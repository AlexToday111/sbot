package dev.workout.telegram.message;

import java.math.BigDecimal;
import java.util.List;

/** A persisted, localized chart snapshot; rendering never reads another user's data. */
public record Chart(String title, String subtitle, String footer, List<Series> series) {
  public record Point(String label, BigDecimal value) {}

  public record Series(
      String title,
      String unit,
      boolean bars,
      List<Point> points,
      String emptyText,
      boolean integerAxis) {
    public Series(String title, String unit, boolean bars, List<Point> points, String emptyText) {
      this(title, unit, bars, points, emptyText, false);
    }
  }
}
