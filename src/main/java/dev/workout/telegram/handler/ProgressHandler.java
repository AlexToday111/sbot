package dev.workout.telegram.handler;

import static dev.workout.common.I18n.t;
import static dev.workout.telegram.message.Screen.b;

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
      case "filter" -> {
        c.data().allSets = !c.data().allSets;
        yield period(c, p[2]);
      }
      case "exfilter" -> {
        c.data().allSets = !c.data().allSets;
        yield exercise(c, Long.parseLong(p[2]), p[3]);
      }
      case "menu" -> period(c, "month");
      case "period" -> period(c, p[2]);
      case "frequency" -> frequency(c, p[2], Integer.parseInt(p[3]));
      case "exercises" -> exerciseList(c, Integer.parseInt(p[2]));
      case "exercise" -> exercise(c, Long.parseLong(p[2]), p[3]);
      case "history" -> exerciseHistory(c, Long.parseLong(p[2]), Integer.parseInt(p[3]));
      default -> throw new DomainException(t("Unknown progress action."));
    };
  }

  private Screen period(Interaction c, String period) {
    var r = analytics.report(c.uid(), period, !c.data().allSets, true);
    var t = r.current();
    var prev = r.previous();
    var b =
        Screen.title(
                t("▥ Progress")
                    + " · "
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
                t("\nPrevious equal elapsed period: ")
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
    b.chart(
        ProgressCharts.report(
            r, java.time.LocalDate.now(clock.withZone(ZoneId.of(c.user().timezone)))));
    b.button(
        t(c.data().allSets ? "All sets — show working only" : "Working sets — show all"),
        "progress:filter:" + period);
    periods(b, period, "progress:period:");
    if (t.workouts() == 0) b.primary(t("▶ Start workout"), "workout:list:0");
    return b.row(
            b(t("By day"), "progress:frequency:" + period + ":0"),
            b(t("By exercise"), "progress:exercises:0"))
        .home()
        .build();
  }

  private Screen frequency(Interaction c, String period, int page) {
    var r = analytics.report(c.uid(), period, !c.data().allSets, true);
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
    return b.pages(page, to < list.size(), "progress:frequency:" + period + ":")
        .navigation("progress:period:" + period)
        .build();
  }

  private Screen exerciseList(Interaction c, int page) {
    var list = exercises.search(c.uid(), "", page);
    var b = Screen.title(t("Exercise progress\nChoose an exercise."));
    list.forEach(e -> b.button(e.name, "progress:exercise:" + e.id + ":quarter"));
    return b.pages(page, list.size() == 8, "progress:exercises:")
        .navigation("progress:menu")
        .build();
  }

  private Screen exercise(Interaction c, long eid, String period) {
    var p = analytics.progress(c.uid(), eid, period, !c.data().allSets, true);
    var b =
        Screen.title(
            p.name() + "\n" + label(period) + "\n\n" + t(p.metric()) + " (" + t(p.unit()) + ")");
    b.chart(ProgressCharts.sessions(p));
    if (p.points().isEmpty()) b.line(t("Complete a workout to see progress."));
    else
      b.line(
          t("Latest result: ")
              + Format.n(p.points().get(p.points().size() - 1).value())
              + " "
              + t(p.unit()));
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
    b.button(
        t(c.data().allSets ? "All sets — show working only" : "Working sets — show all"),
        "progress:exfilter:" + eid + ":" + period);
    return b.row(
            periodButton("Month", "month", period, "progress:exercise:" + eid + ":"),
            periodButton("3 months", "quarter", period, "progress:exercise:" + eid + ":"),
            periodButton("Year", "year", period, "progress:exercise:" + eid + ":"))
        .button(t("Recent workouts"), "progress:history:" + eid + ":0")
        .navigation("progress:exercises:0")
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
    return b.pages(page, list.size() == 5, "progress:history:" + eid + ":")
        .navigation("progress:exercise:" + eid + ":quarter")
        .build();
  }

  private void periods(Screen.Builder screen, String selected, String prefix) {
    screen
        .row(
            periodButton("Week", "week", selected, prefix),
            periodButton("Month", "month", selected, prefix))
        .row(
            periodButton("3 months", "quarter", selected, prefix),
            periodButton("Year", "year", selected, prefix));
  }

  private Screen.Button periodButton(String label, String value, String selected, String prefix) {
    return new Screen.Button(
        (value.equals(selected) ? "✓ " : "") + t(label),
        prefix + value,
        value.equals(selected) ? "primary" : null);
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
