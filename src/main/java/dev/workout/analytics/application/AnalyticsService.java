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
    return report(uid, period, false, false);
  }

  public Report report(long uid, String period, boolean workingOnly, boolean equalElapsed) {
    ZoneId zone = ZoneId.of(users.get(uid).timezone);
    var w = Periods.of(period, LocalDate.now(clock.withZone(zone)));
    Instant currentEnd = w.end().atStartOfDay(zone).toInstant(),
        previousEnd = w.previousEnd().atStartOfDay(zone).toInstant();
    if (equalElapsed) {
      var elapsed =
          Duration.between(w.start().atStartOfDay(), LocalDateTime.now(clock.withZone(zone)));
      var previousLocal = w.previousStart().atStartOfDay().plus(elapsed);
      if (previousLocal.isAfter(w.previousEnd().atStartOfDay()))
        previousLocal = w.previousEnd().atStartOfDay();
      previousEnd = previousLocal.atZone(zone).toInstant();
      // Both windows cover the same elapsed local time, clipped to the shorter period.
      var common = Duration.between(w.previousStart().atStartOfDay(), previousLocal);
      currentEnd = w.start().atStartOfDay().plus(common).atZone(zone).toInstant();
    }
    Totals
        current =
            totalsBetween(uid, w.start().atStartOfDay(zone).toInstant(), currentEnd, workingOnly),
        previous =
            totalsBetween(
                uid, w.previousStart().atStartOfDay(zone).toInstant(), previousEnd, workingOnly);
    List<Day> days =
        jdbc.query(
            """
    select (s.started_at at time zone ?)::date as day, count(distinct s.id) as workouts,
      coalesce(sum(case when e.metric_type='STRENGTH' then x.weight*x.repetitions else 0 end),0) as volume
    from workout_sessions s left join workout_session_exercises e on e.workout_session_id=s.id
    left join exercise_sets x on x.workout_session_exercise_id=e.id and not x.voided and (not ? or not x.warmup)
    where s.user_id=? and s.status='COMPLETED' and s.started_at>=? and s.started_at<?
    group by day order by day
    """,
            (rs, n) ->
                new Day(
                    rs.getDate("day").toLocalDate(),
                    rs.getLong("workouts"),
                    rs.getBigDecimal("volume")),
            zone.getId(),
            workingOnly,
            uid,
            ts(w.start(), zone),
            Timestamp.from(currentEnd));
    return new Report(
        period,
        w.start(),
        equalElapsed ? currentEnd.atZone(zone).toLocalDate().plusDays(1) : w.end(),
        current,
        previous,
        current.workouts - previous.workouts,
        change(current.volume, previous.volume),
        days);
  }

  public Totals totals(long uid, LocalDate start, LocalDate end, ZoneId zone) {
    return totalsBetween(
        uid, start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant(), false);
  }

  public Totals totalsBetween(long uid, Instant start, Instant end, boolean workingOnly) {
    users.get(uid);
    // Aggregate time separately: joining to sets must never multiply session duration.
    var base =
        jdbc.queryForMap(
            "select count(*) as workouts, coalesce(sum(greatest(0,extract(epoch from (finished_at-started_at))-paused_seconds)),0) as seconds from workout_sessions where user_id=? and status='COMPLETED' and started_at>=? and started_at<?",
            uid,
            Timestamp.from(start),
            Timestamp.from(end));
    return jdbc.queryForObject(
        """
    select count(distinct e.id) as exercises,count(x.id) as sets,coalesce(sum(x.repetitions),0) as reps,
    coalesce(sum(case when e.metric_type='STRENGTH' then x.weight*x.repetitions else 0 end),0) as volume
    from workout_sessions s join workout_session_exercises e on e.workout_session_id=s.id
    join exercise_sets x on x.workout_session_exercise_id=e.id and not x.voided and (not ? or not x.warmup)
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
        workingOnly,
        uid,
        Timestamp.from(start),
        Timestamp.from(end));
  }

  public Progress progress(long uid, long eid, String period) {
    return progress(uid, eid, period, false, false);
  }

  public Progress progress(
      long uid, long eid, String period, boolean workingOnly, boolean bySession) {
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
    String bucket =
        bySession
            ? "to_char(s.started_at at time zone ?,'YYYY-MM-DD HH24:MI')"
            : "to_char(s.started_at at time zone ?,'YYYY-MM')";
    String grouping = bySession ? "s.id,s.started_at" : "month";
    String ordering = bySession ? "s.started_at,s.id" : "month";
    List<Point> points =
        jdbc.query(
            "select "
                + bucket
                + " as month,max("
                + expression
                + ") as value from workout_sessions s join workout_session_exercises e on e.workout_session_id=s.id join exercise_sets x on x.workout_session_exercise_id=e.id and not x.voided and (not ? or not x.warmup) where s.user_id=? and e.exercise_id=? and s.status='COMPLETED' and s.started_at>=? and s.started_at<? group by "
                + grouping
                + " order by "
                + ordering,
            (rs, n) ->
                new Point(
                    rs.getString("month"),
                    rs.getBigDecimal("value").setScale(2, RoundingMode.HALF_UP)),
            zone.getId(),
            workingOnly,
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
