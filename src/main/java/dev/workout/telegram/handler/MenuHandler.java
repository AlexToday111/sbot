package dev.workout.telegram.handler;

import static dev.workout.common.I18n.t;

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
            t("🏋 Workout Tracker\n\nPlan your training.\nTrack every set.\nSee your progress."))
        .button(t("Start"), "menu:onboard")
        .button("Русский / English", "menu:language")
        .build();
  }

  public Screen home(Interaction c) {
    c.flow(Flow.HOME);
    var today = LocalDate.now(clock.withZone(ZoneId.of(c.user().timezone)));
    var totals = analytics.totals(c.uid(), today, today.plusDays(1), ZoneId.of(c.user().timezone));
    var week = analytics.report(c.uid(), "week").current();
    var b =
        Screen.title(
                t("🏋 TRAINING\n\nToday\n")
                    + (totals.workouts() == 0
                        ? t("No workout completed yet.")
                        : totals.workouts() + t(" workout(s) completed.")))
            .line(
                t("\nThis week\n")
                    + week.workouts()
                    + t(" workouts · ")
                    + week.exercises()
                    + t(" exercises · ")
                    + week.sets()
                    + t(" sets"));
    sessions
        .active(c.uid())
        .ifPresent(
            s ->
                b.line(t("\nActive: ") + s.name())
                    .button(t("▶ Continue workout"), "session:view:" + s.id()));
    if (c.data().name != null) b.button(t("Continue template draft"), "workout:draft");
    return b.button(t("▶ Start workout"), "workout:list:0")
        .button(t("☷ Workouts"), "workout:list:0")
        .button(t("▥ Progress"), "progress:menu")
        .button(t("◷ History"), "history:month:" + YearMonth.from(today) + ":0")
        .button(t("⚙ Settings"), "menu:settings")
        .build();
  }

  public Screen start(Interaction c) {
    if (!c.user().onboarded) return welcome();
    var active = sessions.active(c.uid());
    if (active.isEmpty()) return home(c);
    var s = active.get();
    c.flow(Flow.HOME);
    return Screen.title(
            t("You have an active workout:\n\n")
                + s.name()
                + t("\nStarted ")
                + Format.duration(Duration.between(s.startedAt(), clock.instant()).getSeconds())
                + t(" ago."))
        .button(t("▶ Continue workout"), "session:view:" + s.id())
        .button(t("Finish workout"), "session:finishask:" + s.id())
        .button(t("Cancel workout"), "session:cancelask:" + s.id())
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
      case "language" ->
          Screen.title("Выберите язык / Choose language")
              .button("Русский", "menu:setlanguage:ru")
              .button("English", "menu:setlanguage:en")
              .build();
      case "setlanguage" -> {
        users.language(c.uid(), p[2]);
        dev.workout.common.I18n.language(p[2]);
        yield c.user().onboarded ? settings(c) : welcome();
      }
      case "timezone" -> {
        c.flow(Flow.TIMEZONE);
        yield Screen.title(
                t(
                    "Enter your timezone\n\nFor example: Europe/Moscow, Europe/London, America/New_York or UTC.\n\nThis controls calendar periods and workout dates."))
            .button(t("← Settings"), "menu:settings")
            .home()
            .build();
      }
      default -> throw new dev.workout.common.DomainException(t("Unknown menu action."));
    };
  }

  private Screen settings(Interaction c) {
    c.flow(Flow.HOME);
    return Screen.title(
            t("⚙ Settings\n\nTimezone: ")
                + c.user().timezone
                + t("\nUnits: kg · km\nLanguage: ")
                + ("ru".equals(c.user().language) ? "Русский" : "English"))
        .button(t("Change timezone"), "menu:timezone")
        .button("Русский / English", "menu:language")
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
