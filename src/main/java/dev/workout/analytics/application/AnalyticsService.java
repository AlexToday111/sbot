package dev.workout.analytics.application;

import dev.workout.analytics.domain.*;
import dev.workout.exercise.application.ExerciseService;
import dev.workout.exercise.domain.*;
import dev.workout.user.application.UserService;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AnalyticsService {
  public record Totals(
      long workouts,
      long exercises,
      long sets,
      long repetitions,
      long durationSeconds,
      BigDecimal volume) {}

  public record Day(LocalDate date, long workouts, BigDecimal volume) {}

  public record Report(
      String period,
      LocalDate start,
      LocalDate end,
      Totals current,
      Totals previous,
      long workoutChange,
      BigDecimal volumeChangePercent,
      List<Day> frequency) {}

  public record Point(String month, BigDecimal value) {}

  public record Records(
      BigDecimal maxWeight,
      int maxReps,
      BigDecimal bestSetVolume,
      BigDecimal bestSessionVolume,
      BigDecimal estimatedOneRm) {}

  public record Progress(
      long exerciseId,
      String name,
      String metric,
      String unit,
      List<Point> points,
      BigDecimal changePercent,
      Records records) {}

  private final JdbcTemplate jdbc;
  private final UserService users;
  private final ExerciseService exercises;
  private final RecordService records;
  private final Clock clock;

  public AnalyticsService(
      JdbcTemplate j, UserService u, ExerciseService e, RecordService r, Clock c) {
    jdbc = j;
    users = u;
    exercises = e;
    records = r;
    clock = c;
  }

  public Report report(long uid, String period) {
    ZoneId zone = ZoneId.of(users.get(uid).timezone);
    var w = Periods.of(period, LocalDate.now(clock.withZone(zone)));
    Totals current = totals(uid, w.start(), w.end(), zone),
        previous = totals(uid, w.previousStart(), w.previousEnd(), zone);
    List<Day> days =
        jdbc.query(
            """
    select (s.started_at at time zone ?)::date as day, count(distinct s.id) as workouts,
      coalesce(sum(case when e.metric_type='STRENGTH' then x.weight*x.repetitions else 0 end),0) as volume
    from workout_sessions s left join workout_session_exercises e on e.workout_session_id=s.id
    left join exercise_sets x on x.workout_session_exercise_id=e.id and not x.voided
    where s.user_id=? and s.status='COMPLETED' and s.started_at>=? and s.started_at<?
    group by day order by day
    """,
            (rs, n) ->
                new Day(
                    rs.getDate("day").toLocalDate(),
                    rs.getLong("workouts"),
                    rs.getBigDecimal("volume")),
            zone.getId(),
            uid,
            ts(w.start(), zone),
            ts(w.end(), zone));
    return new Report(
        period,
        w.start(),
        w.end(),
        current,
        previous,
        current.workouts - previous.workouts,
        change(current.volume, previous.volume),
        days);
  }

  public Totals totals(long uid, LocalDate start, LocalDate end, ZoneId zone) {
    // Aggregate time separately: joining to sets must never multiply session duration.
    var base =
        jdbc.queryForMap(
            "select count(*) as workouts, coalesce(sum(extract(epoch from (finished_at-started_at))),0) as seconds from workout_sessions where user_id=? and status='COMPLETED' and started_at>=? and started_at<?",
            uid,
            ts(start, zone),
            ts(end, zone));
    return jdbc.queryForObject(
        """
    select count(distinct e.id) as exercises,count(x.id) as sets,coalesce(sum(x.repetitions),0) as reps,
    coalesce(sum(case when e.metric_type='STRENGTH' then x.weight*x.repetitions else 0 end),0) as volume
    from workout_sessions s join workout_session_exercises e on e.workout_session_id=s.id
    join exercise_sets x on x.workout_session_exercise_id=e.id and not x.voided
    where s.user_id=? and s.status='COMPLETED' and s.started_at>=? and s.started_at<?
    """,
        (rs, n) ->
            new Totals(
                ((Number) base.get("workouts")).longValue(),
                rs.getLong("exercises"),
                rs.getLong("sets"),
                rs.getLong("reps"),
                ((Number) base.get("seconds")).longValue(),
                rs.getBigDecimal("volume")),
        uid,
        ts(start, zone),
        ts(end, zone));
  }

  public Progress progress(long uid, long eid, String period) {
    Exercise exercise = exercises.get(uid, eid);
    ZoneId zone = ZoneId.of(users.get(uid).timezone);
    var w = Periods.of(period, LocalDate.now(clock.withZone(zone)));
    String expression =
        switch (exercise.metricType) {
          case STRENGTH ->
              "case when x.repetitions=0 then 0 when x.repetitions=1 then x.weight else x.weight*(1+x.repetitions/30.0) end";
          case BODYWEIGHT -> "x.repetitions";
          case CARDIO -> "x.distance";
          case TIMED -> "x.duration_seconds";
        };
    List<Point> points =
        jdbc.query(
            """
    select to_char(s.started_at at time zone ?,'YYYY-MM') as month, max(%s) as value
    from workout_sessions s join workout_session_exercises e on e.workout_session_id=s.id
    join exercise_sets x on x.workout_session_exercise_id=e.id and not x.voided
    where s.user_id=? and e.exercise_id=? and s.status='COMPLETED' and s.started_at>=? and s.started_at<?
    group by month order by month
    """
                .formatted(expression),
            (rs, n) ->
                new Point(
                    rs.getString("month"),
                    rs.getBigDecimal("value").setScale(2, RoundingMode.HALF_UP)),
            zone.getId(),
            uid,
            eid,
            ts(w.start(), zone),
            ts(w.end(), zone));
    Records best =
        records
            .get(uid, eid)
            .map(
                r ->
                    new Records(
                        r.maxWeight,
                        r.maxReps,
                        r.bestSetVolume,
                        r.bestSessionVolume,
                        r.estimatedOneRm))
            .orElse(null);
    String metric =
        switch (exercise.metricType) {
          case STRENGTH -> "Estimated 1RM";
          case BODYWEIGHT -> "Most repetitions";
          case CARDIO -> "Longest distance";
          case TIMED -> "Longest duration";
        };
    String unit =
        switch (exercise.metricType) {
          case STRENGTH -> "kg";
          case BODYWEIGHT -> "reps";
          case CARDIO -> "km";
          case TIMED -> "seconds";
        };
    return new Progress(
        eid,
        exercise.name,
        metric,
        unit,
        points,
        points.size() < 2 ? null : change(points.get(points.size() - 1).value, points.get(0).value),
        best);
  }

  public static BigDecimal change(BigDecimal current, BigDecimal previous) {
    return previous.signum() == 0
        ? null
        : current
            .subtract(previous)
            .multiply(BigDecimal.valueOf(100))
            .divide(previous, 1, RoundingMode.HALF_UP);
  }

  private Timestamp ts(LocalDate date, ZoneId zone) {
    return Timestamp.from(date.atStartOfDay(zone).toInstant());
  }
}
