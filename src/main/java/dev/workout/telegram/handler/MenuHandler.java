package dev.workout.telegram.handler;

import dev.workout.analytics.application.AnalyticsService;
import dev.workout.telegram.callback.CallbackHandler;
import dev.workout.telegram.message.*;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.SessionService;
import java.time.*;
import org.springframework.stereotype.Component;

@Component
public class MenuHandler implements CallbackHandler {
  private final UserService users;
  private final SessionService sessions;
  private final AnalyticsService analytics;
  private final Clock clock;

  public MenuHandler(UserService u, SessionService s, AnalyticsService a, Clock c) {
    users = u;
    sessions = s;
    analytics = a;
    clock = c;
  }

  public String prefix() {
    return "menu";
  }

  public Screen welcome() {
    return Screen.title(
            "🏋 Workout Tracker\n\nPlan your training.\nTrack every set.\nSee your progress.")
        .button("Start", "menu:onboard")
        .build();
  }

  public Screen home(Interaction c) {
    c.flow(Flow.HOME);
    var today = LocalDate.now(clock.withZone(ZoneId.of(c.user().timezone)));
    var totals = analytics.totals(c.uid(), today, today.plusDays(1), ZoneId.of(c.user().timezone));
    var week = analytics.report(c.uid(), "week").current();
    var b =
        Screen.title(
                "🏋 TRAINING\n\nToday\n"
                    + (totals.workouts() == 0
                        ? "No workout completed yet."
                        : totals.workouts() + " workout(s) completed."))
            .line(
                "\nThis week\n"
                    + week.workouts()
                    + " workouts · "
                    + week.exercises()
                    + " exercises · "
                    + week.sets()
                    + " sets");
    sessions
        .active(c.uid())
        .ifPresent(
            s ->
                b.line("\nActive: " + s.name())
                    .button("▶ Continue workout", "session:view:" + s.id()));
    if (c.data().name != null) b.button("Continue template draft", "workout:draft");
    return b.button("▶ Start workout", "workout:list:0")
        .button("☷ Workouts", "workout:list:0")
        .button("▥ Progress", "progress:menu")
        .button("◷ History", "history:month:" + YearMonth.from(today) + ":0")
        .button("⚙ Settings", "menu:settings")
        .build();
  }

  public Screen start(Interaction c) {
    if (!c.user().onboarded) return welcome();
    var active = sessions.active(c.uid());
    if (active.isEmpty()) return home(c);
    var s = active.get();
    c.flow(Flow.HOME);
    return Screen.title(
            "You have an active workout:\n\n"
                + s.name()
                + "\nStarted "
                + Format.duration(Duration.between(s.startedAt(), clock.instant()).getSeconds())
                + " ago.")
        .button("▶ Continue workout", "session:view:" + s.id())
        .button("Finish workout", "session:finishask:" + s.id())
        .button("Cancel workout", "session:cancelask:" + s.id())
        .home()
        .build();
  }

  public Screen handle(Interaction c, String[] p) {
    return switch (p[1]) {
      case "onboard" -> {
        users.onboard(c.uid());
        yield home(c);
      }
      case "home" -> home(c);
      case "settings" -> settings(c);
      case "timezone" -> {
        c.flow(Flow.TIMEZONE);
        yield Screen.title(
                "Enter your timezone\n\nFor example: Europe/Moscow, Europe/London, America/New_York or UTC.\n\nThis controls calendar periods and workout dates.")
            .button("← Settings", "menu:settings")
            .home()
            .build();
      }
      default -> throw new dev.workout.common.DomainException("Unknown menu action.");
    };
  }

  private Screen settings(Interaction c) {
    c.flow(Flow.HOME);
    return Screen.title(
            "⚙ Settings\n\nTimezone: " + c.user().timezone + "\nUnits: kg · km\nLanguage: English")
        .button("Change timezone", "menu:timezone")
        .home()
        .build();
  }

  public boolean accepts(Flow flow) {
    return flow == Flow.TIMEZONE;
  }

  public Screen text(Interaction c, String text) {
    users.settings(c.uid(), text.trim());
    return settings(c);
  }
}
