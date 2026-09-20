package dev.workout.workout.api;

import dev.workout.user.application.UserService;
import dev.workout.workout.application.*;
import dev.workout.workout.application.WorkoutDtos.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workouts")
public class WorkoutController {
  private final TemplateService templates;
  private final UserService users;

  public WorkoutController(TemplateService t, UserService u) {
    templates = t;
    users = u;
  }

  @GetMapping
  public List<TemplateView> list(
      @RequestAttribute long telegramId, @RequestParam(defaultValue = "0") int page) {
    return templates.list(uid(telegramId), page);
  }

  @PostMapping
  public TemplateView create(@RequestAttribute long telegramId, @RequestBody TemplateInput input) {
    return templates.save(uid(telegramId), null, input);
  }

  @GetMapping("/{id}")
  public TemplateView get(@RequestAttribute long telegramId, @PathVariable long id) {
    return templates.get(uid(telegramId), id);
  }

  @PutMapping("/{id}")
  public TemplateView edit(
      @RequestAttribute long telegramId, @PathVariable long id, @RequestBody TemplateInput input) {
    return templates.save(uid(telegramId), id, input);
  }

  @DeleteMapping("/{id}")
  public void delete(@RequestAttribute long telegramId, @PathVariable long id) {
    templates.delete(uid(telegramId), id);
  }

  private long uid(long telegramId) {
    return users.byTelegram(telegramId).id;
  }
}
