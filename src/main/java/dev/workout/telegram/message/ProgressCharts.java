package dev.workout.telegram.message;

import static dev.workout.common.I18n.t;

import dev.workout.analytics.application.AnalyticsService.*;
import dev.workout.analytics.domain.Periods;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class ProgressCharts {
  private ProgressCharts() {}

  public static Chart report(Report report, LocalDate today) {
    var days = new HashMap<LocalDate, Day>();
    report.frequency().forEach(d -> days.put(d.date(), d));
    var volume = new ArrayList<Chart.Point>();
    var workouts = new ArrayList<Chart.Point>();
    LocalDate end = report.end().isBefore(today.plusDays(1)) ? report.end() : today.plusDays(1);
    for (LocalDate d = report.start(); d.isBefore(end); d = d.plusDays(1)) {
      Day day = days.get(d);
      String label = d.format(DateTimeFormatter.ofPattern("dd.MM"));
      volume.add(new Chart.Point(label, day == null ? BigDecimal.ZERO : day.volume()));
      workouts.add(new Chart.Point(label, BigDecimal.valueOf(day == null ? 0 : day.workouts())));
    }
    boolean empty = report.current().workouts() == 0;
    return new Chart(
        t("Training progress"),
        report.start() + " — " + end.minusDays(1),
        t("Completed workouts · daily totals"),
        List.of(
            new Chart.Series(
                t("Strength volume"),
                t("kg"),
                true,
                volume,
                empty
                    ? t("Complete a workout to see a chart")
                    : t("No strength volume in this period")),
            new Chart.Series(
                t("Workout frequency"),
                t("workouts"),
                true,
                workouts,
                t("Complete a workout to see a chart"),
                true)));
  }

  public static Chart sessions(Progress progress) {
    return new Chart(
        progress.name(),
        t(progress.metric()) + " · " + t(progress.unit()),
        t("One point per completed workout"),
        List.of(
            new Chart.Series(
                t(progress.metric()),
                t(progress.unit()),
                false,
                progress.points().stream().map(p -> new Chart.Point(p.month(), p.value())).toList(),
                t("Complete a workout to see a chart"))));
  }

  public static Chart exercise(Progress progress, String period, LocalDate today) {
    var window = Periods.of(period, today);
    var values = new HashMap<String, BigDecimal>();
    progress.points().forEach(p -> values.put(p.month(), p.value()));
    var points = new ArrayList<Chart.Point>();
    for (YearMonth month = YearMonth.from(window.start());
        !month.isAfter(YearMonth.from(today));
        month = month.plusMonths(1)) {
      points.add(new Chart.Point(month.toString(), values.get(month.toString())));
    }
    return new Chart(
        progress.name(),
        t(progress.metric()) + " · " + t(progress.unit()),
        t("Monthly best · gaps mean no recorded data"),
        List.of(
            new Chart.Series(
                t(progress.metric()),
                t(progress.unit()),
                false,
                points,
                t("Complete a workout to see a chart"))));
  }
}
