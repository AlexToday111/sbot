package dev.workout.user.api;

import dev.workout.user.application.UserService;
import dev.workout.user.domain.User;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
public class UserController {
  public record Profile(
      long id, long telegramUserId, String firstName, String timezone, String language) {}

  public record Registration(String username, String firstName) {}

  public record Settings(String timezone) {}

  private final UserService users;

  public UserController(UserService users) {
    this.users = users;
  }

  @PostMapping
  public Profile register(@RequestAttribute long telegramId, @RequestBody Registration body) {
    var u = users.register(telegramId, body.username(), body.firstName());
    users.onboard(u.id);
    return view(u);
  }

  @GetMapping
  public Profile me(@RequestAttribute long telegramId) {
    return view(users.byTelegram(telegramId));
  }

  @PatchMapping
  public Profile settings(@RequestAttribute long telegramId, @RequestBody Settings body) {
    return view(users.settings(users.byTelegram(telegramId).id, body.timezone()));
  }

  private Profile view(User u) {
    return new Profile(u.id, u.telegramUserId, u.firstName, u.timezone, u.language);
  }
}
