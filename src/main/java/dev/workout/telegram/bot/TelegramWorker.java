package dev.workout.telegram.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workout.telegram.message.Screen;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.telegram.enabled", havingValue = "true")
public class TelegramWorker {
  private static final Logger log = LoggerFactory.getLogger(TelegramWorker.class);
  private final TelegramClient client;
  private final UpdateProcessor processor;
  private final OutboxService outbox;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private Instant pollAfter = Instant.EPOCH, sendAfter = Instant.EPOCH;
  private boolean commandsRegistered;

  public TelegramWorker(
      TelegramClient c, UpdateProcessor p, OutboxService o, ObjectMapper j, MeterRegistry m) {
    client = c;
    processor = p;
    outbox = o;
    json = j;
    metrics = m;
  }

  @Scheduled(fixedDelay = 500)
  public void poll() {
    if (Instant.now().isBefore(pollAfter)) return;
    try {
      if (!commandsRegistered) {
        client.registerCommands();
        commandsRegistered = true;
      }
      for (var update : client.updates(processor.offset())) {
        if (update.has("callback_query")) {
          try {
            client.answer(update.path("callback_query").path("id").asText());
          } catch (TelegramClient.ApiException ex) {
            log.debug("Callback acknowledgement expired or unavailable");
          }
        }
        processor.process(update);
        metrics.counter("workout.telegram.updates").increment();
      }
    } catch (TelegramClient.ApiException ex) {
      pollAfter = Instant.now().plusSeconds(Math.max(3, ex.retryAfter));
      log.warn("{}", ex.getMessage());
      metrics.counter("workout.telegram.errors", "operation", "poll").increment();
    } catch (Exception ex) {
      pollAfter = Instant.now().plusSeconds(5);
      log.error("Update processing failed; durable cursor will be retried", ex);
    }
  }

  @Scheduled(fixedDelay = 300)
  public void deliver() {
    if (Instant.now().isBefore(sendAfter)) return;
    try {
      for (var d : outbox.pending()) {
        try {
          long messageId =
              client.deliver(
                  d.chatId(),
                  d.messageId(),
                  d.revision(),
                  json.readValue(d.screen(), Screen.class));
          outbox.delivered(d, messageId);
        } catch (TelegramClient.ApiException ex) {
          log.warn("Screen delivery for user {}: {}", d.userId(), ex.getMessage());
          outbox.retry(d, ex.retryAfter, ex.code == 403);
          metrics.counter("workout.telegram.errors", "operation", "send").increment();
          if (ex.code == 429) {
            sendAfter = Instant.now().plusSeconds(ex.retryAfter);
            break;
          }
        }
      }
    } catch (Exception ex) {
      log.error("Screen delivery failed; pending screen retained", ex);
      sendAfter = Instant.now().plusSeconds(3);
    }
  }
}
