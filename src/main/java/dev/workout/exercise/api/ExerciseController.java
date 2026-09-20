package dev.workout.exercise.api;

import dev.workout.exercise.application.ExerciseService;
import dev.workout.exercise.domain.*;
import dev.workout.user.application.UserService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/exercises")
public class ExerciseController {
  public record ExerciseView(
      long id, String name, String category, MetricType metricType, boolean custom) {}

  public record CreateExercise(String name, MetricType metricType) {}

  private final ExerciseService exercises;
  private final UserService users;

  public ExerciseController(ExerciseService e, UserService u) {
    exercises = e;
    users = u;
  }

  @GetMapping
  public List<ExerciseView> list(
      @RequestAttribute long telegramId,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page) {
    return exercises.search(users.byTelegram(telegramId).id, q, page).stream()
        .map(this::view)
        .toList();
  }

  @PostMapping
  public ExerciseView create(@RequestAttribute long telegramId, @RequestBody CreateExercise input) {
    return view(
        exercises.create(users.byTelegram(telegramId).id, input.name(), input.metricType()));
  }

  private ExerciseView view(Exercise e) {
    return new ExerciseView(e.id, e.name, e.category, e.metricType, e.custom);
  }
}
