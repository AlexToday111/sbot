package dev.workout.workout.application;

import dev.workout.common.*;
import dev.workout.exercise.application.ExerciseService;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.WorkoutDtos.*;
import dev.workout.workout.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TemplateService {
  private final TemplateRepository templates;
  private final ExerciseService exercises;
  private final UserService users;
  private final Clock clock;

  public TemplateService(TemplateRepository t, ExerciseService e, UserService u, Clock c) {
    templates = t;
    exercises = e;
    users = u;
    clock = c;
  }

  @Transactional(readOnly = true)
  public List<TemplateView> list(long uid, int page) {
    return templates
        .findByUserIdAndDeletedFalseOrderByUpdatedAtDesc(uid, PageRequest.of(Math.max(0, page), 8))
        .stream()
        .map(this::view)
        .toList();
  }

  @Transactional(readOnly = true)
  public TemplateView get(long uid, long id) {
    return view(owned(uid, id));
  }

  public TemplateView save(long uid, Long id, TemplateInput input) {
    users.lock(uid);
    if (input == null) throw new DomainException("Provide a workout.");
    String name = Checks.name(input.name());
    Checks.text(input.description(), 500);
    if (input.exercises() == null || input.exercises().isEmpty() || input.exercises().size() > 30)
      throw new DomainException("A workout needs 1 to 30 exercises.");
    // Validate before changing a managed aggregate, including when called by a Telegram flow.
    for (Target item : input.exercises()) {
      if (item == null) throw new DomainException("Choose an exercise.");
      var exercise = exercises.get(uid, item.exerciseId());
      Plans.validate(exercise.metricType, item);
      Checks.range(item.sets(), 1, 100, "Sets");
      Checks.range(item.reps(), 0, 1000, "Repetitions");
      Checks.range(item.weight(), 0, 2000, "Weight");
      Checks.range(item.restSeconds(), 0, 3600, "Rest");
      Checks.scale(item.weight(), 3, "Weight");
    }
    WorkoutTemplate t = id == null ? new WorkoutTemplate() : owned(uid, id);
    t.userId = uid;
    t.name = name;
    t.description = input.description();
    t.updatedAt = clock.instant();
    if (t.createdAt == null) t.createdAt = t.updatedAt;
    t.exercises.clear();
    for (Target item : input.exercises()) {
      TemplateExercise e = new TemplateExercise();
      e.exerciseId = item.exerciseId();
      e.position = t.exercises.size();
      e.targetSets = item.sets();
      e.targetReps = item.reps();
      e.targetWeight = item.weight();
      e.restSeconds = item.restSeconds();
      e.targetDuration = item.durationSeconds();
      e.targetDistance = item.distance();
      e.setPlan = Plans.write(item.plan());
      t.exercises.add(e);
    }
    return view(templates.saveAndFlush(t));
  }

  public void delete(long uid, long id) {
    users.lock(uid);
    WorkoutTemplate t = owned(uid, id);
    t.deleted = true;
    t.updatedAt = clock.instant();
  }

  private WorkoutTemplate owned(long uid, long id) {
    WorkoutTemplate t = templates.findById(id).orElseThrow(DomainException::missing);
    if (t.userId != uid || t.deleted) throw DomainException.missing();
    return t;
  }

  private TemplateView view(WorkoutTemplate t) {
    return new TemplateView(
        t.id,
        t.name,
        t.description,
        t.exercises.stream()
            .map(
                i -> {
                  var e = exercises.get(t.userId, i.exerciseId);
                  return new TemplateItem(
                      e.id,
                      e.name,
                      e.metricType,
                      i.position,
                      i.targetSets,
                      i.targetReps,
                      i.targetWeight,
                      i.restSeconds,
                      i.targetDuration,
                      i.targetDistance,
                      Plans.read(i.setPlan));
                })
            .toList());
  }
}
