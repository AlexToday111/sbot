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
                    "Send a UTF-8 .csv document (up to 64 KiB).\nColumns: workout,exercise,metric_type,sets,reps,weight,rest_seconds\nOne row per exercise, 1–30 exercises. The workout name must be the same in every row.\n\nThe file replaces your current draft. Review it and tap Save workout. /cancel cancels input."))
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
        t.exercises()
            .forEach(
                e ->
                    c.data()
                        .targets
                        .add(
                            new Target(
                                e.exerciseId(),
                                e.targetSets(),
                                e.targetReps(),
                                e.targetWeight(),
                                e.restSeconds())));
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
              .button(t("Delete template"), "workout:delete:" + p[2])
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
      case "target" -> {
        c.data().targetPosition = Integer.parseInt(p[2]);
        c.flow(Flow.TARGETS);
        yield Screen.title(
                t(
                    "Optional targets\n\nEnter: sets reps weight rest_seconds\nExample: 4 8 70 90\nUse - for any value you want to leave empty.\nExample: 3 12 - 60\n\nWeight is kg. Rest is a reference, not a timer."))
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
    if (page > 0) screen.button(t("← Previous"), "workout:list:" + (page - 1));
    if (items.size() == 8) screen.button(t("Next →"), "workout:list:" + (page + 1));
    return screen
        .button(t("+ Create workout"), "workout:new")
        .button(t("Import CSV"), "workout:import")
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
              + targets(e.targetSets(), e.targetReps(), e.targetWeight(), e.restSeconds()));
    return screen
        .button(t("▶ Start"), "session:start:" + t.id())
        .button(t("Edit"), "workout:edit:" + t.id())
        .button(t("Delete"), "workout:deleteask:" + t.id())
        .button(t("← Workouts"), "workout:list:0")
        .home()
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
      if (i > 0)
        screen.row(
            b("↑ " + (i + 1), "workout:up:" + i),
            b(t("Targets ") + (i + 1), "workout:target:" + i),
            b(t("Remove ") + (i + 1), "workout:remove:" + i));
      else screen.row(b(t("Targets 1"), "workout:target:0"), b(t("Remove 1"), "workout:remove:0"));
    }
    if (c.data().targets.isEmpty()) screen.line(t("No exercises yet."));
    return screen
        .button(t("+ Add exercise"), "workout:all")
        .button(t("Rename"), "workout:name")
        .button(t("Description"), "workout:description")
        .button(t("✓ Save workout"), "workout:save")
        .home()
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
    if (page > 0) screen.button(t("← Previous"), "workout:pick:" + (page - 1));
    if (list.size() == 8) screen.button(t("Next →"), "workout:pick:" + (page + 1));
    return screen
        .button(t("Search"), "workout:search")
        .button(t("+ Custom exercise"), "workout:custom")
        .button(t("← Workout"), "workout:draft")
        .home()
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
            Flow.TARGETS)
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
      case TARGETS -> {
        String[] v = text.trim().split("\\s+");
        if (v.length != 4)
          throw new DomainException(
              t("Enter four values: sets reps weight rest_seconds. Use - to skip."));
        Integer sets = integer(v[0]), reps = integer(v[1]), rest = integer(v[3]);
        var weight = v[2].equals("-") ? null : Checks.decimal(v[2]);
        Checks.range(sets, 1, 100, t("Sets"));
        Checks.range(reps, 0, 1000, t("Repetitions"));
        Checks.range(weight, 0, 2000, t("Weight"));
        Checks.range(rest, 0, 3600, t("Rest"));
        Checks.scale(weight, 3, t("Weight"));
        int i = c.data().targetPosition;
        c.data()
            .targets
            .set(i, new Target(c.data().targets.get(i).exerciseId(), sets, reps, weight, rest));
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
