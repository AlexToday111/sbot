package dev.workout.workout.application;

import static dev.workout.common.I18n.t;

import dev.workout.common.DomainException;
import dev.workout.exercise.application.ExerciseService;
import dev.workout.exercise.domain.*;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.WorkoutDtos.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CsvImportService {
  private final ExerciseRepository catalog;
  private final ExerciseService exercises;
  private final UserService users;
  private final TemplateService templates;
  private final JdbcTemplate jdbc;

  public CsvImportService(
      ExerciseRepository catalog,
      ExerciseService exercises,
      UserService users,
      TemplateService templates,
      JdbcTemplate jdbc) {
    this.catalog = catalog;
    this.exercises = exercises;
    this.users = users;
    this.templates = templates;
    this.jdbc = jdbc;
  }

  public Exercise resolve(long uid, String name, MetricType type, boolean create) {
    var matches = catalog.exact(uid, name);
    Exercise e =
        matches.stream()
            .filter(x -> x.userId != null)
            .findFirst()
            .orElseGet(() -> matches.stream().findFirst().orElse(null));
    if (e == null) {
      var ids =
          jdbc.queryForList(
              "select exercise_id from exercise_aliases where lower(alias)=lower(?)",
              Long.class,
              name.trim());
      if (!ids.isEmpty()) e = exercises.get(uid, ids.get(0));
    }
    if (e != null && e.metricType != type)
      throw new DomainException(t("CSV metric type differs from the existing exercise: ") + name);
    return e == null && create ? exercises.create(uid, name, type) : e;
  }

  public WorkoutCsv.Parsed preview(long uid, byte[] bytes) {
    users.get(uid);
    var p = WorkoutCsv.parse(bytes);
    for (var r : p.rows()) resolve(uid, r.exercise(), r.type(), false);
    return p;
  }

  public Long duplicate(long uid, WorkoutCsv.Parsed p) {
    users.get(uid);
    var ids =
        jdbc.queryForList(
            "select i.template_id from template_imports i join workout_templates t on t.id=i.template_id where i.user_id=? and i.fingerprint=? and not t.deleted",
            Long.class,
            uid,
            fingerprint(p));
    return ids.isEmpty() ? null : ids.get(0);
  }

  private TemplateInput materialize(long uid, WorkoutCsv.Parsed parsed) {
    users.lock(uid);
    List<Target> targets = new ArrayList<>();
    for (var r : parsed.rows()) {
      var e = resolve(uid, r.exercise(), r.type(), true);
      targets.add(
          new Target(
              e.id,
              r.sets(),
              r.reps(),
              r.weight(),
              r.rest(),
              r.durationSeconds(),
              r.distance(),
              r.plan()));
    }
    return new TemplateInput(parsed.name(), null, targets);
  }

  public TemplateView save(long uid, byte[] bytes) {
    return save(uid, preview(uid, bytes), null, false);
  }

  public TemplateView save(long uid, WorkoutCsv.Parsed p, Long replaceId, boolean allowDuplicate) {
    users.lock(uid);
    if (replaceId != null) templates.get(uid, replaceId);
    if (duplicate(uid, p) != null && replaceId == null && !allowDuplicate)
      throw new DomainException(
          409, "This template was already imported. Choose create copy or replace existing.");
    var v = templates.save(uid, replaceId, materialize(uid, p));
    jdbc.update(
        "insert into template_imports(user_id,fingerprint,template_id) values (?,?,?) on conflict (user_id,fingerprint) do update set template_id=excluded.template_id",
        uid,
        fingerprint(p),
        v.id());
    return v;
  }

  public static String fingerprint(Object value) {
    try {
      byte[] bytes =
          value instanceof byte[] b
              ? b
              : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(value);
      return HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
