package dev.workout.workout.application;

import static dev.workout.common.I18n.t;

import dev.workout.common.DomainException;
import dev.workout.exercise.application.ExerciseService;
import dev.workout.exercise.domain.ExerciseRepository;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.WorkoutDtos.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CsvImportService {
  private final ExerciseRepository catalog;
  private final ExerciseService exercises;
  private final UserService users;
  private final TemplateService templates;

  public CsvImportService(
      ExerciseRepository catalog,
      ExerciseService exercises,
      UserService users,
      TemplateService templates) {
    this.catalog = catalog;
    this.exercises = exercises;
    this.users = users;
    this.templates = templates;
  }

  public TemplateInput draft(long uid, byte[] bytes) {
    var parsed = WorkoutCsv.parse(bytes);
    users.lock(uid);
    List<Target> targets = new ArrayList<>();
    int rowNumber = 1;
    for (var row : parsed.rows()) {
      rowNumber++;
      var matches = catalog.exact(uid, row.exercise());
      var exercise =
          matches.stream()
              .filter(e -> e.userId != null)
              .findFirst()
              .orElseGet(
                  () ->
                      matches.stream()
                          .findFirst()
                          .orElseGet(() -> exercises.create(uid, row.exercise(), row.type())));
      if (exercise.metricType != row.type())
        throw new DomainException(
            t("CSV row ")
                + rowNumber
                + ": "
                + t("CSV metric type differs from the existing exercise: ")
                + row.exercise());
      targets.add(new Target(exercise.id, row.sets(), row.reps(), row.weight(), row.rest()));
    }
    return new TemplateInput(parsed.name(), null, targets);
  }

  public TemplateView save(long uid, byte[] bytes) {
    return templates.save(uid, null, draft(uid, bytes));
  }
}
