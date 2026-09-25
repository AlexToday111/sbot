package dev.workout.telegram.handler;

import static dev.workout.common.I18n.t;
import static dev.workout.telegram.message.Screen.b;

import dev.workout.common.*;
import dev.workout.exercise.application.ExerciseService;
import dev.workout.telegram.callback.CallbackHandler;
import dev.workout.telegram.message.*;
import dev.workout.workout.application.*;
import dev.workout.workout.application.WorkoutDtos.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class TrainingHandler implements CallbackHandler {
  private final SessionService sessions;
  private final SessionHandler sessionUi;
  private final TrainingService training;
  private final PlanningService planning;
  private final TemplateService templates;
  private final ExerciseService exercises;
  private final CsvImportService imports;
  private final HistoryTransferService history;
  private final Clock clock;

  public TrainingHandler(
      SessionService sessions,
      SessionHandler sessionUi,
      TrainingService training,
      PlanningService planning,
      TemplateService templates,
      ExerciseService exercises,
      CsvImportService imports,
      HistoryTransferService history,
      Clock clock) {
    this.sessions = sessions;
    this.sessionUi = sessionUi;
    this.training = training;
    this.planning = planning;
    this.templates = templates;
    this.exercises = exercises;
    this.imports = imports;
    this.history = history;
    this.clock = clock;
  }

  public String prefix() {
    return "train";
  }

  public Screen handle(Interaction c, String[] p) {
    c.flow(Flow.HOME);
    return switch (p[1]) {
      case "menu" -> menu(c);
      case "settings" -> settings(c);
      case "prefs" ->
          prompt(
              c,
              "prefs",
              t("Enter: weekly_goal weight_step min_reps max_reps\nExample: 3 2.5 8 12"),
              "train:settings");
      case "notifications" -> {
        var v = planning.preferences(c.uid());
        planning.preferences(
            c.uid(),
            new PlanningService.Preferences(
                v.weeklyGoal(), !v.restNotifications(), v.weightStep(), v.repMin(), v.repMax()));
        yield settings(c);
      }
      case "pause" ->
          sessionUi.view(c, training.pause(c.uid(), id(p, 2), Boolean.parseBoolean(p[3])));
      case "rest" ->
          sessionUi.view(c, training.rest(c.uid(), id(p, 2), Integer.parseInt(p[3]), false));
      case "extend" -> sessionUi.view(c, training.rest(c.uid(), id(p, 2), 30, true));
      case "warmup" -> {
        c.data().warmup = !c.data().warmup;
        yield sessionUi.manual(c);
      }
      case "finishat" -> {
        c.data().sessionId = id(p, 2);
        yield prompt(
            c,
            "finishat",
            t(
                "Enter actual finish: YYYY-MM-DD HH:mm in your timezone. The time must follow the last saved set."),
            "session:view:" + p[2]);
      }
      case "manage" -> manage(c, id(p, 2), id(p, 3));
      case "add" -> {
        c.data().sessionId = id(p, 2);
        c.data().manageExerciseId = null;
        yield exercisePicker(c, 0);
      }
      case "replace" -> {
        c.data().sessionId = id(p, 2);
        c.data().manageExerciseId = id(p, 3);
        yield exercisePicker(c, 0);
      }
      case "pick" -> exercisePicker(c, Integer.parseInt(p[2]));
      case "chosen" ->
          sessionUi.view(
              c,
              training.changeExercise(
                  c.uid(), c.data().sessionId, c.data().manageExerciseId, id(p, 2), null, null));
      case "move" ->
          sessionUi.view(
              c,
              training.changeExercise(
                  c.uid(), id(p, 2), id(p, 3), null, Integer.parseInt(p[4]), null));
      case "skip" ->
          sessionUi.view(
              c,
              training.changeExercise(
                  c.uid(), id(p, 2), id(p, 3), null, null, Boolean.parseBoolean(p[4])));
      case "sets" -> sets(c, id(p, 2), id(p, 3), Integer.parseInt(p[4]));
      case "edit" -> {
        var s = sessions.get(c.uid(), id(p, 2));
        var e = find(s, id(p, 3));
        var x =
            e.sets().stream()
                .filter(a -> a.id() == id(p, 4))
                .findFirst()
                .orElseThrow(DomainException::missing);
        c.data().sessionId = s.id();
        c.data().sessionExerciseId = e.id();
        c.data().editingSetId = x.id();
        c.data().warmup = x.warmup();
        yield sessionUi.manual(c);
      }
      case "deleteask" ->
          Screen.title(
                  t(
                      "Delete this recorded set? Records and progress will be recalculated. Deleting the last set of a completed workout cancels it."))
              .danger(t("Delete set"), "train:delete:" + p[2] + ":" + p[3] + ":" + p[4])
              .navigation("train:sets:" + p[2] + ":" + p[3] + ":0")
              .build();
      case "delete" -> {
        sessions.undo(c.uid(), id(p, 2), id(p, 4));
        yield sets(c, id(p, 2), id(p, 3), 0);
      }
      case "compare" -> comparison(c, id(p, 2), Integer.parseInt(p[3]));
      case "savetemplate" -> {
        c.data().sessionId = id(p, 2);
        yield prompt(
            c,
            "savetemplate",
            t(
                "Enter a name for the new template. Skipped exercises are excluded; the original template is kept."),
            "session:view:" + p[2]);
      }
      case "importpreview" -> importPreview(c);
      case "importcopy" -> importSave(c, null, true);
      case "importreplace" -> templatePicker(c, "importtarget", Integer.parseInt(p[2]));
      case "importtarget" -> {
        templates.get(c.uid(), id(p, 2));
        c.data().importReplaceId = id(p, 2);
        yield Screen.title(
                t(
                    "Replace this template with the CSV contents? History and active workouts are kept."))
            .success(t("Confirm replacement"), "train:importconfirm")
            .navigation("train:importpreview")
            .build();
      }
      case "importconfirm" -> importSave(c, c.data().importReplaceId, true);
      case "historyimport" -> {
        c.flow(Flow.HISTORY_IMPORT);
        yield Screen.title(
                t(
                    "Send history CSV, up to 64 KiB. Use the separate history format documented in README. Review before saving."))
            .navigation("history:current")
            .build();
      }
      case "historypreview" -> {
        var entries = history.preview(c.uid(), Base64.getDecoder().decode(c.data().historyCsv));
        yield Screen.title(
                t("Workouts to import: ")
                    + entries.size()
                    + t("\nAll rows were validated. Save these past workouts?"))
            .success(t("Save history"), "train:historysave")
            .navigation("history:current")
            .build();
      }
      case "historysave" -> {
        var saved = history.importCsv(c.uid(), Base64.getDecoder().decode(c.data().historyCsv));
        c.data().historyCsv = null;
        yield Screen.title(t("Imported workouts: ") + saved.size())
            .navigation("history:current")
            .build();
      }
      case "export" ->
          Screen.title(t("Workout history CSV · ") + p[2])
              .attachment(
                  new Screen.Attachment(
                      "history-" + p[2] + ".csv", history.export(c.uid(), YearMonth.parse(p[2]))))
              .navigation("history:current")
              .build();
      case "historical" -> templatePicker(c, "historicaltemplate", Integer.parseInt(p[2]));
      case "historicaltemplate" -> {
        var v = templates.get(c.uid(), id(p, 2));
        c.data().historicalTemplateId = v.id();
        c.data().historicalExercises.clear();
        c.data().historyPosition = 0;
        yield prompt(
            c,
            "historytime",
            t(
                "Enter past date, start and finish in your timezone:\nYYYY-MM-DD HH:mm HH:mm\nExample: 2026-01-10 18:00 19:00\nFor an overnight workout enter the full finish date too."),
            "history:current");
      }
      case "historicalsave" -> {
        var v = templates.get(c.uid(), c.data().historicalTemplateId);
        yield SessionScreens.summary(
            training.historical(
                c.uid(),
                c.key("historical"),
                new TrainingService.HistoricalInput(
                    v.name(),
                    Instant.parse(c.data().historyStart),
                    Instant.parse(c.data().historyEnd),
                    c.data().historicalExercises)));
      }
      case "schedule" -> schedule(c, Integer.parseInt(p[2]));
      case "scheduleadd" -> {
        c.data().scheduleId = null;
        yield templatePicker(c, "scheduletarget", Integer.parseInt(p[2]));
      }
      case "scheduletarget" -> {
        c.data().planningTemplateId = id(p, 2);
        templates.get(c.uid(), c.data().planningTemplateId);
        yield schedulePrompt(c);
      }
      case "appointment" -> appointment(c, id(p, 2));
      case "reschedule" -> {
        var a = appointmentData(c, id(p, 2));
        c.data().scheduleId = a.id();
        c.data().planningTemplateId = a.templateId();
        yield schedulePrompt(c);
      }
      case "schedulecancelask" ->
          Screen.title(t("Remove this appointment and its future repetitions?"))
              .danger(t("Remove appointment"), "train:schedulecancel:" + p[2])
              .navigation("train:schedule:0")
              .build();
      case "schedulecancel" -> {
        planning.cancel(c.uid(), id(p, 2));
        yield schedule(c, 0);
      }
      case "suggest" -> {
        var suggestion = planning.suggest(c.uid(), id(p, 2));
        c.data().templateId = id(p, 2);
        c.data().name = suggestion.template().name();
        c.data().description = suggestion.template().description();
        c.data().targets = new ArrayList<>(suggestion.template().exercises());
        var b = Screen.title(t("Next workout suggestion\n\n") + suggestion.reason());
        var rule = planning.preferences(c.uid());
        b.line(
            t("Repetition range: ")
                + rule.repMin()
                + "–"
                + rule.repMax()
                + t(" · Weight step: ")
                + Format.n(rule.weightStep())
                + t(" kg"));
        for (var x : c.data().targets)
          b.line(exercises.get(c.uid(), x.exerciseId()).name + ": " + Format.targets(x));
        yield b.success(t("Apply to template"), "workout:save")
            .navigation("workout:view:" + p[2])
            .build();
      }
      default -> throw new DomainException("Unknown training action.");
    };
  }

  private Screen prompt(Interaction c, String action, String text, String back) {
    c.data().trainingAction = action;
    c.flow(Flow.TRAIN_INPUT);
    return Screen.title(text).navigation(back).build();
  }

  private Screen menu(Interaction c) {
    var g = planning.goal(c.uid());
    return Screen.title(
            t("Training plan\n\nWeekly goal: ")
                + g.completed()
                + " / "
                + g.target()
                + (g.next() == null
                    ? ""
                    : t("\nNext: ") + g.next().name() + " · " + g.next().localStart()))
        .button(t("Schedule"), "train:schedule:0")
        .button(t("Training settings"), "train:settings")
        .home()
        .build();
  }

  private Screen settings(Interaction c) {
    var p = planning.preferences(c.uid());
    return Screen.title(
            t("Training settings\n\nWeekly goal: ")
                + p.weeklyGoal()
                + t("\nWeight step: ")
                + Format.n(p.weightStep())
                + t(" kg")
                + t("\nRepetition range: ")
                + p.repMin()
                + "–"
                + p.repMax())
        .button(t("Edit goal and progression"), "train:prefs")
        .button(
            t(p.restNotifications() ? "Rest notifications: on" : "Rest notifications: off"),
            "train:notifications")
        .navigation("train:menu")
        .build();
  }

  private ExerciseView find(SessionView s, long eid) {
    return s.exercises().stream()
        .filter(x -> x.id() == eid)
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  private Screen manage(Interaction c, long sid, long eid) {
    var s = sessions.get(c.uid(), sid);
    var e = find(s, eid);
    return Screen.title(e.name())
        .row(
            b(t("Replace exercise"), "train:replace:" + sid + ":" + eid),
            b(
                t(e.skipped() ? "Unskip" : "Skip exercise"),
                "train:skip:" + sid + ":" + eid + ":" + !e.skipped()))
        .row(
            e.position() > 0
                ? b(t("↑ Move up"), "train:move:" + sid + ":" + eid + ":" + (e.position() - 1))
                : null,
            e.position() + 1 < s.exercises().size()
                ? b(t("↓ Move down"), "train:move:" + sid + ":" + eid + ":" + (e.position() + 1))
                : null)
        .button(t("+ Add exercise"), "train:add:" + sid)
        .button(t("Save as new template"), "train:savetemplate:" + sid)
        .navigation("session:view:" + sid)
        .build();
  }

  private Screen exercisePicker(Interaction c, int page) {
    var list = exercises.search(c.uid(), "", page);
    var b = Screen.title(t("Choose an exercise."));
    list.forEach(e -> b.button(e.name, "train:chosen:" + e.id));
    return b.pages(page, list.size() == 8, "train:pick:")
        .navigation("session:view:" + c.data().sessionId)
        .build();
  }

  private Screen sets(Interaction c, long sid, long eid, int page) {
    var s = sessions.get(c.uid(), sid);
    var e = find(s, eid);
    var b = Screen.title(e.name() + t("\nChoose a set to edit or delete."));
    int from = Math.min(Math.max(0, page) * 6, e.sets().size()),
        to = Math.min(from + 6, e.sets().size());
    for (var x : e.sets().subList(from, to))
      b.row(
          b(x.number() + ". " + Format.set(x), "train:edit:" + sid + ":" + eid + ":" + x.id()),
          b(t("Delete"), "train:deleteask:" + sid + ":" + eid + ":" + x.id()));
    return b.pages(page, to < e.sets().size(), "train:sets:" + sid + ":" + eid + ":")
        .navigation("session:view:" + sid)
        .build();
  }

  private Screen comparison(Interaction c, long sid, int page) {
    var rows = training.comparison(c.uid(), sid);
    var session = sessions.get(c.uid(), sid);
    var screen = Screen.title(t("Plan vs actual · working sets"));
    int from = Math.min(Math.max(0, page) * 5, rows.size()), to = Math.min(from + 5, rows.size());
    for (int i = from; i < to; i++) {
      var x = rows.get(i);
      var e = session.exercises().get(i);
      var working = e.sets().stream().filter(v -> !v.warmup()).toList();
      screen.line(
          "\n"
              + x.exercise()
              + ": "
              + x.completed()
              + " / "
              + (x.planned() == 0 ? t("no target") : x.planned())
              + (x.skipped() ? " · " + t("Skipped") : "")
              + t(" · Extra sets: ")
              + x.extra());
      screen.line(
          t("Plan: ")
              + Format.targets(
                  new Target(
                      e.exerciseId(),
                      e.targetSets(),
                      e.targetReps(),
                      e.targetWeight(),
                      e.restSeconds(),
                      e.targetDuration(),
                      e.targetDistance(),
                      e.plan())));
      if (e.metricType() == dev.workout.exercise.domain.MetricType.STRENGTH)
        screen.line(
            t("Volume: ")
                + Format.n(x.currentVolume())
                + t(" kg")
                + t(" · Previous: ")
                + Format.n(x.previousVolume()));
      else if (e.metricType() == dev.workout.exercise.domain.MetricType.BODYWEIGHT)
        screen.line(
            t("Actual repetitions: ")
                + working.stream()
                    .mapToInt(v -> v.repetitions() == null ? 0 : v.repetitions())
                    .sum());
      else
        screen.line(
            t("Actual duration: ")
                + Format.duration(
                    working.stream()
                        .mapToInt(v -> v.durationSeconds() == null ? 0 : v.durationSeconds())
                        .sum())
                + (e.metricType() == dev.workout.exercise.domain.MetricType.CARDIO
                    ? t(" · Distance: ")
                        + Format.n(
                            working.stream()
                                .map(SetView::distance)
                                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                        + t(" km")
                    : ""));
    }
    return screen
        .pages(page, to < rows.size(), "train:compare:" + sid + ":")
        .navigation("session:view:" + sid)
        .build();
  }

  private Screen importPreview(Interaction c) {
    var d = c.data().csvDraft;
    if (d == null) throw DomainException.missing();
    var b = Screen.title(d.name() + t("\nCSV preview — no changes saved."));
    for (var x : d.rows())
      b.line(x.exercise() + " · " + t(x.type().name().toLowerCase(Locale.ROOT)));
    if (imports.duplicate(c.uid(), d) != null)
      b.line(
          t(
              "This file was imported before. Choose whether to create a copy or replace a template."));
    return b.success(t("Create new copy"), "train:importcopy")
        .button(t("Replace existing template"), "train:importreplace:0")
        .navigation("workout:list:0")
        .build();
  }

  private Screen importSave(Interaction c, Long replace, boolean copy) {
    if (c.data().csvDraft == null) throw DomainException.missing();
    var saved = imports.save(c.uid(), c.data().csvDraft, replace, copy);
    c.data().csvDraft = null;
    return Screen.title(t("Workout saved: ") + saved.name())
        .primary(t("▶ Start"), "session:start:" + saved.id())
        .navigation("workout:view:" + saved.id())
        .build();
  }

  private Screen templatePicker(Interaction c, String action, int page) {
    var list = templates.list(c.uid(), page);
    var b = Screen.title(t("Choose a workout."));
    for (var x : list) b.button(x.name(), "train:" + action + ":" + x.id());
    String prefix =
        switch (action) {
          case "importtarget" -> "train:importreplace:";
          case "scheduletarget" -> "train:scheduleadd:";
          default -> "train:historical:";
        };
    return b.pages(page, list.size() == 8, prefix).home().build();
  }

  private Screen schedule(Interaction c, int page) {
    var list = planning.schedule(c.uid());
    var b = Screen.title(t("Schedule · ") + c.user().timezone);
    int from = Math.min(Math.max(0, page) * 8, list.size()), to = Math.min(from + 8, list.size());
    for (var a : list.subList(from, to))
      b.button(a.localStart() + " · " + a.name(), "train:appointment:" + a.id());
    return b.pages(page, to < list.size(), "train:schedule:")
        .primary(t("+ Planned workout"), "train:scheduleadd:0")
        .navigation("train:menu")
        .build();
  }

  private PlanningService.Appointment appointmentData(Interaction c, long id) {
    return planning.schedule(c.uid()).stream()
        .filter(a -> a.id() == id)
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  private Screen appointment(Interaction c, long id) {
    var a = appointmentData(c, id);
    return Screen.title(
            a.name()
                + "\n"
                + a.localStart()
                + " · "
                + c.user().timezone
                + t("\nRepeat weeks (0 = once): ")
                + a.repeatWeeks()
                + "\n"
                + t(a.reminders() ? "Reminders: on" : "Reminders: off"))
        .primary(t("▶ Start"), "session:start:" + a.templateId())
        .button(t("Reschedule / edit"), "train:reschedule:" + id)
        .button(t("Remove appointment"), "train:schedulecancelask:" + id)
        .navigation("train:schedule:0")
        .build();
  }

  private Screen schedulePrompt(Interaction c) {
    return prompt(
        c,
        "schedule",
        t(
                "Enter: YYYY-MM-DD HH:mm repeat_weeks reminders\nExample: 2026-10-01 18:00 2 off\nRepeat: 0 once, 1 weekly, 2 every other week (up to 4). Reminders: on/off. Timezone: ")
            + c.user().timezone,
        "train:schedule:0");
  }

  private Screen historyExercise(Interaction c) {
    var template = templates.get(c.uid(), c.data().historicalTemplateId);
    if (c.data().historyPosition >= template.exercises().size()) {
      c.flow(Flow.HOME);
      return Screen.title(t("Save this past workout? Only the sets you entered will be recorded."))
          .success(t("Save history"), "train:historicalsave")
          .navigation("history:current")
          .build();
    }
    var e = template.exercises().get(c.data().historyPosition);
    return prompt(
        c,
        "historysets",
        e.name()
            + "\n\n"
            + SetEntry.help(e.metricType())
            + t(
                "\nOne actual set per line. Prefix w for warmup, r for working. Enter - to skip this exercise."),
        "history:current");
  }

  public boolean accepts(Flow flow) {
    return flow == Flow.TRAIN_INPUT;
  }

  public Screen text(Interaction c, String text) {
    try {
      return switch (c.data().trainingAction) {
        case "prefs" -> {
          var v = text.strip().split("\\s+");
          if (v.length != 4)
            throw new DomainException(
                "Enter four values: weekly goal, weight step, min reps, max reps.");
          var old = planning.preferences(c.uid());
          planning.preferences(
              c.uid(),
              new PlanningService.Preferences(
                  Checks.integer(v[0]),
                  old.restNotifications(),
                  Checks.decimal(v[1]),
                  Checks.integer(v[2]),
                  Checks.integer(v[3])));
          c.flow(Flow.HOME);
          yield settings(c);
        }
        case "finishat" -> {
          var time =
              LocalDateTime.parse(text.strip(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                  .atZone(ZoneId.of(c.user().timezone))
                  .toInstant();
          var result = training.finishAt(c.uid(), c.data().sessionId, time);
          c.flow(Flow.HOME);
          yield SessionScreens.summary(result);
        }
        case "savetemplate" -> {
          var saved = training.saveAsTemplate(c.uid(), c.data().sessionId, Checks.name(text));
          c.flow(Flow.HOME);
          yield Screen.title(t("Workout saved: ") + saved.name())
              .navigation("workout:view:" + saved.id())
              .build();
        }
        case "schedule" -> {
          var v = text.strip().split("\\s+");
          if (v.length != 4 || !List.of("on", "off").contains(v[3]))
            throw new DomainException("Enter date, time, repeat weeks and on/off.");
          var start = LocalDateTime.parse(v[0] + "T" + v[1]);
          planning.schedule(
              c.uid(),
              c.data().scheduleId,
              c.data().planningTemplateId,
              start,
              Checks.integer(v[2]),
              v[3].equals("on"));
          c.flow(Flow.HOME);
          yield schedule(c, 0);
        }
        case "historytime" -> {
          var v = text.strip().split("\\s+");
          if (v.length != 3 && v.length != 4)
            throw new DomainException("Enter past date, start time and finish time.");
          ZoneId zone = ZoneId.of(c.user().timezone);
          c.data().historyStart =
              LocalDateTime.parse(v[0] + "T" + v[1]).atZone(zone).toInstant().toString();
          c.data().historyEnd =
              LocalDateTime.parse(v.length == 3 ? v[0] + "T" + v[2] : v[2] + "T" + v[3])
                  .atZone(zone)
                  .toInstant()
                  .toString();
          if (!Instant.parse(c.data().historyEnd).isAfter(Instant.parse(c.data().historyStart))
              || Instant.parse(c.data().historyEnd).isAfter(clock.instant()))
            throw new DomainException(
                "Provide past start and finish times in chronological order.");
          yield historyExercise(c);
        }
        case "historysets" -> {
          var e =
              templates
                  .get(c.uid(), c.data().historicalTemplateId)
                  .exercises()
                  .get(c.data().historyPosition);
          var values = new ArrayList<SetInput>();
          if (!text.strip().equals("-"))
            for (String line : text.lines().filter(s -> !s.isBlank()).toList())
              values.add(SetEntry.parse(e.metricType(), 0, line, false));
          if (values.size() > 100) throw new DomainException("Maximum 100 sets per exercise.");
          c.data()
              .historicalExercises
              .add(new TrainingService.HistoricalExercise(e.exerciseId(), values));
          c.data().historyPosition++;
          yield historyExercise(c);
        }
        default -> throw new DomainException("Choose an action using the buttons below.");
      };
    } catch (java.time.DateTimeException ex) {
      throw new DomainException("Check the date and time format shown above.");
    }
  }

  private static long id(String[] p, int i) {
    return Long.parseLong(p[i]);
  }
}
