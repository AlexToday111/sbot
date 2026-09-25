package dev.workout.workout.application;

import dev.workout.common.*;
import dev.workout.exercise.domain.MetricType;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Bounded CSV reader: comma/semicolon, quoted fields, escaped quotes and UTF-8 BOM. */
public final class WorkoutCsv {
  public static final int MAX_BYTES = 65536;
  public static final List<String> HEADER =
      List.of("workout", "exercise", "metric_type", "sets", "reps", "weight", "rest_seconds");

  public record Row(
      String exercise,
      MetricType type,
      Integer sets,
      Integer reps,
      BigDecimal weight,
      Integer rest,
      Integer durationSeconds,
      BigDecimal distance,
      List<WorkoutDtos.SetPlan> plan) {}

  public record Parsed(String name, List<Row> rows) {}

  private WorkoutCsv() {}

  public static Parsed parse(byte[] bytes) {
    var records = table(bytes);
    var extended = new ArrayList<>(HEADER);
    extended.addAll(List.of("duration_seconds", "distance", "set_plan"));
    if (records.isEmpty() || !(records.get(0).equals(HEADER) || records.get(0).equals(extended)))
      throw new DomainException(
          "CSV header must have 7 base columns, or all 10 extended columns in the documented order.");
    int columns = records.get(0).size();
    if (records.size() < 2 || records.size() > 31)
      throw new DomainException("A workout needs 1 to 30 exercises.");
    String name = null;
    List<Row> rows = new ArrayList<>();
    for (int i = 1; i < records.size(); i++) {
      try {
        var cells = records.get(i);
        if (cells.size() != columns)
          throw new DomainException("Column count must match the header.");
        String currentName = Checks.name(cells.get(0));
        if (name != null && !name.equals(currentName))
          throw new DomainException("Use the same workout name in every row.");
        name = currentName;
        String exercise = Checks.name(cells.get(1));
        MetricType type;
        try {
          type = MetricType.valueOf(cells.get(2).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          throw new DomainException("Metric type must be STRENGTH, BODYWEIGHT, CARDIO or TIMED.");
        }
        Integer sets = integer(cells.get(3)),
            reps = integer(cells.get(4)),
            rest = integer(cells.get(6));
        BigDecimal weight = empty(cells.get(5)) ? null : Checks.decimal(cells.get(5));
        Checks.range(sets, 1, 100, "Sets");
        Checks.range(reps, 0, 1000, "Repetitions");
        Checks.range(rest, 0, 3600, "Rest");
        Checks.range(weight, 0, 2000, "Weight");
        Checks.scale(weight, 3, "Weight");
        Integer duration = columns == 10 ? integer(cells.get(7)) : null;
        BigDecimal distance =
            columns == 10 && !empty(cells.get(8)) ? Checks.decimal(cells.get(8)) : null;
        List<WorkoutDtos.SetPlan> plan = List.of();
        if (columns == 10 && !empty(cells.get(9))) {
          try {
            plan =
                new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(
                        cells.get(9),
                        new com.fasterxml.jackson.core.type.TypeReference<
                            List<WorkoutDtos.SetPlan>>() {});
          } catch (Exception ex) {
            throw new DomainException("Invalid set_plan JSON.");
          }
        }
        Plans.validate(
            type, new WorkoutDtos.Target(1, sets, reps, weight, rest, duration, distance, plan));
        rows.add(new Row(exercise, type, sets, reps, weight, rest, duration, distance, plan));
      } catch (DomainException ex) {
        throw new DomainException(I18n.t("CSV row ") + (i + 1) + ": " + ex.getMessage());
      }
    }
    return new Parsed(name, List.copyOf(rows));
  }

  public static List<List<String>> table(byte[] bytes) {
    if (bytes.length > MAX_BYTES) throw new DomainException("CSV must be at most 64 KiB.");
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException ex) {
      throw new DomainException("Save the CSV file as UTF-8.");
    }
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    return records(text, text.lines().findFirst().orElse("").contains(";") ? ';' : ',');
  }

  private static boolean empty(String s) {
    return s.isBlank() || s.equals("-");
  }

  private static Integer integer(String s) {
    return empty(s) ? null : Checks.integer(s);
  }

  private static List<List<String>> records(String text, char delimiter) {
    List<List<String>> result = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false, closed = false;
    for (int i = 0; i <= text.length(); i++) {
      char ch = i == text.length() ? '\n' : text.charAt(i);
      if (quoted) {
        if (i == text.length()) throw new DomainException("Unclosed quote in CSV.");
        if (ch == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            quoted = false;
            closed = true;
          }
        } else field.append(ch);
      } else if (ch == delimiter || ch == '\n' || ch == '\r') {
        row.add(field.toString().strip());
        field.setLength(0);
        closed = false;
        if (ch != delimiter) {
          if (!(row.size() == 1 && row.get(0).isEmpty())) result.add(List.copyOf(row));
          row.clear();
          if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
          if (result.size() > 3001) throw new DomainException("A workout needs 1 to 30 exercises.");
        }
      } else if (ch == '"' && field.length() == 0 && !closed) quoted = true;
      else {
        if (closed || ch == '"') throw new DomainException("Invalid quoting in CSV.");
        field.append(ch);
      }
    }
    return result;
  }
}
