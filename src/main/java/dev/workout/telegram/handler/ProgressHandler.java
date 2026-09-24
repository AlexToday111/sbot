package dev.workout.telegram.handler;

import static dev.workout.common.I18n.t;

import dev.workout.analytics.application.AnalyticsService;
import dev.workout.common.*;
import dev.workout.exercise.application.ExerciseService;
import dev.workout.telegram.callback.CallbackHandler;
import dev.workout.telegram.message.*;
import dev.workout.workout.application.SessionService;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class ProgressHandler implements CallbackHandler {
  private final AnalyticsService analytics;
  private final ExerciseService exercises;
  private final SessionService sessions;
  private final Clock clock;

  public ProgressHandler(AnalyticsService a, ExerciseService e, SessionService s, Clock clock) {
    analytics = a;
    exercises = e;
    sessions = s;
    this.clock = clock;
  }

  public String prefix() {
    return "progress";
  }

  public Screen handle(Interaction c, String[] p) {
    c.flow(Flow.HOME);
    return switch (p[1]) {
      case "menu" ->
          Screen.title(
                  t("▥ Progress\n\nCompleted workouts only. Calendar periods use your timezone."))
              .button(t("This week"), "progress:period:week")
              .button(t("This month"), "progress:period:month")
              .button(t("3 months"), "progress:period:quarter")
              .button(t("This year"), "progress:period:year")
              .button(t("Exercises"), "progress:exercises:0")
              .button(t("Training volume"), "progress:period:month")
              .button(t("Workout frequency"), "progress:frequency:month:0")
              .home()
              .build();
      case "period" -> period(c, p[2]);
      case "frequency" -> frequency(c, p[2], Integer.parseInt(p[3]));
      case "exercises" -> exerciseList(c, Integer.parseInt(p[2]));
      case "exercise" -> exercise(c, Long.parseLong(p[2]), p[3]);
      case "history" -> exerciseHistory(c, Long.parseLong(p[2]), Integer.parseInt(p[3]));
      default -> throw new DomainException(t("Unknown progress action."));
    };
  }

  private Screen period(Interaction c, String period) {
    var r = analytics.report(c.uid(), period);
    var t = r.current();
    var prev = r.previous();
    var b =
        Screen.title(
                "▥ "
                    + label(period)
                    + "\n"
                    + r.start()
                    + " – "
                    + r.end().minusDays(1)
                    + t("\n\nWorkouts: ")
                    + t.workouts()
                    + t("\nExercises: ")
                    + t.exercises()
                    + t("\nSets: ")
                    + t.sets()
                    + t("\nRepetitions: ")
                    + t.repetitions()
                    + t("\nTraining time: ")
                    + Format.duration(t.durationSeconds())
                    + t("\nStrength volume: ")
                    + Format.n(t.volume())
                    + t(" kg"))
            .line(
                t("\nPrevious full period: ")
                    + prev.workouts()
                    + t(" workouts · ")
                    + Format.n(prev.volume())
                    + t(" kg"))
            .line(
                t("Change: ")
                    + (r.workoutChange() > 0 ? "+" : "")
                    + r.workoutChange()
                    + t(" workouts · ")
                    + (r.volumeChangePercent() == null
                        ? t("no volume baseline")
                        : Format.n(r.volumeChangePercent()) + t("% volume")));
    long elapsed =
        Math.max(
            1,
            java.time.temporal.ChronoUnit.DAYS.between(
                    r.start(),
                    java.time.LocalDate.now(clock.withZone(ZoneId.of(c.user().timezone))))
                + 1);
    b.line(
        t("Average: ")
            + String.format(java.util.Locale.ROOT, "%.1f", t.workouts() * 7.0 / elapsed)
            + t(" workouts/week so far"));
    return b.button(t("Daily frequency & volume"), "progress:frequency:" + period + ":0")
        .button(t("← Progress"), "progress:menu")
        .home()
        .build();
  }

  private Screen frequency(Interaction c, String period, int page) {
    var r = analytics.report(c.uid(), period);
    var list = r.frequency();
    var b =
        Screen.title(
            t("Workout frequency · ") + label(period) + t("\nDays without workouts are omitted."));
    int from = Math.min(Math.max(0, page) * 12, list.size()), to = Math.min(from + 12, list.size());
    list.subList(from, to)
        .forEach(
            d ->
                b.line(
                    d.date()
                        + " · "
                        + d.workouts()
                        + t(" workout(s) · ")
                        + Format.n(d.volume())
                        + t(" kg")));
    if (list.isEmpty()) b.line(t("\nNo completed workouts yet."));
    if (page > 0) b.button(t("← Previous"), "progress:frequency:" + period + ":" + (page - 1));
    if (to < list.size()) b.button(t("Next →"), "progress:frequency:" + period + ":" + (page + 1));
    return b.button(t("← Period"), "progress:period:" + period).home().build();
  }

  private Screen exerciseList(Interaction c, int page) {
    var list = exercises.search(c.uid(), "", page);
    var b = Screen.title(t("Exercise progress\nChoose an exercise."));
    list.forEach(e -> b.button(e.name, "progress:exercise:" + e.id + ":quarter"));
    if (page > 0) b.button(t("← Previous"), "progress:exercises:" + (page - 1));
    if (list.size() == 8) b.button(t("Next →"), "progress:exercises:" + (page + 1));
    return b.button(t("← Progress"), "progress:menu").home().build();
  }

  private Screen exercise(Interaction c, long eid, String period) {
    var p = analytics.progress(c.uid(), eid, period);
    var b =
        Screen.title(
            p.name() + "\n" + label(period) + "\n\n" + t(p.metric()) + " (" + t(p.unit()) + ")");
    p.points().forEach(point -> b.line(point.month() + "   " + Format.n(point.value())));
    if (p.points().isEmpty()) b.line(t("Complete a workout to see progress."));
    if (p.changePercent() != null) b.line(t("\nChange: ") + Format.n(p.changePercent()) + "%");
    if (p.records() != null) {
      var r = p.records();
      b.line(
          t("\n🏆 All-time records\nMax weight: ")
              + Format.n(r.maxWeight())
              + t(" kg\nMost reps: ")
              + r.maxReps()
              + t("\nBest set volume: ")
              + Format.n(r.bestSetVolume())
              + t(" kg\nBest session volume: ")
              + Format.n(r.bestSessionVolume())
              + t(" kg\nEstimated 1RM: ")
              + Format.n(r.estimatedOneRm())
              + t(" kg\nEpley estimate; high-rep estimates are less reliable."));
    }
    return b.button(t("This month"), "progress:exercise:" + eid + ":month")
        .button(t("3 months"), "progress:exercise:" + eid + ":quarter")
        .button(t("This year"), "progress:exercise:" + eid + ":year")
        .button(t("Recent workouts"), "progress:history:" + eid + ":0")
        .button(t("← Exercises"), "progress:exercises:0")
        .home()
        .build();
  }

  private Screen exerciseHistory(Interaction c, long eid, int page) {
    var e = exercises.get(c.uid(), eid);
    var list = sessions.exerciseHistory(c.uid(), eid, page);
    var b = Screen.title(e.name + t("\n\nRecent workouts"));
    if (list.isEmpty()) b.line(t("No completed workouts."));
    for (var s : list) {
      b.line(
          "\n"
              + s.startedAt().atZone(ZoneId.of(c.user().timezone)).toLocalDate()
              + " · "
              + s.name());
      var sets =
          s.exercises().stream()
              .filter(x -> x.exerciseId() == eid)
              .flatMap(x -> x.sets().stream())
              .toList();
      sets.stream().limit(5).forEach(x -> b.line(Format.set(x)));
      if (sets.size() > 5) b.line("… " + sets.size() + t(" sets total"));
      b.button(
          t("Full workout · ") + s.startedAt().atZone(ZoneId.of(c.user().timezone)).toLocalDate(),
          "history:detail:" + s.id() + ":0:0");
    }
    if (page > 0) b.button(t("← Previous"), "progress:history:" + eid + ":" + (page - 1));
    if (list.size() == 5) b.button(t("Next →"), "progress:history:" + eid + ":" + (page + 1));
    return b.button(t("← Exercise progress"), "progress:exercise:" + eid + ":quarter")
        .home()
        .build();
  }

  private String label(String period) {
    return switch (period) {
      case "week" -> t("This week");
      case "month" -> t("This month");
      case "quarter" -> t("Last 3 calendar months");
      case "year" -> t("This year");
      default -> throw new DomainException(t("Invalid period."));
    };
  }
}
