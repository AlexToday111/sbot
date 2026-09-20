package dev.workout.exercise.application;

import dev.workout.common.*;
import dev.workout.exercise.domain.*;
import dev.workout.user.application.UserService;
import java.time.Clock;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ExerciseService {
  private final ExerciseRepository exercises;
  private final UserService users;
  private final Clock clock;

  public ExerciseService(ExerciseRepository e, UserService u, Clock c) {
    exercises = e;
    users = u;
    clock = c;
  }

  @Transactional(readOnly = true)
  public List<Exercise> search(long userId, String query, int page) {
    return exercises.search(
        userId, Checks.text(query == null ? "" : query, 80), PageRequest.of(Math.max(0, page), 8));
  }

  @Transactional(readOnly = true)
  public Exercise get(long uid, long id) {
    Exercise e = exercises.findById(id).orElseThrow(DomainException::missing);
    if (e.userId != null && e.userId != uid) throw DomainException.missing();
    return e;
  }

  public Exercise create(long uid, String name, MetricType type) {
    users.lock(uid);
    name = Checks.name(name);
    if (type == null) throw new DomainException("Choose an exercise type.");
    if (exercises.existsByUserIdAndNameIgnoreCase(uid, name))
      throw new DomainException("You already have an exercise with this name.");
    Exercise e = new Exercise();
    e.userId = uid;
    e.name = name;
    e.category = "Custom";
    e.metricType = type;
    e.custom = true;
    e.createdAt = clock.instant();
    return exercises.save(e);
  }
}
