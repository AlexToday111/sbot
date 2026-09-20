package dev.workout.telegram.bot;

import com.fasterxml.jackson.databind.*;
import dev.workout.telegram.message.Screen;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TelegramClient {
  public static class ApiException extends RuntimeException {
    public final int code, retryAfter;
    public final boolean notModified, missingMessage;

    ApiException(String method, int code, int retryAfter, String description) {
      super("Telegram " + method + " failed (code " + code + ")");
      this.code = code;
      this.retryAfter = retryAfter;
      notModified = description.contains("message is not modified");
      missingMessage =
          description.contains("message to edit not found")
              || description.contains("message can't be edited");
    }
  }

  private final ObjectMapper json;
  private final HttpClient http;
  private final String endpoint;

  public TelegramClient(
      ObjectMapper json,
      @Value("${app.telegram.token}") String token,
      @Value("${app.telegram.base-url}") String baseUrl,
      @Value("${app.telegram.enabled}") boolean enabled) {
    if (enabled && token.isBlank())
      throw new IllegalArgumentException(
          "TELEGRAM_BOT_TOKEN is required when TELEGRAM_ENABLED=true");
    this.json = json;
    endpoint = baseUrl + "/bot" + token + "/";
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public JsonNode call(String method, Object body, boolean poll) {
    try {
      var request =
          HttpRequest.newBuilder(URI.create(endpoint + method))
              .timeout(Duration.ofSeconds(poll ? 35 : 12))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      var data = json.readTree(response.body());
      if (!data.path("ok").asBoolean())
        throw new ApiException(
            method,
            data.path("error_code").asInt(response.statusCode()),
            data.path("parameters").path("retry_after").asInt(3),
            data.path("description").asText());
      return data.path("result");
    } catch (ApiException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ApiException(method, 0, 3, "interrupted");
    } catch (Exception ex) {
      // Never attach HTTP exceptions: their URI contains the bot credential.
      throw new ApiException(method, 0, 3, "transport failure");
    }
  }

  public JsonNode updates(long offset) {
    return call(
        "getUpdates",
        Map.of(
            "offset",
            offset,
            "timeout",
            25,
            "limit",
            30,
            "allowed_updates",
            List.of("message", "callback_query")),
        true);
  }

  public void answer(String id) {
    call("answerCallbackQuery", Map.of("callback_query_id", id), false);
  }

  public long deliver(long chatId, Long messageId, long revision, Screen screen) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("chat_id", chatId);
    payload.put("text", screen.text());
    var keyboard =
        screen.rows().stream()
            .map(
                row ->
                    row.stream()
                        .map(
                            button -> {
                              String callback = revision + "|" + button.action();
                              if (callback.getBytes(StandardCharsets.UTF_8).length > 64)
                                throw new IllegalStateException("Callback exceeds Telegram limit");
                              return Map.of("text", button.text(), "callback_data", callback);
                            })
                        .toList())
            .toList();
    payload.put("reply_markup", Map.of("inline_keyboard", keyboard));
    if (messageId != null) {
      payload.put("message_id", messageId);
      try {
        call("editMessageText", payload, false);
        return messageId;
      } catch (ApiException ex) {
        if (ex.notModified) return messageId;
        if (!ex.missingMessage) throw ex;
      }
      payload.remove("message_id");
    }
    return call("sendMessage", payload, false).path("message_id").asLong();
  }

  public void registerCommands() {
    call(
        "setMyCommands",
        Map.of(
            "commands",
            List.of(
                Map.of("command", "start", "description", "Open or recover your workout"),
                Map.of("command", "menu", "description", "Main dashboard"),
                Map.of("command", "workout", "description", "Choose or create a workout"),
                Map.of("command", "history", "description", "Completed workouts"),
                Map.of("command", "progress", "description", "Training progress"),
                Map.of("command", "cancel", "description", "Leave the current input flow"))),
        false);
  }
}
