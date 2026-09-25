package dev.workout.workout.application;

import dev.workout.common.*;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.WorkoutDtos.*;
import dev.workout.workout.domain.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PlanningService {
  public record Preferences(
      int weeklyGoal, boolean restNotifications, BigDecimal weightStep, int repMin, int repMax) {}

  public record Appointment(
      long id,
      long templateId,
      String name,
      LocalDateTime localStart,
      int repeatWeeks,
      boolean reminders) {}

  public record Goal(int target, long completed, Appointment next) {}

  public record Suggestion(TemplateInput template, String reason) {}

  private final JdbcTemplate jdbc;
  private final UserService users;
  private final TemplateService templates;
  private final SessionRepository sessions;
  private final Clock clock;

  public PlanningService(
      JdbcTemplate jdbc,
      UserService users,
      TemplateService templates,
      SessionRepository sessions,
      Clock clock) {
    this.jdbc = jdbc;
    this.users = users;
    this.templates = templates;
    this.sessions = sessions;
    this.clock = clock;
  }

  public Preferences preferences(long uid) {
    users.get(uid);
    jdbc.update("insert into training_preferences(user_id) values (?) on conflict do nothing", uid);
    return jdbc.queryForObject(
        "select * from training_preferences where user_id=?",
        (rs, n) ->
            new Preferences(
                rs.getInt("weekly_goal"),
                rs.getBoolean("rest_notifications"),
                rs.getBigDecimal("weight_step"),
                rs.getInt("rep_min"),
                rs.getInt("rep_max")),
        uid);
  }

  public Preferences preferences(long uid, Preferences p) {
    users.lock(uid);
    if (p == null || p.weightStep() == null)
      throw new DomainException("Provide training settings.");
    Checks.range(p.weeklyGoal(), 1, 14, "Weekly goal");
    Checks.range(p.weightStep(), 0.125, 100, "Weight step");
    Checks.scale(p.weightStep(), 3, "Weight step");
    Checks.range(p.repMin(), 1, 100, "Repetitions");
    Checks.range(p.repMax(), p.repMin(), 100, "Repetitions");
    preferences(uid);
    jdbc.update(
        "update training_preferences set weekly_goal=?,rest_notifications=?,weight_step=?,rep_min=?,rep_max=? where user_id=?",
        p.weeklyGoal(),
        p.restNotifications(),
        p.weightStep(),
        p.repMin(),
        p.repMax(),
        uid);
    return p;
  }

  public List<Appointment> schedule(long uid) {
    users.get(uid);
    return jdbc.query(
        "select s.*,t.name from training_schedule s join workout_templates t on t.id=s.template_id where s.user_id=? and not s.cancelled and not t.deleted and (s.repeat_weeks>0 or s.local_start >= ?) order by s.local_start,s.id limit 50",
        (rs, n) ->
            new Appointment(
                rs.getLong("id"),
                rs.getLong("template_id"),
                rs.getString("name"),
                rs.getTimestamp("local_start").toLocalDateTime(),
                rs.getInt("repeat_weeks"),
                rs.getBoolean("reminders")),
        uid,
        java.sql.Timestamp.valueOf(
            LocalDateTime.now(clock.withZone(ZoneId.of(users.get(uid).timezone))).minusDays(1)));
  }

  public Appointment schedule(
      long uid, Long id, long templateId, LocalDateTime start, int repeatWeeks, boolean reminders) {
    users.lock(uid);
    templates.get(uid, templateId);
    Checks.range(repeatWeeks, 0, 4, "Repeat weeks");
    ZoneId zone = ZoneId.of(users.get(uid).timezone);
    if (start == null
        || !start.atZone(zone).toInstant().isAfter(clock.instant())
        || zone.getRules().getValidOffsets(start).isEmpty())
      throw new DomainException("Choose a future local date and time valid in your timezone.");
    if (id == null) {
      if (schedule(uid).size() >= 50) throw new DomainException("Maximum 50 planned workouts.");
      id =
          jdbc.queryForObject(
              "insert into training_schedule(user_id,template_id,local_start,repeat_weeks,reminders) values (?,?,?,?,?) returning id",
              Long.class,
              uid,
              templateId,
              java.sql.Timestamp.valueOf(start),
              repeatWeeks,
              reminders);
    } else if (jdbc.update(
            "update training_schedule set template_id=?,local_start=?,repeat_weeks=?,reminders=?,notified_at=null where id=? and user_id=? and not cancelled",
            templateId,
            java.sql.Timestamp.valueOf(start),
            repeatWeeks,
            reminders,
            id,
            uid)
        != 1) throw DomainException.missing();
    return new Appointment(
        id, templateId, templates.get(uid, templateId).name(), start, repeatWeeks, reminders);
  }

  public void cancel(long uid, long id) {
    users.lock(uid);
    if (jdbc.update("update training_schedule set cancelled=true where id=? and user_id=?", id, uid)
        != 1) throw DomainException.missing();
  }

  public Goal goal(long uid) {
    var p = preferences(uid);
    ZoneId zone = ZoneId.of(users.get(uid).timezone);
    var today = LocalDate.now(clock.withZone(zone));
    var monday = today.with(java.time.DayOfWeek.MONDAY);
    long count =
        jdbc.queryForObject(
            "select count(*) from workout_sessions where user_id=? and status='COMPLETED' and started_at>=? and started_at<?",
            Long.class,
            uid,
            java.sql.Timestamp.from(monday.atStartOfDay(zone).toInstant()),
            java.sql.Timestamp.from(monday.plusWeeks(1).atStartOfDay(zone).toInstant()));
    var next =
        schedule(uid).stream()
            .filter(a -> a.localStart().atZone(zone).toInstant().isAfter(clock.instant()))
            .findFirst()
            .orElse(null);
    return new Goal(p.weeklyGoal(), count, next);
  }

  public Suggestion suggest(long uid, long templateId) {
    var t = templates.get(uid, templateId);
    var prefs = preferences(uid);
    var targets = new ArrayList<Target>();
    int increased = 0;
    for (var e : t.exercises()) {
      var target = Plans.target(e);
      var recent =
          sessions.exerciseHistory(
              uid, e.exerciseId(), org.springframework.data.domain.PageRequest.of(0, 1));
      if (e.metricType() == dev.workout.exercise.domain.MetricType.STRENGTH
          && e.targetSets() != null
          && e.targetWeight() != null
          && (e.plan() == null || e.plan().isEmpty())
          && !recent.isEmpty()) {
        var sets =
            recent.get(0).exercises.stream()
                .filter(x -> x.exerciseId == e.exerciseId())
                .flatMap(x -> x.recordedSets().stream())
                .filter(x -> !x.warmup)
                .toList();
        if (sets.size() >= e.targetSets()
            && sets.stream()
                .allMatch(
                    x ->
                        x.repetitions >= prefs.repMax()
                            && x.weight.compareTo(e.targetWeight()) >= 0)
            && e.targetWeight().add(prefs.weightStep()).compareTo(new BigDecimal("2000")) <= 0) {
          target =
              new Target(
                  e.exerciseId(),
                  e.targetSets(),
                  prefs.repMin(),
                  e.targetWeight().add(prefs.weightStep()),
                  e.restSeconds(),
                  e.targetDuration(),
                  e.targetDistance(),
                  e.plan());
          increased++;
        }
      }
      targets.add(target);
    }
    String reason =
        increased == 0
            ? I18n.t(
                "Keep the current plan: no exercise met all progression rules. A strength target and enough working sets at the upper repetition bound are required. Individual set plans are kept.")
            : I18n.t(
                    "Increase weight only where all working sets reached your upper repetition target. Reset repetitions to your lower target. Exercises changed: ")
                + increased;
    return new Suggestion(new TemplateInput(t.name(), t.description(), targets), reason);
  }
}
