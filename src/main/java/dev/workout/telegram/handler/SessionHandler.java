package dev.workout.telegram.handler;

import static dev.workout.common.I18n.t;
import static dev.workout.telegram.message.Screen.b;

import dev.workout.common.*;
import dev.workout.exercise.domain.MetricType;
import dev.workout.telegram.callback.CallbackHandler;
import dev.workout.telegram.message.*;
import dev.workout.workout.application.*;
import dev.workout.workout.application.WorkoutDtos.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SessionHandler implements CallbackHandler {
  private final SessionService sessions;

  private final TrainingService training;
  private final PlanningService planning;
  private final java.time.Clock clock;

  public SessionHandler(
      SessionService s, TrainingService training, PlanningService planning, java.time.Clock clock) {
    this.training = training;
    this.planning = planning;
    this.clock = clock;
    sessions = s;
  }

  public String prefix() {
    return "session";
  }

  public Screen handle(Interaction c, String[] p) {
    return switch (p[1]) {
      case "start" -> view(c, sessions.start(c.uid(), Long.parseLong(p[2]), c.key("start")));
      case "repeat" -> view(c, sessions.repeat(c.uid(), Long.parseLong(p[2]), c.key("repeat")));
      case "view" -> view(c, sessions.get(c.uid(), Long.parseLong(p[2])));
      case "nav" ->
          view(c, sessions.navigate(c.uid(), Long.parseLong(p[2]), Integer.parseInt(p[3])));
      case "add" -> add(c, Long.parseLong(p[2]), Long.parseLong(p[3]));
      case "weight" -> {
        c.data().weight = Checks.decimal(p[2]);
        yield reps(c);
      }
      case "customweight" -> {
        c.flow(Flow.WEIGHT);
        yield Screen.title(
                t(
                    "Enter weight in kg\n\nExample: 70 or 72.5\nUse 0 for bodyweight without added weight."))
            .button(t("← Workout"), "session:view:" + c.data().sessionId)
            .home()
            .build();
      }
      case "reps" ->
          save(
              c,
              new SetInput(
                  c.data().sessionExerciseId,
                  c.data().weight,
                  Checks.integer(p[2]),
                  null,
                  null,
                  null,
                  null));
      case "manual" -> manual(c);
      case "same" -> {
        var s = sessions.get(c.uid(), Long.parseLong(p[2]));
        var e = find(s, Long.parseLong(p[3]));
        var last = last(c, e);
        if (last == null) throw new DomainException(t("No previous set available."));
        c.data().editingSetId = null;
        c.data().warmup = last.warmup();
        c.data().sessionId = s.id();
        c.data().sessionExerciseId = e.id();
        yield save(
            c,
            new SetInput(
                e.id(),
                last.weight(),
                last.repetitions(),
                last.durationSeconds(),
                last.distance(),
                null,
                null));
      }
      case "undo" -> view(c, sessions.undo(c.uid(), Long.parseLong(p[2]), Long.parseLong(p[3])));
      case "finishask" -> {
        var current = sessions.get(c.uid(), Long.parseLong(p[2]));
        if (current.exercises().stream().allMatch(e -> e.sets().isEmpty()))
          yield Screen.title(t("No sets recorded. Cancel this empty workout instead."))
              .danger(t("Cancel workout"), "session:cancelask:" + p[2])
              .navigation("session:view:" + p[2])
              .build();
        if (java.time.Duration.between(current.startedAt(), clock.instant()).toHours() >= 12)
          yield Screen.title(
                  t("This workout has been open for 12 hours. Enter its actual finish time."))
              .primary(t("Enter finish time"), "train:finishat:" + p[2])
              .navigation("session:view:" + p[2])
              .build();
        yield Screen.title(
                t(
                    "Finish this workout?\n\nEvery recorded set is already saved. Finishing adds it to history and updates records."))
            .success(t("✓ Finish workout"), "session:finish:" + p[2])
            .button(t("← Continue"), "session:view:" + p[2])
            .home()
            .build();
      }
      case "finish" -> {
        c.flow(Flow.HOME);
        yield SessionScreens.summary(sessions.finish(c.uid(), Long.parseLong(p[2])));
      }
      case "cancelask" ->
          Screen.title(
                  t(
                      "Cancel this workout?\n\nIts saved data is retained, but it will be excluded from progress and completed history."))
              .danger(t("Cancel workout"), "session:cancel:" + p[2])
              .button(t("← Continue"), "session:view:" + p[2])
              .home()
              .build();
      case "cancel" -> {
        sessions.cancel(c.uid(), Long.parseLong(p[2]));
        c.flow(Flow.HOME);
        yield Screen.title(t("Workout cancelled.\nRecorded data is retained.")).home().build();
      }
      default -> throw new DomainException(t("Unknown session action."));
    };
  }

  public Screen view(Interaction c, SessionView s) {
    c.flow(Flow.HOME);
    c.data().editingSetId = null;
    if (!s.status().equals("ACTIVE")) return SessionScreens.summary(s);
    c.data().sessionId = s.id();
    var e = s.exercises().get(s.currentPosition());
    c.data().sessionExerciseId = e.id();
    var screen =
        Screen.title(
            "🔥 "
                + s.name()
                + t("\n\nExercise ")
                + (e.position() + 1)
                + " / "
                + s.exercises().size()
                + "\n"
                + e.name());
    if (e.targetSets() != null || e.targetReps() != null || e.targetWeight() != null)
      screen.line(
          t("\nTarget: ")
              + (e.targetSets() == null ? "–" : e.targetSets())
              + t(" sets")
              + (e.targetReps() == null ? "" : " × " + e.targetReps() + t(" reps"))
              + (e.targetWeight() == null ? "" : " @ " + Format.n(e.targetWeight()) + t(" kg")));
    if (e.restSeconds() != null) screen.line(t("Rest: ") + e.restSeconds() + t(" seconds"));
    if (e.targetDuration() != null)
      screen.line(t("Duration target: ") + Format.duration(e.targetDuration()));
    if (e.targetDistance() != null)
      screen.line(t("Distance target: ") + Format.n(e.targetDistance()) + t(" km"));
    if (e.skipped()) screen.line(t("Skipped"));
    if (!e.plan().isEmpty()) {
      var next = e.sets().size() < e.plan().size() ? e.plan().get(e.sets().size()) : null;
      if (next != null) screen.line(t("Next planned set: ") + Format.plan(next));
    }
    if (s.pausedAt() != null) screen.line(t("Paused — training time is stopped."));
    if (s.restUntil() != null)
      screen.line(
          t("Rest remaining: ")
              + Format.duration(
                  Math.max(
                      0, java.time.Duration.between(clock.instant(), s.restUntil()).getSeconds())));
    if (s.restUntil() != null) screen.button(t("Refresh timer"), "session:view:" + s.id());
    var previous = previous(c, e);
    screen.line(t("\nLast workout:"));
    if (previous.isEmpty()) screen.line(t("No previous performance yet."));
    else {
      previous.stream().limit(6).forEach(x -> screen.line(Format.set(x)));
      if (previous.size() > 6) screen.line("… " + previous.size() + t(" sets total"));
    }
    screen.line(t("\nCurrent workout:"));
    if (e.sets().isEmpty()) screen.line(t("No sets recorded."));
    else {
      int start = Math.max(0, e.sets().size() - 8);
      if (start > 0) screen.line(t("Showing latest 8 of ") + e.sets().size() + t(" sets"));
      e.sets()
          .subList(start, e.sets().size())
          .forEach(x -> screen.line(x.number() + ". " + Format.set(x)));
    }
    if (s.pausedAt() == null)
      screen.primary(t("+ Add set"), "session:add:" + s.id() + ":" + e.id());
    var last = last(c, e);
    if (last != null && s.pausedAt() == null)
      screen.button(t("Repeat ") + Format.set(last), "session:same:" + s.id() + ":" + e.id());
    if (!e.sets().isEmpty())
      screen.button(
          t("↶ Undo last set"),
          "session:undo:" + s.id() + ":" + e.sets().get(e.sets().size() - 1).id());
    screen.row(
        e.position() > 0
            ? b(t("← Exercise"), "session:nav:" + s.id() + ":" + (e.position() - 1))
            : null,
        e.position() + 1 < s.exercises().size()
            ? b(t("Exercise →"), "session:nav:" + s.id() + ":" + (e.position() + 1))
            : null);
    screen.row(
        b(t("Manage exercise"), "train:manage:" + s.id() + ":" + e.id()),
        b(t("Edit sets"), "train:sets:" + s.id() + ":" + e.id() + ":0"));
    screen.row(
        b(
            t(s.pausedAt() == null ? "Pause" : "Resume"),
            "train:pause:" + s.id() + ":" + (s.pausedAt() == null)),
        b(t("Plan vs actual"), "train:compare:" + s.id() + ":0"));
    if (s.pausedAt() == null)
      screen.row(
          b(
              t("Rest timer"),
              "train:rest:" + s.id() + ":" + (e.restSeconds() == null ? 60 : e.restSeconds())),
          b(t("+30 sec"), "train:extend:" + s.id()),
          b(t("Skip rest"), "train:rest:" + s.id() + ":0"));
    return screen
        .success(t("✓ Finish"), "session:finishask:" + s.id())
        .row(b(t("Cancel workout"), "session:cancelask:" + s.id()), b(t("⌂ Menu"), "menu:home"))
        .build();
  }

  private Screen add(Interaction c, long sid, long eid) {
    var s = sessions.get(c.uid(), sid);
    if (!s.status().equals("ACTIVE")) return SessionScreens.summary(s);
    var e = find(s, eid);
    c.data().sessionId = sid;
    c.data().sessionExerciseId = eid;
    c.data().editingSetId = null;
    c.data().warmup = e.sets().size() < e.plan().size() && e.plan().get(e.sets().size()).warmup();
    if (e.metricType() == MetricType.CARDIO || e.metricType() == MetricType.TIMED) return manual(c);
    c.flow(Flow.WEIGHT);
    var last = last(c, e);
    var planned = e.sets().size() < e.plan().size() ? e.plan().get(e.sets().size()) : null;
    BigDecimal weight =
        planned != null && planned.weight() != null
            ? planned.weight()
            : last != null && last.weight() != null
                ? last.weight()
                : e.targetWeight() != null ? e.targetWeight() : BigDecimal.ZERO;
    var options = new LinkedHashSet<BigDecimal>();
    options.add(weight.subtract(planning.preferences(c.uid()).weightStep()).max(BigDecimal.ZERO));
    options.add(weight);
    options.add(weight.add(planning.preferences(c.uid()).weightStep()).min(new BigDecimal("2000")));
    var screen =
        Screen.title(e.name() + t("\n\nWeight (kg)\nChoose a value, or type a custom weight."));
    screen.row(
        options.stream()
            .map(w -> b(Format.n(w), "session:weight:" + Format.n(w)))
            .toArray(Screen.Button[]::new));
    return screen
        .button(t("Custom weight"), "session:customweight")
        .button(t("Enter full set / RPE / notes"), "session:manual")
        .navigation("session:view:" + sid)
        .build();
  }

  private Screen reps(Interaction c) {
    Checks.range(c.data().weight, 0, 2000, t("Weight"));
    Checks.scale(c.data().weight, 3, t("Weight"));
    c.flow(Flow.REPS);
    var e = find(sessions.get(c.uid(), c.data().sessionId), c.data().sessionExerciseId);
    var last = last(c, e);
    var planned = e.sets().size() < e.plan().size() ? e.plan().get(e.sets().size()) : null;
    int base =
        planned != null && planned.reps() != null
            ? planned.reps()
            : last != null && last.repetitions() != null
                ? last.repetitions()
                : e.targetReps() != null ? e.targetReps() : 8;
    var options =
        new LinkedHashSet<Integer>(
            List.of(Math.max(0, base - 2), base, Math.min(1000, base + 2), 12));
    return Screen.title(
            e.name()
                + "\n\n"
                + Format.n(c.data().weight)
                + t(" kg\nRepetitions — tap to save, or type a number."))
        .row(
            options.stream()
                .map(r -> b(r.toString(), "session:reps:" + r))
                .toArray(Screen.Button[]::new))
        .button(t("Enter full set / RPE / notes"), "session:manual")
        .navigation("session:view:" + c.data().sessionId)
        .build();
  }

  public Screen manual(Interaction c) {
    c.flow(Flow.METRICS);
    var e = find(sessions.get(c.uid(), c.data().sessionId), c.data().sessionExerciseId);
    String help =
        switch (e.metricType()) {
          case STRENGTH -> t("weight_kg reps\nExample: 70 8");
          case BODYWEIGHT -> t("reps [added_weight_kg]\nExample: 12 or 12 5");
          case CARDIO -> t("duration_seconds distance_km\nExample: 1800 5");
          case TIMED -> t("duration_seconds\nExample: 60");
        };
    return Screen.title(
            e.name()
                + t("\n\nEnter ")
                + help
                + t(
                    "\n\nOptional: append | RPE | notes\nExample suffix: | 8 | Felt strong\nUse - to omit RPE.\nYour message saves the set."))
        .button(
            t(
                c.data().warmup
                    ? "Warmup set — switch to working"
                    : "Working set — switch to warmup"),
            "train:warmup")
        .navigation("session:view:" + c.data().sessionId)
        .build();
  }

  public boolean accepts(Flow f) {
    return f == Flow.WEIGHT || f == Flow.REPS || f == Flow.METRICS;
  }

  public Screen text(Interaction c, String text) {
    if (c.state().flow == Flow.WEIGHT) {
      c.data().weight = Checks.decimal(text);
      return reps(c);
    }
    if (c.state().flow == Flow.REPS)
      return save(
          c,
          new SetInput(
              c.data().sessionExerciseId,
              c.data().weight,
              Checks.integer(text),
              null,
              null,
              null,
              null));
    var e = find(sessions.get(c.uid(), c.data().sessionId), c.data().sessionExerciseId);
    return save(c, SetEntry.parse(e.metricType(), e.id(), text, c.data().warmup));
  }

  private Screen save(Interaction c, SetInput input) {
    input =
        new SetInput(
            input.sessionExerciseId(),
            input.weight(),
            input.repetitions(),
            input.durationSeconds(),
            input.distance(),
            input.rpe(),
            input.notes(),
            c.state().flow == Flow.METRICS ? input.warmup() : c.data().warmup);
    if (c.data().editingSetId != null)
      return view(c, training.editSet(c.uid(), c.data().sessionId, c.data().editingSetId, input));
    var result = sessions.addSet(c.uid(), c.data().sessionId, input, c.key("set"));
    var e = find(result, input.sessionExerciseId());
    if (e.restSeconds() != null && e.restSeconds() > 0)
      result = training.rest(c.uid(), result.id(), e.restSeconds(), false);
    c.data().warmup = false;
    return view(c, result);
  }

  private ExerciseView find(SessionView s, long id) {
    return s.exercises().stream()
        .filter(e -> e.id() == id)
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  private List<SetView> previous(Interaction c, ExerciseView e) {
    for (var s : sessions.exerciseHistory(c.uid(), e.exerciseId(), 0)) {
      var sets =
          s.exercises().stream()
              .filter(x -> x.exerciseId() == e.exerciseId())
              .flatMap(x -> x.sets().stream())
              .toList();
      if (!sets.isEmpty()) return sets;
    }
    return List.of();
  }

  private SetView last(Interaction c, ExerciseView e) {
    var sets = e.sets().isEmpty() ? previous(c, e) : e.sets();
    return sets.isEmpty() ? null : sets.get(sets.size() - 1);
  }
}
