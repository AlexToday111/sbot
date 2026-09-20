package dev.workout.analytics.api;

import dev.workout.analytics.application.AnalyticsService;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.*;
import dev.workout.workout.application.WorkoutDtos.SessionView;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ReportController {
  private final AnalyticsService analytics;
  private final SessionService sessions;
  private final UserService users;
  private final Clock clock;

  public ReportController(AnalyticsService a, SessionService s, UserService u, Clock c) {
    analytics = a;
    sessions = s;
    users = u;
    clock = c;
  }

  @GetMapping("/history")
  public List<SessionView> history(
      @RequestAttribute long telegramId,
      @RequestParam(required = false) String month,
      @RequestParam(defaultValue = "0") int page) {
    var u = users.byTelegram(telegramId);
    YearMonth m;
    try {
      m =
          month == null
              ? YearMonth.now(clock.withZone(ZoneId.of(u.timezone)))
              : YearMonth.parse(month);
    } catch (DateTimeException ex) {
      throw new dev.workout.common.DomainException("Use a month in YYYY-MM format.");
    }
    return sessions.history(u.id, m, page);
  }

  @GetMapping("/analytics/{period}")
  public AnalyticsService.Report report(
      @RequestAttribute long telegramId, @PathVariable String period) {
    String normalized =
        switch (period) {
          case "weekly" -> "week";
          case "monthly" -> "month";
          case "yearly" -> "year";
          default -> period;
        };
    return analytics.report(users.byTelegram(telegramId).id, normalized);
  }

  @GetMapping("/exercises/{id}/progress")
  public AnalyticsService.Progress progress(
      @RequestAttribute long telegramId,
      @PathVariable long id,
      @RequestParam(defaultValue = "quarter") String period) {
    return analytics.progress(users.byTelegram(telegramId).id, id, period);
  }

  @GetMapping("/exercises/{id}/history")
  public List<SessionView> exerciseHistory(
      @RequestAttribute long telegramId,
      @PathVariable long id,
      @RequestParam(defaultValue = "0") int page) {
    return sessions.exerciseHistory(users.byTelegram(telegramId).id, id, page);
  }
}
