package dev.workout.telegram.bot;

import dev.workout.user.application.UserService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
  public record Delivery(long userId, long chatId, Long messageId, long revision, String screen) {}

  private final BotStateRepository states;
  private final UserService users;
  private final Clock clock;

  public OutboxService(BotStateRepository s, UserService u, Clock c) {
    states = s;
    users = u;
    clock = c;
  }

  @Transactional(readOnly = true)
  public List<Delivery> pending() {
    return states
        .findTop20ByPendingTrueAndRetryAtLessThanEqualOrderByRetryAtAsc(clock.instant())
        .stream()
        .map(s -> new Delivery(s.userId, s.chatId, s.messageId, s.revision, s.screen))
        .toList();
  }

  @Transactional
  public void delivered(Delivery delivery, long messageId) {
    users.lock(delivery.userId());
    BotState s = states.findById(delivery.userId()).orElseThrow();
    s.messageId = messageId;
    if (s.revision == delivery.revision()) s.pending = false;
  }

  @Transactional
  public void retry(Delivery delivery, int seconds, boolean blocked) {
    users.lock(delivery.userId());
    BotState s = states.findById(delivery.userId()).orElseThrow();
    if (s.revision == delivery.revision()) {
      s.retryAt = clock.instant().plusSeconds(Math.max(1, seconds));
      if (blocked) s.pending = false;
    }
  }
}
