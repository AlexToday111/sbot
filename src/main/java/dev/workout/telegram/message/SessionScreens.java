package dev.workout.telegram.message;

import dev.workout.workout.application.WorkoutDtos.*;
import java.time.ZoneId;

public final class SessionScreens {
  private SessionScreens() {}

  public static Screen summary(SessionView s) {
    var b =
        Screen.title(
            ("COMPLETED".equals(s.status())
                    ? "✓ Workout completed"
                    : "Workout " + s.status().toLowerCase())
                + "\n\n"
                + s.name()
                + "\n\n"
                + Format.summary(s.summary()));
    for (var e : s.exercises())
      if (!e.sets().isEmpty()) {
        var volume =
            e.metricType() == dev.workout.exercise.domain.MetricType.STRENGTH
                ? e.sets().stream()
                    .map(x -> x.weight().multiply(java.math.BigDecimal.valueOf(x.repetitions())))
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                : null;
        b.line(
            e.name()
                + ": "
                + e.sets().size()
                + " sets"
                + (volume == null ? "" : " · " + Format.n(volume) + " kg"));
      }
    b.button("View all sets", "history:detail:" + s.id() + ":0:0")
        .button("▥ View progress", "progress:menu");
    if ("COMPLETED".equals(s.status())) b.button("↻ Repeat workout", "session:repeat:" + s.id());
    return b.home().build();
  }

  public static Screen detail(SessionView s, ZoneId zone, int exerciseIndex, int page) {
    int index = Math.max(0, Math.min(exerciseIndex, s.exercises().size() - 1));
    var e = s.exercises().get(index);
    var b =
        Screen.title(
            s.name()
                + "\n"
                + s.startedAt().atZone(zone).toLocalDate()
                + "\n\n"
                + Format.summary(s.summary())
                + "\n\n"
                + (index + 1)
                + "/"
                + s.exercises().size()
                + " · "
                + e.name());
    int from = Math.min(Math.max(0, page) * 10, e.sets().size()),
        to = Math.min(from + 10, e.sets().size());
    for (var set : e.sets().subList(from, to))
      b.line(
          set.number()
              + ". "
              + Format.set(set)
              + (set.notes() == null
                  ? ""
                  : "\n   " + set.notes().substring(0, Math.min(120, set.notes().length()))));
    if (e.sets().isEmpty()) b.line("No sets recorded.");
    if (page > 0)
      b.button("← Earlier sets", "history:detail:" + s.id() + ":" + index + ":" + (page - 1));
    if (to < e.sets().size())
      b.button("More sets →", "history:detail:" + s.id() + ":" + index + ":" + (page + 1));
    if (index > 0)
      b.button("← Previous exercise", "history:detail:" + s.id() + ":" + (index - 1) + ":0");
    if (index + 1 < s.exercises().size())
      b.button("Next exercise →", "history:detail:" + s.id() + ":" + (index + 1) + ":0");
    return b.button("Exercise progress", "progress:exercise:" + e.exerciseId() + ":quarter")
        .button("Summary", "history:summary:" + s.id())
        .button("← History", "history:current")
        .home()
        .build();
  }
}
