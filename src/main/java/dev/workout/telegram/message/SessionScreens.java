package dev.workout.telegram.message;

import static dev.workout.common.I18n.t;

import dev.workout.workout.application.WorkoutDtos.*;
import java.time.ZoneId;

public final class SessionScreens {
  private SessionScreens() {}

  public static Screen summary(SessionView s) {
    var b =
        Screen.title(
            ("COMPLETED".equals(s.status())
                    ? t("✓ Workout completed")
                    : t("Workout ") + t(s.status().toLowerCase(java.util.Locale.ROOT)))
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
                + t(" sets")
                + (volume == null ? "" : " · " + Format.n(volume) + t(" kg")));
      }
    b.button(t("Plan vs actual"), "train:compare:" + s.id() + ":0");
    b.button(t("Save as new template"), "train:savetemplate:" + s.id());
    b.button(t("View all sets"), "history:detail:" + s.id() + ":0:0")
        .button(t("▥ View progress"), "progress:menu");
    if ("COMPLETED".equals(s.status())) b.button(t("↻ Repeat workout"), "session:repeat:" + s.id());
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
    if (e.sets().isEmpty()) b.line(t("No sets recorded."));
    if (page > 0)
      b.button(t("← Earlier sets"), "history:detail:" + s.id() + ":" + index + ":" + (page - 1));
    if (to < e.sets().size())
      b.button(t("More sets →"), "history:detail:" + s.id() + ":" + index + ":" + (page + 1));
    if (index > 0)
      b.button(t("← Previous exercise"), "history:detail:" + s.id() + ":" + (index - 1) + ":0");
    if (index + 1 < s.exercises().size())
      b.button(t("Next exercise →"), "history:detail:" + s.id() + ":" + (index + 1) + ":0");
    if (!s.status().equals("CANCELLED"))
      b.button(t("Edit sets"), "train:sets:" + s.id() + ":" + e.id() + ":0");
    return b.button(t("Exercise progress"), "progress:exercise:" + e.exerciseId() + ":quarter")
        .button(t("Summary"), "history:summary:" + s.id())
        .button(t("← History"), "history:current")
        .home()
        .build();
  }
}
