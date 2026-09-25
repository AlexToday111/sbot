package dev.workout.workout.api;

import dev.workout.user.application.UserService;
import dev.workout.workout.application.*;
import dev.workout.workout.application.WorkoutDtos.*;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/training")
public class TrainingController {
  private final TrainingService training;
  private final PlanningService planning;
  private final UserService users;
  private final HistoryTransferService history;

  public TrainingController(
      TrainingService training,
      PlanningService planning,
      UserService users,
      HistoryTransferService history) {
    this.training = training;
    this.planning = planning;
    this.users = users;
    this.history = history;
  }

  @PostMapping(value = "/history/import", consumes = "multipart/form-data")
  public List<SessionView> importHistory(
      @RequestAttribute long telegramId,
      @RequestParam("file") org.springframework.web.multipart.MultipartFile file)
      throws java.io.IOException {
    if (file.getSize() > WorkoutCsv.MAX_BYTES)
      throw new dev.workout.common.DomainException("CSV must be at most 64 KiB.");
    return history.importCsv(uid(telegramId), file.getBytes());
  }

  @GetMapping(value = "/history/export", produces = "text/csv;charset=UTF-8")
  public org.springframework.http.ResponseEntity<String> exportHistory(
      @RequestAttribute long telegramId, @RequestParam String month) {
    YearMonth m;
    try {
      m = YearMonth.parse(month);
    } catch (DateTimeException ex) {
      throw new dev.workout.common.DomainException("Use a month in YYYY-MM format.");
    }
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=history-" + m + ".csv")
        .body(history.export(uid(telegramId), m));
  }

  private long uid(long telegramId) {
    return users.byTelegram(telegramId).id;
  }

  public record Pause(boolean paused) {}

  public record Rest(int seconds, boolean extend) {}

  public record Finish(Instant finishedAt) {}

  public record Change(Long itemId, Long exerciseId, Integer position, Boolean skipped) {}

  public record Schedule(
      Long id, long templateId, LocalDateTime localStart, int repeatWeeks, boolean reminders) {}

  @PutMapping("/sessions/{id}/sets/{setId}")
  public SessionView edit(
      @RequestAttribute long telegramId,
      @PathVariable long id,
      @PathVariable long setId,
      @RequestBody SetInput body) {
    return training.editSet(uid(telegramId), id, setId, body);
  }

  @PostMapping("/sessions/{id}/pause")
  public SessionView pause(
      @RequestAttribute long telegramId, @PathVariable long id, @RequestBody Pause body) {
    return training.pause(uid(telegramId), id, body.paused());
  }

  @PostMapping("/sessions/{id}/rest")
  public SessionView rest(
      @RequestAttribute long telegramId, @PathVariable long id, @RequestBody Rest body) {
    return training.rest(uid(telegramId), id, body.seconds(), body.extend());
  }

  @PostMapping("/sessions/{id}/exercises")
  public SessionView change(
      @RequestAttribute long telegramId, @PathVariable long id, @RequestBody Change body) {
    return training.changeExercise(
        uid(telegramId), id, body.itemId(), body.exerciseId(), body.position(), body.skipped());
  }

  @PostMapping("/sessions/{id}/finish-at")
  public SessionView finish(
      @RequestAttribute long telegramId, @PathVariable long id, @RequestBody Finish body) {
    return training.finishAt(uid(telegramId), id, body.finishedAt());
  }

  @PostMapping("/history")
  public SessionView history(
      @RequestAttribute long telegramId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody TrainingService.HistoricalInput body) {
    return training.historical(uid(telegramId), key, body);
  }

  @GetMapping("/sessions/{id}/comparison")
  public List<TrainingService.Comparison> comparison(
      @RequestAttribute long telegramId, @PathVariable long id) {
    return training.comparison(uid(telegramId), id);
  }

  @PostMapping("/sessions/{id}/template")
  public TemplateView template(
      @RequestAttribute long telegramId, @PathVariable long id, @RequestParam String name) {
    return training.saveAsTemplate(uid(telegramId), id, name);
  }

  @GetMapping("/settings")
  public PlanningService.Preferences preferences(@RequestAttribute long telegramId) {
    return planning.preferences(uid(telegramId));
  }

  @PutMapping("/settings")
  public PlanningService.Preferences preferences(
      @RequestAttribute long telegramId, @RequestBody PlanningService.Preferences body) {
    return planning.preferences(uid(telegramId), body);
  }

  @GetMapping("/goal")
  public PlanningService.Goal goal(@RequestAttribute long telegramId) {
    return planning.goal(uid(telegramId));
  }

  @GetMapping("/schedule")
  public List<PlanningService.Appointment> schedule(@RequestAttribute long telegramId) {
    return planning.schedule(uid(telegramId));
  }

  @PostMapping("/schedule")
  public PlanningService.Appointment schedule(
      @RequestAttribute long telegramId, @RequestBody Schedule body) {
    return planning.schedule(
        uid(telegramId),
        body.id(),
        body.templateId(),
        body.localStart(),
        body.repeatWeeks(),
        body.reminders());
  }

  @DeleteMapping("/schedule/{id}")
  public void cancel(@RequestAttribute long telegramId, @PathVariable long id) {
    planning.cancel(uid(telegramId), id);
  }

  @GetMapping("/suggestions/{id}")
  public PlanningService.Suggestion suggest(
      @RequestAttribute long telegramId, @PathVariable long id) {
    return planning.suggest(uid(telegramId), id);
  }
}
