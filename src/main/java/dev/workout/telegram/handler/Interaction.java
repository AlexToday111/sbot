package dev.workout.telegram.handler;

import dev.workout.telegram.bot.BotState;
import dev.workout.user.domain.User;

public record Interaction(User user, BotState state, FlowData data, long updateId) {
  public long uid() {
    return user.id;
  }

  public String key(String operation) {
    return "tg:" + updateId + ":" + operation;
  }

  public void flow(Flow flow) {
    state.flow = flow;
  }
}
