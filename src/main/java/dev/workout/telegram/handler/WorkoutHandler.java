package dev.workout.telegram.handler;

import static dev.workout.common.I18n.t;
import static dev.workout.telegram.message.Screen.b;

import dev.workout.common.*;
import dev.workout.exercise.application.ExerciseService;
import dev.workout.exercise.domain.MetricType;
import dev.workout.telegram.callback.CallbackHandler;
import dev.workout.telegram.message.*;
import dev.workout.workout.application.*;
import dev.workout.workout.application.WorkoutDtos.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class WorkoutHandler implements CallbackHandler {
  private final TemplateService templates;
  private final ExerciseService exercises;

  public WorkoutHandler(TemplateService t, ExerciseService e) {
    templates = t;
    exercises = e;
  }

  public String prefix() {
    return "workout";
  }

  public Screen handle(Interaction c, String[] p) {
    return switch (p[1]) {
      case "import" -> {
        c.flow(Flow.CSV_IMPORT);
        yield Screen.title(
                t(
                    "Send a UTF-8 .csv document (up to 64 KiB).\nColumns: workout,exercise,metric_type,sets,reps,weight,rest_seconds\nOne row per exercise, 1–30 exercises. The workout name must be the same in every row.\n\nReview the file, then choose a new copy or replacement. Nothing is created before confirmation. /cancel cancels input."))
            .button(t("← Workouts"), "workout:list:0")
            .home()
            .build();
      }
      case "list" -> list(c, Integer.parseInt(p[2]));
      case "view" -> view(c, Long.parseLong(p[2]));
      case "new" -> {
        c.data().templateId = null;
        c.data().name = null;
        c.data().description = null;
        c.data().targets.clear();
        yield name(c);
      }
      case "edit" -> {
        var t = templates.get(c.uid(), Long.parseLong(p[2]));
        c.data().templateId = t.id();
        c.data().name = t.name();
        c.data().description = t.description();
        c.data().targets.clear();
        t.exercises().forEach(e -> c.data().targets.add(Plans.target(e)));
        yield draft(c);
      }
      case "name" -> name(c);
      case "description" -> {
        c.flow(Flow.WORKOUT_DESCRIPTION);
        yield Screen.title(t("Enter a description (up to 500 characters), or - to clear it."))
            .button(t("← Back"), "workout:draft")
            .home()
            .build();
      }
      case "draft" -> draft(c);
      case "save" -> {
        var t =
            templates.save(
                c.uid(),
                c.data().templateId,
                new TemplateInput(c.data().name, c.data().description, c.data().targets));
        c.data().templateId = t.id();
        c.data().name = null;
        c.data().targets.clear();
        yield view(c, t.id());
      }
      case "deleteask" ->
          Screen.title(
                  t("Delete this workout template?\n\nCompleted and active workouts will be kept."))
              .danger(t("Delete template"), "workout:delete:" + p[2])
              .button(t("← Back"), "workout:view:" + p[2])
              .home()
              .build();
      case "delete" -> {
        templates.delete(c.uid(), Long.parseLong(p[2]));
        yield list(c, 0);
      }
      case "pick" -> picker(c, Integer.parseInt(p[2]));
      case "search" -> {
        c.flow(Flow.EXERCISE_SEARCH);
        yield Screen.title(t("Search exercises\n\nEnter part of an exercise name."))
            .button(t("Browse all"), "workout:all")
            .button(t("← Workout"), "workout:draft")
            .home()
            .build();
      }
      case "all" -> {
        c.data().search = "";
        yield picker(c, 0);
      }
      case "add" -> {
        add(c, Long.parseLong(p[2]));
        yield draft(c);
      }
      case "remove" -> {
        c.data().targets.remove(Integer.parseInt(p[2]));
        yield draft(c);
      }
      case "up" -> {
        int i = Integer.parseInt(p[2]);
        if (i > 0) Collections.swap(c.data().targets, i, i - 1);
        yield draft(c);
      }
      case "item" -> item(c, Integer.parseInt(p[2]));
      case "down" -> {
        int i = Integer.parseInt(p[2]);
        if (i + 1 < c.data().targets.size()) Collections.swap(c.data().targets, i, i + 1);
        yield draft(c);
      }
      case "setplan" -> {
        c.data().targetPosition = Integer.parseInt(p[2]);
        var target = c.data().targets.get(c.data().targetPosition);
        var type = exercises.get(c.uid(), target.exerciseId()).metricType;
        c.flow(Flow.SET_PLAN);
        yield Screen.title(
                t(
                        "Individual set plan\nOne set per line. Prefix w for warmup, r for working. Enter - to clear.\n\n")
                    + SetEntry.help(type))
            .navigation("workout:item:" + p[2])
            .build();
      }
      case "target" -> {
        c.data().targetPosition = Integer.parseInt(p[2]);
        c.flow(Flow.TARGETS);
        yield Screen.title(
                t(
                    "Optional targets\n\nStrength/bodyweight: sets reps weight rest_seconds\nTimed: sets duration_seconds rest_seconds\nCardio: sets duration_seconds distance_km rest_seconds\nUse - to leave a value empty. Rest starts a timer after saving a set."))
            .button(t("Clear targets"), "workout:cleartarget")
            .button(t("← Workout"), "workout:draft")
            .home()
            .build();
      }
      case "cleartarget" -> {
        int i = c.data().targetPosition;
        c.data()
            .targets
            .set(i, new Target(c.data().targets.get(i).exerciseId(), null, null, null, null));
        yield draft(c);
      }
      case "custom" -> {
        c.flow(Flow.CUSTOM_NAME);
        yield Screen.title(t("Create an exercise\n\nEnter its name (1–80 characters)."))
            .button(t("← Exercises"), "workout:pick:0")
            .home()
            .build();
      }
      case "type" -> {
        var e = exercises.create(c.uid(), c.data().customName, MetricType.valueOf(p[2]));
        add(c, e.id);
        yield draft(c);
      }
      default -> throw new DomainException(t("Unknown workout action."));
    };
  }

  public Screen list(Interaction c, int page) {
    c.flow(Flow.HOME);
    var items = templates.list(c.uid(), page);
    var screen =
        Screen.title(
            t("☷ Workouts")
                + (items.isEmpty()
                    ? t("\n\nCreate your first reusable workout.")
                    : t("\n\nChoose a workout.")));
    items.forEach(
        t ->
            screen.button(
                t.name() + " · " + t.exercises().size() + t(" exercises"),
                "workout:view:" + t.id()));
    return screen
        .pages(page, items.size() == 8, "workout:list:")
        .row(b(t("+ Create"), "workout:new"), b(t("Import CSV"), "workout:import"))
        .home()
        .build();
  }

  private Screen view(Interaction c, long id) {
    c.flow(Flow.HOME);
    var t = templates.get(c.uid(), id);
    var screen = Screen.title(t.name());
    if (t.description() != null) screen.line(t.description());
    for (var e : t.exercises())
      screen.line(
          (e.position() + 1)
              + ". "
              + (e.name().length() > 45 ? e.name().substring(0, 42) + "…" : e.name())
              + " · "
              + Format.targets(Plans.target(e)));
    return screen
        .primary(t("▶ Start"), "session:start:" + t.id())
        .button(t("Suggest next plan"), "train:suggest:" + t.id())
        .row(b(t("Edit"), "workout:edit:" + t.id()), b(t("Delete"), "workout:deleteask:" + t.id()))
        .navigation("workout:list:0")
        .build();
  }

  private Screen name(Interaction c) {
    c.flow(Flow.WORKOUT_NAME);
    return Screen.title(t("Workout name\n\nFor example: Push Day (1–80 characters)."))
        .button(t("← Workouts"), "workout:list:0")
        .home()
        .build();
  }

  private Screen draft(Interaction c) {
    c.flow(Flow.WORKOUT_EDIT);
    if (c.data().name == null) return name(c);
    var screen = Screen.title("✎ " + c.data().name + t("\n\nExercises"));
    // Names are limited in this overview; targets and full names appear on exercise
    // buttons/screens.
    for (int i = 0; i < c.data().targets.size(); i++) {
      var t = c.data().targets.get(i);
      String name = exercises.get(c.uid(), t.exerciseId()).name;
      screen.line((i + 1) + ". " + name);
      screen.button(
          (i + 1) + ". " + (name.length() > 36 ? name.substring(0, 33) + "…" : name) + " ›",
          "workout:item:" + i);
    }
    if (c.data().targets.isEmpty()) screen.line(t("No exercises yet."));
    return screen
        .button(t("+ Add exercise"), "workout:all")
        .row(b(t("Rename"), "workout:name"), b(t("Description"), "workout:description"))
        .success(t("✓ Save workout"), "workout:save")
        .home()
        .build();
  }

  private Screen item(Interaction c, int index) {
    c.flow(Flow.WORKOUT_EDIT);
    var target = c.data().targets.get(index);
    var exercise = exercises.get(c.uid(), target.exerciseId());
    return Screen.title((index + 1) + ". " + exercise.name)
        .line(targets(target.sets(), target.reps(), target.weight(), target.restSeconds()))
        .line(
            target.durationSeconds() == null
                ? ""
                : t("Duration target: ") + Format.duration(target.durationSeconds()))
        .line(
            target.distance() == null
                ? ""
                : t("Distance target: ") + Format.n(target.distance()) + t(" km"))
        .line(
            target.plan() == null || target.plan().isEmpty()
                ? ""
                : t("Individual sets: ") + target.plan().size())
        .primary(t("Edit targets"), "workout:target:" + index)
        .button(t("Individual set plan"), "workout:setplan:" + index)
        .row(
            index > 0 ? b(t("↑ Move up"), "workout:up:" + index) : null,
            index + 1 < c.data().targets.size()
                ? b(t("↓ Move down"), "workout:down:" + index)
                : null)
        .button(t("Remove exercise"), "workout:remove:" + index)
        .navigation("workout:draft")
        .build();
  }

  private Screen picker(Interaction c, int page) {
    c.flow(Flow.EXERCISE_PICK);
    var list = exercises.search(c.uid(), c.data().search, page);
    var screen =
        Screen.title(
            t("Add an exercise")
                + (c.data().search.isEmpty() ? "" : t("\nSearch: ") + c.data().search));
    list.forEach(
        e ->
            screen.button(
                e.name + " · " + t(e.metricType.name().toLowerCase(java.util.Locale.ROOT)),
                "workout:add:" + e.id));
    if (list.isEmpty()) screen.line(t("No matching exercises."));
    return screen
        .pages(page, list.size() == 8, "workout:pick:")
        .row(b(t("Search"), "workout:search"), b(t("+ Custom exercise"), "workout:custom"))
        .navigation("workout:draft")
        .build();
  }

  private void add(Interaction c, long eid) {
    if (c.data().targets.size() >= 30)
      throw new DomainException(t("Maximum 30 exercises per workout."));
    exercises.get(c.uid(), eid);
    c.data().targets.add(new Target(eid, null, null, null, null));
  }

  public boolean accepts(Flow f) {
    return Set.of(
            Flow.WORKOUT_NAME,
            Flow.WORKOUT_DESCRIPTION,
            Flow.EXERCISE_SEARCH,
            Flow.CUSTOM_NAME,
            Flow.TARGETS,
            Flow.SET_PLAN)
        .contains(f);
  }

  public Screen text(Interaction c, String text) {
    return switch (c.state().flow) {
      case WORKOUT_NAME -> {
        c.data().name = Checks.name(text);
        yield draft(c);
      }
      case WORKOUT_DESCRIPTION -> {
        c.data().description = text.equals("-") ? null : Checks.text(text, 500);
        yield draft(c);
      }
      case EXERCISE_SEARCH -> {
        c.data().search = Checks.text(text.trim(), 80);
        yield picker(c, 0);
      }
      case CUSTOM_NAME -> {
        c.data().customName = Checks.name(text);
        c.flow(Flow.CUSTOM_TYPE);
        yield Screen.title(t("Choose metrics for ") + c.data().customName)
            .button(t("Strength · kg × reps"), "workout:type:STRENGTH")
            .button(t("Bodyweight · reps (+kg)"), "workout:type:BODYWEIGHT")
            .button(t("Cardio · seconds + km"), "workout:type:CARDIO")
            .button(t("Timed · seconds"), "workout:type:TIMED")
            .button(t("← Exercises"), "workout:pick:0")
            .home()
            .build();
      }
      case SET_PLAN -> {
        int i = c.data().targetPosition;
        var old = c.data().targets.get(i);
        var type = exercises.get(c.uid(), old.exerciseId()).metricType;
        var plan = new ArrayList<SetPlan>();
        if (!text.strip().equals("-"))
          for (String line : text.lines().filter(v -> !v.isBlank()).toList()) {
            var v = SetEntry.parse(type, 0, line, false);
            plan.add(
                new SetPlan(
                    v.repetitions(), v.weight(), v.durationSeconds(), v.distance(), v.warmup()));
          }
        var target =
            new Target(
                old.exerciseId(),
                old.sets(),
                old.reps(),
                old.weight(),
                old.restSeconds(),
                old.durationSeconds(),
                old.distance(),
                plan);
        Plans.validate(type, target);
        c.data().targets.set(i, target);
        yield item(c, i);
      }
      case TARGETS -> {
        String[] v = text.strip().split("\s+");
        int i = c.data().targetPosition;
        var old = c.data().targets.get(i);
        var type = exercises.get(c.uid(), old.exerciseId()).metricType;
        if (v.length != (type == MetricType.TIMED ? 3 : 4))
          throw new DomainException("Check the target format shown above.");
        Integer sets = integer(v[0]), reps = null, duration = null, rest = integer(v[v.length - 1]);
        java.math.BigDecimal weight = null, distance = null;
        if (type == MetricType.TIMED || type == MetricType.CARDIO) {
          duration = integer(v[1]);
          if (type == MetricType.CARDIO) distance = v[2].equals("-") ? null : Checks.decimal(v[2]);
        } else {
          reps = integer(v[1]);
          weight = v[2].equals("-") ? null : Checks.decimal(v[2]);
        }
        Checks.range(sets, 1, 100, t("Sets"));
        Checks.range(reps, 0, 1000, t("Repetitions"));
        Checks.range(weight, 0, 2000, t("Weight"));
        Checks.range(rest, 0, 3600, t("Rest"));
        Checks.scale(weight, 3, t("Weight"));
        var target =
            new Target(old.exerciseId(), sets, reps, weight, rest, duration, distance, old.plan());
        Plans.validate(type, target);
        c.data().targets.set(i, target);
        yield draft(c);
      }
      default -> throw new DomainException(t("Use the workout buttons."));
    };
  }

  private Integer integer(String value) {
    return value.equals("-") ? null : Checks.integer(value);
  }

  private String targets(Integer sets, Integer reps, java.math.BigDecimal weight, Integer rest) {
    return (sets == null ? "" : " · " + sets + t(" sets"))
        + (reps == null ? "" : " × " + reps)
        + (weight == null ? "" : " @ " + Format.n(weight) + t(" kg"))
        + (rest == null ? "" : t(" · rest ") + rest + t("s"));
  }
}
