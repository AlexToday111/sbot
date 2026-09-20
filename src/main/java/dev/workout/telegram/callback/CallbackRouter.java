package dev.workout.telegram.callback;

import dev.workout.common.DomainException;
import dev.workout.telegram.handler.*;
import dev.workout.telegram.message.Screen;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CallbackRouter {
  private final Map<String, CallbackHandler> routes = new HashMap<>();

  public CallbackRouter(List<CallbackHandler> handlers) {
    for (var h : handlers)
      if (routes.put(h.prefix(), h) != null) throw new IllegalStateException("Duplicate route");
  }

  public Screen route(Interaction c, String action) {
    String[] parts = action.split(":");
    CallbackHandler handler = routes.get(parts[0]);
    if (handler == null)
      throw new DomainException("This button is no longer available. Open /menu.");
    try {
      return handler.handle(c, parts);
    } catch (NumberFormatException | IndexOutOfBoundsException ex) {
      throw new DomainException("This button is no longer available. Open /menu.");
    }
  }

  public Screen text(Interaction c, String text) {
    return routes.values().stream()
        .filter(h -> h.accepts(c.state().flow))
        .findFirst()
        .orElseThrow(() -> new DomainException("Choose an action using the buttons below."))
        .text(c, text);
  }
}
