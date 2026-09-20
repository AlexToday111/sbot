package dev.workout.workout.api;

import dev.workout.user.application.UserService;
import dev.workout.workout.application.*;
import dev.workout.workout.application.WorkoutDtos.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
  public record Start(long templateId) {}

  public record Position(int position) {}

  private final SessionService sessions;
  private final UserService users;

  public SessionController(SessionService s, UserService u) {
    sessions = s;
    users = u;
  }

  @PostMapping
  public SessionView start(
      @RequestAttribute long telegramId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Start input) {
    return sessions.start(uid(telegramId), input.templateId(), key);
  }

  @GetMapping("/active")
  public Optional<SessionView> active(@RequestAttribute long telegramId) {
    return sessions.active(uid(telegramId));
  }

  @GetMapping("/{id}")
  public SessionView get(@RequestAttribute long telegramId, @PathVariable long id) {
    return sessions.get(uid(telegramId), id);
  }

  @PostMapping("/{id}/sets")
  public SessionView add(
      @RequestAttribute long telegramId,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody SetInput input) {
    return sessions.addSet(uid(telegramId), id, input, key);
  }

  @DeleteMapping("/{id}/sets/{setId}")
  public SessionView undo(
      @RequestAttribute long telegramId, @PathVariable long id, @PathVariable long setId) {
    return sessions.undo(uid(telegramId), id, setId);
  }

  @PatchMapping("/{id}/position")
  public SessionView position(
      @RequestAttribute long telegramId, @PathVariable long id, @RequestBody Position input) {
    return sessions.navigate(uid(telegramId), id, input.position());
  }

  @PostMapping("/{id}/finish")
  public SessionView finish(@RequestAttribute long telegramId, @PathVariable long id) {
    return sessions.finish(uid(telegramId), id);
  }

  @PostMapping("/{id}/cancel")
  public SessionView cancel(@RequestAttribute long telegramId, @PathVariable long id) {
    return sessions.cancel(uid(telegramId), id);
  }

  @PostMapping("/{id}/repeat")
  public SessionView repeat(
      @RequestAttribute long telegramId,
      @PathVariable long id,
      @RequestHeader("Idempotency-Key") String key) {
    return sessions.repeat(uid(telegramId), id, key);
  }

  private long uid(long telegramId) {
    return users.byTelegram(telegramId).id;
  }
}
