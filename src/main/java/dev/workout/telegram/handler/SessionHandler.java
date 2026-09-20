package dev.workout.telegram.handler;

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

  public SessionHandler(SessionService s) {
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
                "Enter weight in kg\n\nExample: 70 or 72.5\nUse 0 for bodyweight without added weight.")
            .button("← Workout", "session:view:" + c.data().sessionId)
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
        if (last == null) throw new DomainException("No previous set available.");
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
      case "finishask" ->
          Screen.title(
                  "Finish this workout?\n\nEvery recorded set is already saved. Finishing adds it to history and updates records.")
              .button("✓ Finish workout", "session:finish:" + p[2])
              .button("← Continue", "session:view:" + p[2])
              .home()
              .build();
      case "finish" -> {
        c.flow(Flow.HOME);
        yield SessionScreens.summary(sessions.finish(c.uid(), Long.parseLong(p[2])));
      }
      case "cancelask" ->
          Screen.title(
                  "Cancel this workout?\n\nIts saved data is retained, but it will be excluded from progress and completed history.")
              .button("Cancel workout", "session:cancel:" + p[2])
              .button("← Continue", "session:view:" + p[2])
              .home()
              .build();
      case "cancel" -> {
        sessions.cancel(c.uid(), Long.parseLong(p[2]));
        c.flow(Flow.HOME);
        yield Screen.title("Workout cancelled.\nRecorded data is retained.").home().build();
      }
      default -> throw new DomainException("Unknown session action.");
    };
  }

  public Screen view(Interaction c, SessionView s) {
    c.flow(Flow.HOME);
    if (!s.status().equals("ACTIVE")) return SessionScreens.summary(s);
    c.data().sessionId = s.id();
    var e = s.exercises().get(s.currentPosition());
    c.data().sessionExerciseId = e.id();
    var screen =
        Screen.title(
            "🔥 "
                + s.name()
                + "\n\nExercise "
                + (e.position() + 1)
                + " / "
                + s.exercises().size()
                + "\n"
                + e.name());
    if (e.targetSets() != null || e.targetReps() != null || e.targetWeight() != null)
      screen.line(
          "\nTarget: "
              + (e.targetSets() == null ? "–" : e.targetSets())
              + " sets × "
              + (e.targetReps() == null ? "–" : e.targetReps())
              + " reps"
              + (e.targetWeight() == null ? "" : " @ " + Format.n(e.targetWeight()) + " kg"));
    if (e.restSeconds() != null) screen.line("Rest: " + e.restSeconds() + " seconds");
    var previous = previous(c, e);
    screen.line("\nLast workout:");
    if (previous.isEmpty()) screen.line("No previous performance yet.");
    else {
      previous.stream().limit(6).forEach(x -> screen.line(Format.set(x)));
      if (previous.size() > 6) screen.line("… " + previous.size() + " sets total");
    }
    screen.line("\nCurrent workout:");
    if (e.sets().isEmpty()) screen.line("No sets recorded.");
    else {
      int start = Math.max(0, e.sets().size() - 8);
      if (start > 0) screen.line("Showing latest 8 of " + e.sets().size() + " sets");
      e.sets()
          .subList(start, e.sets().size())
          .forEach(x -> screen.line(x.number() + ". " + Format.set(x)));
    }
    screen.button("+ Add set", "session:add:" + s.id() + ":" + e.id());
    var last = last(c, e);
    if (last != null)
      screen.button("Repeat " + Format.set(last), "session:same:" + s.id() + ":" + e.id());
    if (!e.sets().isEmpty())
      screen.button(
          "↶ Undo last set",
          "session:undo:" + s.id() + ":" + e.sets().get(e.sets().size() - 1).id());
    if (e.position() > 0)
      screen.button("← Previous exercise", "session:nav:" + s.id() + ":" + (e.position() - 1));
    if (e.position() + 1 < s.exercises().size())
      screen.button("Next exercise →", "session:nav:" + s.id() + ":" + (e.position() + 1));
    return screen
        .button("Finish workout", "session:finishask:" + s.id())
        .button("Cancel workout", "session:cancelask:" + s.id())
        .home()
        .build();
  }

  private Screen add(Interaction c, long sid, long eid) {
    var s = sessions.get(c.uid(), sid);
    if (!s.status().equals("ACTIVE")) return SessionScreens.summary(s);
    var e = find(s, eid);
    c.data().sessionId = sid;
    c.data().sessionExerciseId = eid;
    if (e.metricType() == MetricType.CARDIO || e.metricType() == MetricType.TIMED) return manual(c);
    c.flow(Flow.WEIGHT);
    var last = last(c, e);
    BigDecimal weight =
        last != null && last.weight() != null
            ? last.weight()
            : e.targetWeight() != null ? e.targetWeight() : BigDecimal.ZERO;
    var options = new LinkedHashSet<BigDecimal>();
    options.add(weight.subtract(new BigDecimal("2.5")).max(BigDecimal.ZERO));
    options.add(weight);
    options.add(weight.add(new BigDecimal("2.5")).min(new BigDecimal("2000")));
    var screen =
        Screen.title(e.name() + "\n\nWeight (kg)\nChoose a value, or type a custom weight.");
    screen.row(
        options.stream()
            .map(w -> b(Format.n(w), "session:weight:" + Format.n(w)))
            .toArray(Screen.Button[]::new));
    return screen
        .button("Custom weight", "session:customweight")
        .button("Enter full set / RPE / notes", "session:manual")
        .button("← Workout", "session:view:" + sid)
        .home()
        .build();
  }

  private Screen reps(Interaction c) {
    Checks.range(c.data().weight, 0, 2000, "Weight");
    Checks.scale(c.data().weight, 3, "Weight");
    c.flow(Flow.REPS);
    var e = find(sessions.get(c.uid(), c.data().sessionId), c.data().sessionExerciseId);
    var last = last(c, e);
    int base =
        last != null && last.repetitions() != null
            ? last.repetitions()
            : e.targetReps() != null ? e.targetReps() : 8;
    var options =
        new LinkedHashSet<Integer>(
            List.of(Math.max(0, base - 2), base, Math.min(1000, base + 2), 12));
    return Screen.title(
            e.name()
                + "\n\n"
                + Format.n(c.data().weight)
                + " kg\nRepetitions — tap to save, or type a number.")
        .row(
            options.stream()
                .map(r -> b(r.toString(), "session:reps:" + r))
                .toArray(Screen.Button[]::new))
        .button("Enter full set / RPE / notes", "session:manual")
        .button("← Workout", "session:view:" + c.data().sessionId)
        .home()
        .build();
  }

  private Screen manual(Interaction c) {
    c.flow(Flow.METRICS);
    var e = find(sessions.get(c.uid(), c.data().sessionId), c.data().sessionExerciseId);
    String help =
        switch (e.metricType()) {
          case STRENGTH -> "weight_kg reps\nExample: 70 8";
          case BODYWEIGHT -> "reps [added_weight_kg]\nExample: 12 or 12 5";
          case CARDIO -> "duration_seconds distance_km\nExample: 1800 5";
          case TIMED -> "duration_seconds\nExample: 60";
        };
    return Screen.title(
            e.name()
                + "\n\nEnter "
                + help
                + "\n\nOptional: append | RPE | notes\nExample suffix: | 8 | Felt strong\nUse - to omit RPE.\nYour message saves the set.")
        .button("← Workout", "session:view:" + c.data().sessionId)
        .home()
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
    String[] parts = text.split("\\|", -1);
    if (parts.length > 3) throw new DomainException("Use metrics | RPE | notes.");
    String[] values = parts[0].trim().split("\\s+");
    BigDecimal weight = null, distance = null;
    Integer reps = null, duration = null;
    switch (e.metricType()) {
      case STRENGTH -> {
        count(values, 2, 2);
        weight = Checks.decimal(values[0]);
        reps = Checks.integer(values[1]);
      }
      case BODYWEIGHT -> {
        count(values, 1, 2);
        reps = Checks.integer(values[0]);
        if (values.length == 2) weight = Checks.decimal(values[1]);
      }
      case CARDIO -> {
        count(values, 2, 2);
        duration = Checks.integer(values[0]);
        distance = Checks.decimal(values[1]);
      }
      case TIMED -> {
        count(values, 1, 1);
        duration = Checks.integer(values[0]);
      }
    }
    BigDecimal rpe =
        parts.length > 1 && !parts[1].trim().equals("-") && !parts[1].isBlank()
            ? Checks.decimal(parts[1])
            : null;
    return save(
        c,
        new SetInput(
            e.id(),
            weight,
            reps,
            duration,
            distance,
            rpe,
            parts.length > 2 ? parts[2].trim() : null));
  }

  private Screen save(Interaction c, SetInput input) {
    return view(c, sessions.addSet(c.uid(), c.data().sessionId, input, c.key("set")));
  }

  private void count(String[] a, int min, int max) {
    if (a.length < min || a.length > max)
      throw new DomainException("Check the metric format shown above and try again.");
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
