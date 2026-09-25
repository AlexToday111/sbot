package dev.workout.workout.application;

import dev.workout.analytics.application.RecordService;
import dev.workout.common.*;
import dev.workout.exercise.application.ExerciseService;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.WorkoutDtos.*;
import dev.workout.workout.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TrainingService {
  private final SessionRepository sessions;
  private final UserService users;
  private final ExerciseService exercises;
  private final TemplateService templates;
  private final RecordService records;
  private final Clock clock;

  public TrainingService(
      SessionRepository sessions,
      UserService users,
      ExerciseService exercises,
      TemplateService templates,
      RecordService records,
      Clock clock) {
    this.sessions = sessions;
    this.users = users;
    this.exercises = exercises;
    this.templates = templates;
    this.records = records;
    this.clock = clock;
  }

  public WorkoutSession owned(long uid, long sid) {
    users.lock(uid);
    var s = sessions.findById(sid).orElseThrow(DomainException::missing);
    if (s.userId != uid) throw DomainException.missing();
    return s;
  }

  private void active(WorkoutSession s) {
    if (s.status != WorkoutSession.Status.ACTIVE)
      throw new DomainException("This workout has ended. Open history or start another workout.");
  }

  private SessionExercise exercise(WorkoutSession s, long id) {
    return s.exercises.stream()
        .filter(e -> e.id == id)
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  public SessionView editSet(long uid, long sid, long setId, SetInput input) {
    var s = owned(uid, sid);
    if (s.status == WorkoutSession.Status.CANCELLED)
      throw new DomainException("Cancelled workouts cannot be edited.");
    if (input == null) throw new DomainException("Provide set metrics.");
    var e = exercise(s, input.sessionExerciseId());
    var set =
        e.sets.stream()
            .filter(x -> x.id == setId && !x.voided)
            .findFirst()
            .orElseThrow(DomainException::missing);
    SetValidation.validate(e.metricType, input);
    set.weight = input.weight();
    set.repetitions = input.repetitions();
    set.durationSeconds = input.durationSeconds();
    set.distance = input.distance();
    set.rpe = input.rpe();
    set.notes = input.notes();
    set.warmup = input.warmup();
    sessions.flush();
    if (s.status == WorkoutSession.Status.COMPLETED) records.rebuild(uid);
    return WorkoutMapper.session(s, clock.instant());
  }

  public SessionView pause(long uid, long sid, boolean paused) {
    var s = owned(uid, sid);
    active(s);
    if (paused && s.pausedAt == null) {
      s.pausedAt = clock.instant();
      s.restUntil = null;
    }
    if (!paused && s.pausedAt != null) {
      s.pausedSeconds += Duration.between(s.pausedAt, clock.instant()).getSeconds();
      s.pausedAt = null;
    }
    return WorkoutMapper.session(s, clock.instant());
  }

  public SessionView rest(long uid, long sid, int seconds, boolean extend) {
    var s = owned(uid, sid);
    active(s);
    Checks.range(seconds, 0, 3600, "Rest");
    if (s.pausedAt != null && seconds > 0)
      throw new DomainException("Resume the workout before recording a set.");
    s.restUntil =
        seconds == 0
            ? null
            : (extend && s.restUntil != null && s.restUntil.isAfter(clock.instant())
                    ? s.restUntil
                    : clock.instant())
                .plusSeconds(seconds);
    return WorkoutMapper.session(s, clock.instant());
  }

  public SessionView changeExercise(
      long uid, long sid, Long itemId, Long replacementId, Integer position, Boolean skipped) {
    var s = owned(uid, sid);
    active(s);
    SessionExercise e;
    if (itemId == null) {
      if (s.exercises.size() >= 30) throw new DomainException("Maximum 30 exercises per workout.");
      e = new SessionExercise();
      e.position = s.exercises.size();
      s.exercises.add(e);
    } else e = exercise(s, itemId);
    if (replacementId != null) {
      if (!e.recordedSets().isEmpty())
        throw new DomainException(
            "An exercise with recorded sets cannot be replaced. Add another exercise instead.");
      var source = exercises.get(uid, replacementId);
      e.exerciseId = source.id;
      e.name = source.name;
      e.metricType = source.metricType;
      e.targetSets = null;
      e.targetReps = null;
      e.targetWeight = null;
      e.targetDuration = null;
      e.targetDistance = null;
      e.setPlan = "[]";
      e.skipped = false;
    } else if (itemId == null) throw new DomainException("Choose an exercise.");
    if (skipped != null) e.skipped = skipped;
    if (position != null) {
      Checks.range(position, 0, s.exercises.size() - 1, "Position");
      s.exercises.remove(e);
      s.exercises.add(position, e);
      for (int i = 0; i < s.exercises.size(); i++) s.exercises.get(i).position = i;
    }
    s.currentPosition = e.position;
    s.restUntil = null;
    sessions.flush();
    return WorkoutMapper.session(s, clock.instant());
  }

  public SessionView finishAt(long uid, long sid, Instant finished) {
    var s = owned(uid, sid);
    active(s);
    if (finished == null || finished.isBefore(s.startedAt) || finished.isAfter(clock.instant()))
      throw new DomainException("Finish time must be between the start and now.");
    if (s.exercises.stream().allMatch(e -> e.recordedSets().isEmpty()))
      throw new DomainException("No sets recorded. Cancel this empty workout instead.");
    Instant last =
        s.exercises.stream()
            .flatMap(e -> e.recordedSets().stream())
            .map(x -> x.createdAt)
            .max(Comparator.naturalOrder())
            .orElse(s.startedAt);
    if (finished.isBefore(last))
      throw new DomainException("Finish time cannot precede the last recorded set.");
    if (s.pausedAt != null) {
      s.pausedSeconds += Math.max(0, Duration.between(s.pausedAt, finished).getSeconds());
      s.pausedAt = null;
    }
    s.pausedSeconds =
        Math.min(s.pausedSeconds, Duration.between(s.startedAt, finished).getSeconds());
    s.finishedAt = finished;
    s.status = WorkoutSession.Status.COMPLETED;
    s.restUntil = null;
    records.onCompleted(s);
    return WorkoutMapper.session(s, clock.instant());
  }

  public record HistoricalExercise(long exerciseId, List<SetInput> sets) {}

  public record HistoricalInput(
      String name,
      Instant startedAt,
      Instant finishedAt,
      List<HistoricalExercise> exercises,
      long pausedSeconds) {
    public HistoricalInput(
        String name, Instant startedAt, Instant finishedAt, List<HistoricalExercise> exercises) {
      this(name, startedAt, finishedAt, exercises, 0);
    }
  }

  public SessionView historical(long uid, String key, HistoricalInput input) {
    users.lock(uid);
    if (key == null || key.isBlank() || key.length() > 100)
      throw new DomainException("Provide an Idempotency-Key of 1 to 100 characters.");
    var previous = sessions.findByUserIdAndRequestKey(uid, "history:" + key);
    if (previous.isPresent())
      throw new DomainException(409, "This history import was already saved.");
    if (key.length() > 92)
      throw new DomainException("History request key must not exceed 92 characters.");
    if (input == null
        || input.startedAt() == null
        || input.finishedAt() == null
        || !input.finishedAt().isAfter(input.startedAt())
        || input.finishedAt().isAfter(clock.instant()))
      throw new DomainException("Provide past start and finish times in chronological order.");
    if (input.pausedSeconds() < 0
        || input.pausedSeconds()
            > Duration.between(input.startedAt(), input.finishedAt()).getSeconds())
      throw new DomainException("Paused seconds must be between zero and the workout duration.");
    if (input.exercises() == null || input.exercises().isEmpty() || input.exercises().size() > 30)
      throw new DomainException("A workout needs 1 to 30 exercises.");
    var s = new WorkoutSession();
    s.userId = uid;
    s.name = Checks.name(input.name());
    s.startedAt = input.startedAt();
    s.finishedAt = input.finishedAt();
    s.pausedSeconds = input.pausedSeconds();
    s.status = WorkoutSession.Status.COMPLETED;
    s.requestKey = "history:" + key;
    int total = 0;
    for (var item : input.exercises()) {
      var source = exercises.get(uid, item.exerciseId());
      var e = new SessionExercise();
      e.exerciseId = source.id;
      e.name = source.name;
      e.metricType = source.metricType;
      e.position = s.exercises.size();
      s.exercises.add(e);
      if (item.sets() == null || item.sets().size() > 100)
        throw new DomainException("Maximum 100 sets per exercise.");
      for (var values : item.sets()) {
        SetValidation.validate(e.metricType, values);
        var x = new ExerciseSet();
        x.setNumber = e.sets.size() + 1;
        x.requestKey = "import:" + x.setNumber;
        x.createdAt = input.finishedAt();
        x.weight = values.weight();
        x.repetitions = values.repetitions();
        x.durationSeconds = values.durationSeconds();
        x.distance = values.distance();
        x.rpe = values.rpe();
        x.notes = values.notes();
        x.warmup = values.warmup();
        e.sets.add(x);
        total++;
      }
    }
    if (total == 0)
      throw new DomainException("No sets recorded. Cancel this empty workout instead.");
    sessions.saveAndFlush(s);
    records.onCompleted(s);
    return WorkoutMapper.session(s, clock.instant());
  }

  public record Comparison(
      String exercise,
      int planned,
      int completed,
      int extra,
      boolean skipped,
      BigDecimal previousVolume,
      BigDecimal currentVolume) {}

  public List<Comparison> comparison(long uid, long sid) {
    var s = owned(uid, sid);
    var result = new ArrayList<Comparison>();
    for (var e : s.exercises) {
      int planned =
          Plans.read(e.setPlan).isEmpty()
              ? (e.targetSets == null ? 0 : e.targetSets)
              : (int) Plans.read(e.setPlan).stream().filter(p -> !p.warmup()).count();
      int actual = (int) e.recordedSets().stream().filter(x -> !x.warmup).count();
      var previous =
          sessions
              .exerciseHistory(
                  uid, e.exerciseId, org.springframework.data.domain.PageRequest.of(0, 100))
              .stream()
              .filter(x -> !x.id.equals(s.id) && x.startedAt.isBefore(s.startedAt))
              .findFirst();
      BigDecimal prior =
          previous
              .map(
                  x ->
                      x.exercises.stream()
                          .filter(a -> a.exerciseId == e.exerciseId)
                          .flatMap(a -> a.recordedSets().stream())
                          .filter(x1 -> !x1.warmup)
                          .map(x1 -> WorkoutMath.volume(e.metricType, x1))
                          .reduce(BigDecimal.ZERO, BigDecimal::add))
              .orElse(null);
      BigDecimal current =
          e.recordedSets().stream()
              .filter(x -> !x.warmup)
              .map(x -> WorkoutMath.volume(e.metricType, x))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      result.add(
          new Comparison(
              e.name,
              planned,
              actual,
              planned == 0 ? 0 : Math.max(0, actual - planned),
              e.skipped,
              prior,
              current));
    }
    return result;
  }

  public TemplateView saveAsTemplate(long uid, long sid, String name) {
    var s = owned(uid, sid);
    return templates.save(
        uid,
        null,
        new TemplateInput(
            name,
            null,
            s.exercises.stream()
                .filter(e -> !e.skipped)
                .map(
                    e ->
                        new Target(
                            e.exerciseId,
                            e.targetSets,
                            e.targetReps,
                            e.targetWeight,
                            e.restSeconds,
                            e.targetDuration,
                            e.targetDistance,
                            Plans.read(e.setPlan)))
                .toList()));
  }
}
