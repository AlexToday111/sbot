package dev.workout.workout.application;

import dev.workout.analytics.application.RecordService;
import dev.workout.common.*;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.WorkoutDtos.*;
import dev.workout.workout.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SessionService {
  private final SessionRepository sessions;
  private final TemplateService templates;
  private final UserService users;
  private final RecordService records;
  private final Clock clock;

  public SessionService(
      SessionRepository s, TemplateService t, UserService u, RecordService r, Clock c) {
    sessions = s;
    templates = t;
    users = u;
    records = r;
    clock = c;
  }

  public SessionView start(long uid, long templateId, String key) {
    users.lock(uid);
    key = key(key);
    var previous = sessions.findByUserIdAndRequestKey(uid, key);
    if (previous.isPresent()) {
      if (previous.get().sourceSessionId != null
          || !Objects.equals(previous.get().templateId, templateId))
        throw new DomainException(409, "This request key was used for another workout.");
      return view(previous.get());
    }
    ensureNoActive(uid);
    var t = templates.get(uid, templateId);
    WorkoutSession s = new WorkoutSession();
    s.userId = uid;
    s.templateId = t.id();
    s.name = t.name();
    s.startedAt = clock.instant();
    s.requestKey = key;
    for (TemplateItem item : t.exercises()) {
      SessionExercise e = new SessionExercise();
      e.exerciseId = item.exerciseId();
      e.name = item.name();
      e.metricType = item.metricType();
      e.position = item.position();
      e.targetSets = item.targetSets();
      e.targetReps = item.targetReps();
      e.targetWeight = item.targetWeight();
      e.restSeconds = item.restSeconds();
      s.exercises.add(e);
    }
    return view(sessions.saveAndFlush(s));
  }

  public SessionView repeat(long uid, long sessionId, String key) {
    users.lock(uid);
    key = key(key);
    var previous = sessions.findByUserIdAndRequestKey(uid, key);
    if (previous.isPresent()) {
      if (!Objects.equals(previous.get().sourceSessionId, sessionId))
        throw new DomainException(409, "This request key was used for another workout.");
      return view(previous.get());
    }
    ensureNoActive(uid);
    WorkoutSession source = owned(uid, sessionId);
    if (source.status != WorkoutSession.Status.COMPLETED)
      throw new DomainException("Only completed workouts can be repeated.");
    WorkoutSession s = new WorkoutSession();
    s.userId = uid;
    s.templateId = source.templateId;
    s.sourceSessionId = source.id;
    s.name = source.name;
    s.startedAt = clock.instant();
    s.requestKey = key;
    for (SessionExercise old : source.exercises) {
      SessionExercise e = new SessionExercise();
      e.exerciseId = old.exerciseId;
      e.name = old.name;
      e.metricType = old.metricType;
      e.position = old.position;
      e.targetSets = old.targetSets;
      e.targetReps = old.targetReps;
      e.targetWeight = old.targetWeight;
      e.restSeconds = old.restSeconds;
      s.exercises.add(e);
    }
    return view(sessions.saveAndFlush(s));
  }

  public SessionView addSet(long uid, long sessionId, SetInput input, String key) {
    if (input == null) throw new DomainException("Provide set metrics.");
    users.lock(uid);
    WorkoutSession s = owned(uid, sessionId);
    key = key(key);
    SessionExercise e = exercise(s, input.sessionExerciseId());
    for (ExerciseSet existing : s.exercises.stream().flatMap(item -> item.sets.stream()).toList())
      if (existing.requestKey.equals(key)) {
        if (!e.sets.contains(existing) || !same(existing, input))
          throw new DomainException(409, "This request key was used with different set values.");
        return view(s); // An undone set stays undone on replay.
      }
    active(s);
    SetValidation.validate(e.metricType, input);
    if (e.recordedSets().size() >= 100) throw new DomainException("Maximum 100 sets per exercise.");
    ExerciseSet set = new ExerciseSet();
    set.setNumber = e.sets.stream().mapToInt(x -> x.setNumber).max().orElse(0) + 1;
    set.weight = input.weight();
    set.repetitions = input.repetitions();
    set.durationSeconds = input.durationSeconds();
    set.distance = input.distance();
    set.rpe = input.rpe();
    set.notes = input.notes();
    set.requestKey = key;
    set.createdAt = clock.instant();
    e.sets.add(set);
    sessions.flush();
    return view(s);
  }

  public SessionView undo(long uid, long sid, long setId) {
    users.lock(uid);
    WorkoutSession s = owned(uid, sid);
    active(s);
    ExerciseSet set =
        s.exercises.stream()
            .flatMap(e -> e.sets.stream())
            .filter(x -> x.id == setId)
            .findFirst()
            .orElseThrow(DomainException::missing);
    set.voided = true;
    return view(s);
  }

  public SessionView navigate(long uid, long sid, int position) {
    users.lock(uid);
    WorkoutSession s = owned(uid, sid);
    active(s);
    if (position < 0 || position >= s.exercises.size())
      throw new DomainException("Exercise is no longer available.");
    s.currentPosition = position;
    return view(s);
  }

  public SessionView finish(long uid, long sid) {
    users.lock(uid);
    WorkoutSession s = owned(uid, sid);
    if (s.status == WorkoutSession.Status.COMPLETED) return view(s);
    active(s);
    s.status = WorkoutSession.Status.COMPLETED;
    s.finishedAt = clock.instant();
    records.onCompleted(s);
    return view(s);
  }

  public SessionView cancel(long uid, long sid) {
    users.lock(uid);
    WorkoutSession s = owned(uid, sid);
    if (s.status == WorkoutSession.Status.CANCELLED) return view(s);
    active(s);
    s.status = WorkoutSession.Status.CANCELLED;
    s.finishedAt = clock.instant();
    return view(s);
  }

  @Transactional(readOnly = true)
  public SessionView get(long uid, long sid) {
    return view(owned(uid, sid));
  }

  @Transactional(readOnly = true)
  public Optional<SessionView> active(long uid) {
    return sessions.findByUserIdAndStatus(uid, WorkoutSession.Status.ACTIVE).map(this::view);
  }

  @Transactional(readOnly = true)
  public List<SessionView> history(long uid, YearMonth month, int page) {
    ZoneId zone = ZoneId.of(users.get(uid).timezone);
    return sessions
        .findByUserIdAndStatusAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDesc(
            uid,
            WorkoutSession.Status.COMPLETED,
            month.atDay(1).atStartOfDay(zone).toInstant(),
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant(),
            PageRequest.of(Math.max(0, page), 8))
        .stream()
        .map(this::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<SessionView> exerciseHistory(long uid, long eid, int page) {
    return sessions.exerciseHistory(uid, eid, PageRequest.of(Math.max(0, page), 5)).stream()
        .map(this::view)
        .toList();
  }

  private WorkoutSession owned(long uid, long sid) {
    WorkoutSession s = sessions.findById(sid).orElseThrow(DomainException::missing);
    if (s.userId != uid) throw DomainException.missing();
    return s;
  }

  private void active(WorkoutSession s) {
    if (s.status != WorkoutSession.Status.ACTIVE)
      throw new DomainException(
          409, "This workout has ended. Open history or start another workout.");
  }

  private void ensureNoActive(long uid) {
    if (sessions.findByUserIdAndStatus(uid, WorkoutSession.Status.ACTIVE).isPresent())
      throw new DomainException(
          409, "You already have an active workout. Continue or finish it first.");
  }

  private SessionExercise exercise(WorkoutSession s, long id) {
    return s.exercises.stream()
        .filter(e -> e.id == id)
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  private SessionView view(WorkoutSession s) {
    return WorkoutMapper.session(s, clock.instant());
  }

  private String key(String key) {
    if (key == null || key.isBlank() || key.length() > 100)
      throw new DomainException("Provide an Idempotency-Key of 1 to 100 characters.");
    return key;
  }

  private boolean same(ExerciseSet s, SetInput i) {
    return eq(s.weight, i.weight())
        && Objects.equals(s.repetitions, i.repetitions())
        && Objects.equals(s.durationSeconds, i.durationSeconds())
        && eq(s.distance, i.distance())
        && eq(s.rpe, i.rpe())
        && Objects.equals(s.notes, i.notes());
  }

  private boolean eq(java.math.BigDecimal a, java.math.BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }
}
