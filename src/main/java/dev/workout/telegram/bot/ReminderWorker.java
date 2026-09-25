package dev.workout.telegram.bot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.telegram.enabled", havingValue = "true")
public class ReminderWorker {
  private final ReminderService reminders;
  private final TelegramClient client;
  private java.time.Instant retryAfter = java.time.Instant.EPOCH;

  public ReminderWorker(ReminderService reminders, TelegramClient client) {
    this.reminders = reminders;
    this.client = client;
  }

  @Scheduled(fixedDelay = 5000)
  public void run() {
    if (java.time.Instant.now().isBefore(retryAfter)) return;
    try {
      for (long uid : reminders.dueUsers()) reminders.deliver(uid, client);
    } catch (TelegramClient.ApiException ex) {
      retryAfter = java.time.Instant.now().plusSeconds(Math.max(5, ex.retryAfter));
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Reminder delivery: {}", ex.getMessage());
    }
  }
}
