package dev.workout.telegram.handler;

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
          Screen.title("▥ Progress\n\nCompleted workouts only. Calendar periods use your timezone.")
              .button("This week", "progress:period:week")
              .button("This month", "progress:period:month")
              .button("3 months", "progress:period:quarter")
              .button("This year", "progress:period:year")
              .button("Exercises", "progress:exercises:0")
              .button("Training volume", "progress:period:month")
              .button("Workout frequency", "progress:frequency:month:0")
              .home()
              .build();
      case "period" -> period(c, p[2]);
      case "frequency" -> frequency(c, p[2], Integer.parseInt(p[3]));
      case "exercises" -> exerciseList(c, Integer.parseInt(p[2]));
      case "exercise" -> exercise(c, Long.parseLong(p[2]), p[3]);
      case "history" -> exerciseHistory(c, Long.parseLong(p[2]), Integer.parseInt(p[3]));
      default -> throw new DomainException("Unknown progress action.");
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
                    + "\n\nWorkouts: "
                    + t.workouts()
                    + "\nExercises: "
                    + t.exercises()
                    + "\nSets: "
                    + t.sets()
                    + "\nRepetitions: "
                    + t.repetitions()
                    + "\nTraining time: "
                    + Format.duration(t.durationSeconds())
                    + "\nStrength volume: "
                    + Format.n(t.volume())
                    + " kg")
            .line(
                "\nPrevious full period: "
                    + prev.workouts()
                    + " workouts · "
                    + Format.n(prev.volume())
                    + " kg")
            .line(
                "Change: "
                    + (r.workoutChange() > 0 ? "+" : "")
                    + r.workoutChange()
                    + " workouts · "
                    + (r.volumeChangePercent() == null
                        ? "no volume baseline"
                        : Format.n(r.volumeChangePercent()) + "% volume"));
    long elapsed =
        Math.max(
            1,
            java.time.temporal.ChronoUnit.DAYS.between(
                    r.start(),
                    java.time.LocalDate.now(clock.withZone(ZoneId.of(c.user().timezone))))
                + 1);
    b.line(
        "Average: "
            + String.format(java.util.Locale.ROOT, "%.1f", t.workouts() * 7.0 / elapsed)
            + " workouts/week so far");
    return b.button("Daily frequency & volume", "progress:frequency:" + period + ":0")
        .button("← Progress", "progress:menu")
        .home()
        .build();
  }

  private Screen frequency(Interaction c, String period, int page) {
    var r = analytics.report(c.uid(), period);
    var list = r.frequency();
    var b =
        Screen.title(
            "Workout frequency · " + label(period) + "\nDays without workouts are omitted.");
    int from = Math.min(Math.max(0, page) * 12, list.size()), to = Math.min(from + 12, list.size());
    list.subList(from, to)
        .forEach(
            d ->
                b.line(
                    d.date()
                        + " · "
                        + d.workouts()
                        + " workout(s) · "
                        + Format.n(d.volume())
                        + " kg"));
    if (list.isEmpty()) b.line("\nNo completed workouts yet.");
    if (page > 0) b.button("← Previous", "progress:frequency:" + period + ":" + (page - 1));
    if (to < list.size()) b.button("Next →", "progress:frequency:" + period + ":" + (page + 1));
    return b.button("← Period", "progress:period:" + period).home().build();
  }

  private Screen exerciseList(Interaction c, int page) {
    var list = exercises.search(c.uid(), "", page);
    var b = Screen.title("Exercise progress\nChoose an exercise.");
    list.forEach(e -> b.button(e.name, "progress:exercise:" + e.id + ":quarter"));
    if (page > 0) b.button("← Previous", "progress:exercises:" + (page - 1));
    if (list.size() == 8) b.button("Next →", "progress:exercises:" + (page + 1));
    return b.button("← Progress", "progress:menu").home().build();
  }

  private Screen exercise(Interaction c, long eid, String period) {
    var p = analytics.progress(c.uid(), eid, period);
    var b =
        Screen.title(p.name() + "\n" + label(period) + "\n\n" + p.metric() + " (" + p.unit() + ")");
    p.points().forEach(point -> b.line(point.month() + "   " + Format.n(point.value())));
    if (p.points().isEmpty()) b.line("Complete a workout to see progress.");
    if (p.changePercent() != null) b.line("\nChange: " + Format.n(p.changePercent()) + "%");
    if (p.records() != null) {
      var r = p.records();
      b.line(
          "\n🏆 All-time records\nMax weight: "
              + Format.n(r.maxWeight())
              + " kg\nMost reps: "
              + r.maxReps()
              + "\nBest set volume: "
              + Format.n(r.bestSetVolume())
              + " kg\nBest session volume: "
              + Format.n(r.bestSessionVolume())
              + " kg\nEstimated 1RM: "
              + Format.n(r.estimatedOneRm())
              + " kg\nEpley estimate; high-rep estimates are less reliable.");
    }
    return b.button("This month", "progress:exercise:" + eid + ":month")
        .button("3 months", "progress:exercise:" + eid + ":quarter")
        .button("This year", "progress:exercise:" + eid + ":year")
        .button("Recent workouts", "progress:history:" + eid + ":0")
        .button("← Exercises", "progress:exercises:0")
        .home()
        .build();
  }

  private Screen exerciseHistory(Interaction c, long eid, int page) {
    var e = exercises.get(c.uid(), eid);
    var list = sessions.exerciseHistory(c.uid(), eid, page);
    var b = Screen.title(e.name + "\n\nRecent workouts");
    if (list.isEmpty()) b.line("No completed workouts.");
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
      if (sets.size() > 5) b.line("… " + sets.size() + " sets total");
      b.button(
          "Full workout · " + s.startedAt().atZone(ZoneId.of(c.user().timezone)).toLocalDate(),
          "history:detail:" + s.id() + ":0:0");
    }
    if (page > 0) b.button("← Previous", "progress:history:" + eid + ":" + (page - 1));
    if (list.size() == 5) b.button("Next →", "progress:history:" + eid + ":" + (page + 1));
    return b.button("← Exercise progress", "progress:exercise:" + eid + ":quarter").home().build();
  }

  private String label(String period) {
    return switch (period) {
      case "week" -> "This week";
      case "month" -> "This month";
      case "quarter" -> "Last 3 calendar months";
      case "year" -> "This year";
      default -> throw new DomainException("Invalid period.");
    };
  }
}
