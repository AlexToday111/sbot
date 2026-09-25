package dev.workout.telegram.handler;

import static dev.workout.common.I18n.t;
import static dev.workout.telegram.message.Screen.b;

import dev.workout.common.*;
import dev.workout.telegram.callback.CallbackHandler;
import dev.workout.telegram.message.*;
import dev.workout.workout.application.*;
import java.time.*;
import org.springframework.stereotype.Component;

@Component
public class HistoryHandler implements CallbackHandler {
  private final SessionService sessions;
  private final Clock clock;

  public HistoryHandler(SessionService s, Clock c) {
    sessions = s;
    clock = c;
  }

  public String prefix() {
    return "history";
  }

  public Screen handle(Interaction c, String[] p) {
    c.flow(Flow.HOME);
    return switch (p[1]) {
      case "current" -> month(c, YearMonth.now(clock.withZone(ZoneId.of(c.user().timezone))), 0);
      case "month" -> month(c, YearMonth.parse(p[2]), Integer.parseInt(p[3]));
      case "summary" -> SessionScreens.summary(sessions.get(c.uid(), Long.parseLong(p[2])));
      case "detail" ->
          SessionScreens.detail(
              sessions.get(c.uid(), Long.parseLong(p[2])),
              ZoneId.of(c.user().timezone),
              Integer.parseInt(p[3]),
              Integer.parseInt(p[4]));
      default -> throw new DomainException(t("Unknown history action."));
    };
  }

  private Screen month(Interaction c, YearMonth month, int page) {
    var items = sessions.history(c.uid(), month, page);
    var b = Screen.title(t("◷ Workout history\n\n") + month);
    if (items.isEmpty()) b.line(t("No completed workouts in this page."));
    items.forEach(
        s ->
            b.button(
                s.startedAt().atZone(ZoneId.of(c.user().timezone)).getDayOfMonth()
                    + " · "
                    + s.name(),
                "history:summary:" + s.id()));
    b.row(
        b(t("+ Past workout"), "train:historical:0"),
        b(t("Import history"), "train:historyimport"));
    b.button(t("Export CSV"), "train:export:" + month);
    return b.pages(page, items.size() == 8, "history:month:" + month + ":")
        .row(
            b("‹ " + month.minusMonths(1), "history:month:" + month.minusMonths(1) + ":0"),
            b(month.plusMonths(1) + " ›", "history:month:" + month.plusMonths(1) + ":0"))
        .home()
        .build();
  }
}
