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
    public final boolean notModified, missingMessage, mediaMessage;

    ApiException(String method, int code, int retryAfter, String description) {
      super("Telegram " + method + " failed (code " + code + ")");
      this.code = code;
      this.retryAfter = retryAfter;
      mediaMessage = description.contains("there is no text in the message to edit");
      notModified = description.contains("message is not modified");
      missingMessage =
          description.contains("message to edit not found")
              || description.contains("message can't be edited");
    }
  }

  private final ObjectMapper json;
  private final HttpClient http;
  private final String endpoint;
  private final String fileEndpoint;

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
    fileEndpoint = baseUrl + "/file/bot" + token + "/";
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

  public byte[] downloadCsv(JsonNode document) {
    int limit = dev.workout.workout.application.WorkoutCsv.MAX_BYTES;
    if (!document.path("file_name").asText().toLowerCase(Locale.ROOT).endsWith(".csv"))
      throw new dev.workout.common.DomainException("Send a .csv file as a document.");
    if (document.path("file_size").asLong() > limit)
      throw new dev.workout.common.DomainException("CSV must be at most 64 KiB.");
    var file = call("getFile", Map.of("file_id", document.path("file_id").asText()), false);
    String path = file.path("file_path").asText();
    if (!path.matches("[A-Za-z0-9_/-]+\\.[A-Za-z0-9]+")
        || path.contains("..")
        || path.startsWith("/")) throw new ApiException("getFile", 0, 3, "invalid path");
    try {
      var request =
          HttpRequest.newBuilder(URI.create(fileEndpoint + path))
              .timeout(Duration.ofSeconds(12))
              .GET()
              .build();
      // A bounded body subscriber avoids both unbounded allocation and blocking InputStream reads.
      var response = http.send(request, info -> new LimitedBodySubscriber(limit));
      if (response.statusCode() != 200)
        throw new ApiException("downloadFile", response.statusCode(), 3, "download failed");
      if (response.body().length > limit)
        throw new dev.workout.common.DomainException("CSV must be at most 64 KiB.");
      return response.body();
    } catch (dev.workout.common.DomainException | ApiException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ApiException("downloadFile", 0, 3, "interrupted");
    } catch (Exception ex) {
      throw new ApiException("downloadFile", 0, 3, "transport failure");
    }
  }

  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final java.util.concurrent.CompletableFuture<byte[]> result =
        new java.util.concurrent.CompletableFuture<>();
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    private final int limit;
    private java.util.concurrent.Flow.Subscription subscription;

    LimitedBodySubscriber(int limit) {
      this.limit = limit;
    }

    public java.util.concurrent.CompletionStage<byte[]> getBody() {
      return result;
    }

    public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
      subscription = s;
      s.request(1);
    }

    public void onNext(List<java.nio.ByteBuffer> buffers) {
      for (var buffer : buffers) {
        int count = Math.min(buffer.remaining(), limit + 1 - bytes.size());
        byte[] part = new byte[count];
        buffer.get(part);
        bytes.writeBytes(part);
        if (bytes.size() > limit) {
          subscription.cancel();
          result.complete(bytes.toByteArray());
          return;
        }
      }
      subscription.request(1);
    }

    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    public void onComplete() {
      result.complete(bytes.toByteArray());
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
                              Map<String, String> item = new HashMap<>();
                              item.put("text", button.text());
                              item.put("callback_data", callback);
                              if (button.style() != null) item.put("style", button.style());
                              return item;
                            })
                        .toList())
            .toList();
    payload.put("reply_markup", Map.of("inline_keyboard", keyboard));
    if (screen.attachment() != null)
      return deliverDocument(chatId, messageId, screen, payload.get("reply_markup"));
    if (screen.chart() != null)
      return deliverChart(chatId, messageId, screen, payload.get("reply_markup"));
    boolean replaceMedia = false;
    if (messageId != null) {
      payload.put("message_id", messageId);
      try {
        call("editMessageText", payload, false);
        return messageId;
      } catch (ApiException ex) {
        if (ex.notModified) return messageId;
        if (!ex.missingMessage && !ex.mediaMessage) throw ex;
        replaceMedia = ex.mediaMessage;
      }
      payload.remove("message_id");
    }
    long sent = call("sendMessage", payload, false).path("message_id").asLong();
    if (replaceMedia) {
      try {
        call("deleteMessage", Map.of("chat_id", chatId, "message_id", messageId), false);
      } catch (ApiException ignored) {
        /* Delivery succeeded; an old chart must not cause duplicate sends. */
      }
    }
    return sent;
  }

  private long deliverDocument(long chatId, Long messageId, Screen screen, Object keyboard) {
    var a = screen.attachment();
    var fields = new HashMap<String, Object>();
    fields.put("chat_id", chatId);
    fields.put("reply_markup", keyboard);
    fields.put("document", "attach://chart");
    fields.put("caption", screen.text());
    long sent =
        upload(
                "sendDocument",
                fields,
                a.content().getBytes(StandardCharsets.UTF_8),
                a.filename(),
                "text/csv; charset=utf-8")
            .path("message_id")
            .asLong();
    if (messageId != null)
      try {
        call("deleteMessage", Map.of("chat_id", chatId, "message_id", messageId), false);
      } catch (ApiException ignored) {
      }
    return sent;
  }

  private long deliverChart(long chatId, Long messageId, Screen screen, Object keyboard) {
    if (screen.text().length() > 1024)
      throw new IllegalArgumentException("Chart caption exceeds Telegram limit");
    byte[] png = dev.workout.telegram.message.ChartRenderer.render(screen.chart());
    var payload = new HashMap<String, Object>();
    payload.put("chat_id", chatId);
    payload.put("reply_markup", keyboard);
    if (messageId != null) {
      payload.put("message_id", messageId);
      payload.put(
          "media", Map.of("type", "photo", "media", "attach://chart", "caption", screen.text()));
      try {
        upload("editMessageMedia", payload, png);
        return messageId;
      } catch (ApiException ex) {
        if (ex.notModified) return messageId;
        if (!ex.missingMessage) throw ex;
      }
      payload.remove("message_id");
      payload.remove("media");
    }
    payload.put("photo", "attach://chart");
    payload.put("caption", screen.text());
    return upload("sendPhoto", payload, png).path("message_id").asLong();
  }

  private JsonNode upload(String method, Map<String, Object> fields, byte[] png) {
    return upload(method, fields, png, "progress.png", "image/png");
  }

  private JsonNode upload(
      String method, Map<String, Object> fields, byte[] png, String filename, String mime) {
    if (!filename.matches("[A-Za-z0-9._-]+"))
      throw new IllegalArgumentException("Invalid attachment filename");
    try {
      String boundary = "workout-" + UUID.randomUUID();
      var body = new java.io.ByteArrayOutputStream();
      for (var entry : fields.entrySet()) {
        String value =
            entry.getValue() instanceof String str
                ? str
                : json.writeValueAsString(entry.getValue());
        body.write(
            ("--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\""
                    + entry.getKey()
                    + "\"\r\n\r\n"
                    + value
                    + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
      }
      body.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\"chart\"; filename=\""
                  + filename
                  + "\"\r\nContent-Type: "
                  + mime
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      body.write(png);
      body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      var request =
          HttpRequest.newBuilder(URI.create(endpoint + method))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
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
      throw new ApiException(method, 0, 3, "transport failure");
    }
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
    call(
        "setMyCommands",
        Map.of(
            "language_code",
            "ru",
            "commands",
            List.of(
                Map.of("command", "start", "description", "Открыть или продолжить тренировку"),
                Map.of("command", "menu", "description", "Главное меню"),
                Map.of("command", "workout", "description", "Выбрать или создать тренировку"),
                Map.of("command", "history", "description", "Завершённые тренировки"),
                Map.of("command", "progress", "description", "Прогресс тренировок"),
                Map.of("command", "cancel", "description", "Отменить текущий ввод"))),
        false);
  }
}
