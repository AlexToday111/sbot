package dev.workout.analytics.domain;

import dev.workout.common.DomainException;
import java.time.*;
import java.time.temporal.TemporalAdjusters;

public final class Periods {
  private Periods() {}

  public record Window(
      LocalDate start, LocalDate end, LocalDate previousStart, LocalDate previousEnd) {}

  public static Window of(String period, LocalDate today) {
    return switch (period) {
      case "week" -> {
        LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        yield new Window(start, start.plusWeeks(1), start.minusWeeks(1), start);
      }
      case "month" -> {
        LocalDate start = today.withDayOfMonth(1);
        yield new Window(start, start.plusMonths(1), start.minusMonths(1), start);
      }
      case "quarter" -> {
        LocalDate end = today.withDayOfMonth(1).plusMonths(1);
        yield new Window(end.minusMonths(3), end, end.minusMonths(6), end.minusMonths(3));
      }
      case "year" -> {
        LocalDate start = today.withDayOfYear(1);
        yield new Window(start, start.plusYears(1), start.minusYears(1), start);
      }
      default -> throw new DomainException("Choose week, month, quarter or year.");
    };
  }
}
