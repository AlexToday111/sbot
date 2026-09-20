package dev.workout;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.workout.telegram.bot.TelegramClient;
import dev.workout.telegram.message.Screen;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class TelegramClientTest {
  HttpServer server;
  List<String> methods = new ArrayList<>(), bodies = new ArrayList<>();
  Deque<String> responses = new ArrayDeque<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          methods.add(exchange.getRequestURI().getPath());
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = responses.removeFirst().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  TelegramClient client() {
    return new TelegramClient(
        new ObjectMapper(),
        "test-secret",
        "http://127.0.0.1:" + server.getAddress().getPort(),
        true);
  }

  Screen screen() {
    return Screen.title("Bench <Press> & reps").button("Add set", "session:add:1:2").home().build();
  }

  @Test
  void editsExistingMessageAndVersionsButtonsWithoutHtmlParsing() throws Exception {
    responses.add("{\"ok\":true,\"result\":{\"message_id\":10}}");
    assertThat(client().deliver(1, 10L, 7, screen())).isEqualTo(10);
    assertThat(methods).containsExactly("/bottest-secret/editMessageText");
    var body = new ObjectMapper().readTree(bodies.get(0));
    assertThat(
            body.path("reply_markup")
                .path("inline_keyboard")
                .get(0)
                .get(0)
                .path("callback_data")
                .asText())
        .isEqualTo("7|session:add:1:2");
    assertThat(body.has("parse_mode")).isFalse();
  }

  @Test
  void deletedInterfaceFallsBackToOneNewMessage() {
    responses.add(
        "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message to edit not found\"}");
    responses.add("{\"ok\":true,\"result\":{\"message_id\":11}}");
    assertThat(client().deliver(1, 10L, 7, screen())).isEqualTo(11);
    assertThat(methods).hasSize(2);
  }

  @Test
  void unchangedMessageIsSuccessAndRateLimitPreservesRetryDelay() {
    responses.add(
        "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message is not modified\"}");
    assertThat(client().deliver(1, 10L, 7, screen())).isEqualTo(10);
    responses.add(
        "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":20},\"description\":\"Too Many Requests\"}");
    assertThatThrownBy(() -> client().deliver(1, 10L, 8, screen()))
        .isInstanceOfSatisfying(
            TelegramClient.ApiException.class,
            e -> {
              assertThat(e.retryAfter).isEqualTo(20);
              assertThat(e.getMessage()).doesNotContain("test-secret");
              assertThat(e.getCause()).isNull();
            });
  }
}
