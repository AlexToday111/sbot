package dev.workout.workout.application;

import dev.workout.common.*;
import dev.workout.exercise.domain.MetricType;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.WorkoutDtos.*;
import dev.workout.workout.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class HistoryTransferService {
  public static final List<String> HEADER =
      List.of(
          "session",
          "workout",
          "started_at",
          "finished_at",
          "exercise_position",
          "exercise",
          "metric_type",
          "weight",
          "reps",
          "duration_seconds",
          "distance",
          "rpe",
          "notes",
          "warmup",
          "paused_seconds");

  public record Item(int position, String name, MetricType type, List<SetInput> sets) {}

  public record Entry(
      String name, Instant started, Instant finished, List<Item> exercises, long pausedSeconds) {}

  private final SessionRepository sessions;
  private final UserService users;
  private final CsvImportService imports;
  private final TrainingService training;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public HistoryTransferService(
      SessionRepository sessions,
      UserService users,
      CsvImportService imports,
      TrainingService training,
      JdbcTemplate jdbc,
      Clock clock) {
    this.sessions = sessions;
    this.users = users;
    this.imports = imports;
    this.training = training;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public List<Entry> preview(long uid, byte[] bytes) {
    users.get(uid);
    var rows = WorkoutCsv.table(bytes);
    if (rows.size() < 2 || !rows.get(0).equals(HEADER))
      throw new DomainException("Invalid history CSV header. Use the history export format.");
    var groups = new LinkedHashMap<String, List<List<String>>>();
    for (var r : rows.subList(1, rows.size())) {
      if (r.size() != 15 || r.get(0).isBlank())
        throw new DomainException("History CSV needs 15 columns and a session identifier.");
      groups.computeIfAbsent(r.get(0), k -> new ArrayList<>()).add(r);
    }
    if (groups.size() > 100) throw new DomainException("Import at most 100 workouts at a time.");
    List<Entry> entries = new ArrayList<>();
    for (var group : groups.values()) {
      var first = group.get(0);
      Instant start, end;
      try {
        start = Instant.parse(first.get(2));
        end = Instant.parse(first.get(3));
      } catch (Exception ex) {
        throw new DomainException(
            "Use ISO timestamps with timezone, for example 2026-01-10T15:00:00Z.");
      }
      if (!end.isAfter(start) || end.isAfter(clock.instant()))
        throw new DomainException("Provide past start and finish times in chronological order.");
      long paused = Checks.integer(first.get(14));
      if (paused < 0 || paused > Duration.between(start, end).getSeconds())
        throw new DomainException("Paused seconds must be between zero and the workout duration.");
      var items = new TreeMap<Integer, Item>();
      for (var r : group) {
        if (!r.subList(1, 4).equals(first.subList(1, 4)) || !r.get(14).equals(first.get(14)))
          throw new DomainException("Rows of one session must share its name and timestamps.");
        int pos = Checks.integer(r.get(4));
        Checks.range(pos, 0, 29, "Position");
        MetricType type;
        try {
          type = MetricType.valueOf(r.get(6));
        } catch (Exception ex) {
          throw new DomainException("Metric type must be STRENGTH, BODYWEIGHT, CARDIO or TIMED.");
        }
        String name = Checks.name(r.get(5));
        imports.resolve(uid, name, type, false);
        var item = items.computeIfAbsent(pos, k -> new Item(pos, name, type, new ArrayList<>()));
        if (!item.name.equals(name) || item.type != type)
          throw new DomainException("Each exercise position must have one name and metric type.");
        if (!List.of("true", "false").contains(r.get(13)))
          throw new DomainException("warmup must be true or false.");
        var values =
            new SetInput(
                0,
                decimal(r.get(7)),
                integer(r.get(8)),
                integer(r.get(9)),
                decimal(r.get(10)),
                decimal(r.get(11)),
                r.get(12).isBlank() ? null : r.get(12),
                Boolean.parseBoolean(r.get(13)));
        SetValidation.validate(type, values);
        item.sets.add(values);
        if (item.sets.size() > 100) throw new DomainException("Maximum 100 sets per exercise.");
      }
      entries.add(
          new Entry(Checks.name(first.get(1)), start, end, List.copyOf(items.values()), paused));
    }
    return entries;
  }

  public List<SessionView> importCsv(long uid, byte[] bytes) {
    users.lock(uid);
    String hash = CsvImportService.fingerprint(bytes);
    if (jdbc.queryForObject(
            "select count(*) from history_imports where user_id=? and fingerprint=?",
            Integer.class,
            uid,
            hash)
        > 0) throw new DomainException(409, "This history import was already saved.");
    var entries = preview(uid, bytes);
    List<SessionView> result = new ArrayList<>();
    int i = 0;
    for (var e : entries) {
      var items =
          e.exercises.stream()
              .map(
                  x ->
                      new TrainingService.HistoricalExercise(
                          imports.resolve(uid, x.name, x.type, true).id, x.sets))
              .toList();
      result.add(
          training.historical(
              uid,
              hash + ":" + (i++),
              new TrainingService.HistoricalInput(
                  e.name, e.started, e.finished, items, e.pausedSeconds)));
    }
    jdbc.update("insert into history_imports(user_id,fingerprint) values (?,?)", uid, hash);
    return result;
  }

  public String export(long uid, YearMonth month) {
    ZoneId zone = ZoneId.of(users.get(uid).timezone);
    var list =
        sessions
            .findByUserIdAndStatusAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDesc(
                uid,
                WorkoutSession.Status.COMPLETED,
                month.atDay(1).atStartOfDay(zone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant(),
                org.springframework.data.domain.Pageable.unpaged());
    StringBuilder out = new StringBuilder(String.join(",", HEADER) + "\n");
    for (var s : list)
      for (var e : s.exercises)
        for (var x : e.recordedSets()) {
          Object[] cells = {
            s.id,
            s.name,
            s.startedAt,
            s.finishedAt,
            e.position,
            e.name,
            e.metricType,
            x.weight,
            x.repetitions,
            x.durationSeconds,
            x.distance,
            x.rpe,
            x.notes,
            x.warmup,
            s.pausedSeconds
          };
          for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            out.append('"')
                .append(cells[i] == null ? "" : cells[i].toString().replace("\"", "\"\""))
                .append('"');
          }
          out.append('\n');
        }
    return out.toString();
  }

  private static BigDecimal decimal(String s) {
    return s.isBlank() ? null : Checks.decimal(s);
  }

  private static Integer integer(String s) {
    return s.isBlank() ? null : Checks.integer(s);
  }
}
