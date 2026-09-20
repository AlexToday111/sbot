package dev.workout.telegram.callback;

import dev.workout.telegram.handler.*;
import dev.workout.telegram.message.Screen;

public interface CallbackHandler {
  String prefix();

  Screen handle(Interaction interaction, String[] parts);

  default boolean accepts(Flow flow) {
    return false;
  }

  default Screen text(Interaction interaction, String text) {
    throw new dev.workout.common.DomainException("Use the buttons to continue.");
  }
}
