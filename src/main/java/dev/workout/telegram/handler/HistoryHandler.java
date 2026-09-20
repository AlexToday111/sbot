package dev.workout.telegram.handler;

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
      default -> throw new DomainException("Unknown history action.");
    };
  }

  private Screen month(Interaction c, YearMonth month, int page) {
    var items = sessions.history(c.uid(), month, page);
    var b = Screen.title("◷ Workout history\n\n" + month);
    if (items.isEmpty()) b.line("No completed workouts in this page.");
    items.forEach(
        s ->
            b.button(
                s.startedAt().atZone(ZoneId.of(c.user().timezone)).getDayOfMonth()
                    + " · "
                    + s.name(),
                "history:summary:" + s.id()));
    if (page > 0) b.button("← Previous page", "history:month:" + month + ":" + (page - 1));
    if (items.size() == 8) b.button("Next page →", "history:month:" + month + ":" + (page + 1));
    return b.button("← Previous month", "history:month:" + month.minusMonths(1) + ":0")
        .button("Next month →", "history:month:" + month.plusMonths(1) + ":0")
        .home()
        .build();
  }
}
