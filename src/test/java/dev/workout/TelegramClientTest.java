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
    org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
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
  void exportsUtf8HistoryAsDocumentWithVersionedButtons() {
    responses.add("{\"ok\":true,\"result\":{\"message_id\":30}}");
    responses.add("{\"ok\":true,\"result\":true}");
    var screen =
        Screen.title("История")
            .attachment(new Screen.Attachment("history-2026-01.csv", "session,workout\n1,Грудь\n"))
            .home()
            .build();
    assertThat(client().deliver(1, 20L, 9, screen)).isEqualTo(30);
    assertThat(methods)
        .containsExactly("/bottest-secret/sendDocument", "/bottest-secret/deleteMessage");
    assertThat(bodies.get(0))
        .contains(
            "Content-Type: text/csv; charset=utf-8",
            "filename=\"history-2026-01.csv\"",
            "Грудь",
            "9|menu:home");
  }

  @Test
  void downloadsCsvThroughGetFileAndRejectsOversizedBody() throws Exception {
    var document =
        new ObjectMapper().readTree("{\"file_id\":\"abc\",\"file_name\":\"workout.csv\"}");
    responses.add("{\"ok\":true,\"result\":{\"file_path\":\"documents/file.csv\"}}");
    responses.add("workout,exercise\nДень,Жим\n");
    assertThat(new String(client().downloadCsv(document), StandardCharsets.UTF_8))
        .contains("День,Жим");
    assertThat(methods)
        .containsExactly("/bottest-secret/getFile", "/file/bottest-secret/documents/file.csv");
    responses.add("{\"ok\":true,\"result\":{\"file_path\":\"documents/file.csv\"}}");
    responses.add("x".repeat(65537));
    assertThatThrownBy(() -> client().downloadCsv(document))
        .isInstanceOf(dev.workout.common.DomainException.class);
  }

  @Test
  void rejectsWrongFileTypeAndUnsafeDownloadPath() throws Exception {
    var mapper = new ObjectMapper();
    assertThatThrownBy(() -> client().downloadCsv(mapper.readTree("{\"file_name\":\"a.exe\"}")))
        .isInstanceOf(dev.workout.common.DomainException.class);
    responses.add("{\"ok\":true,\"result\":{\"file_path\":\"../secret.csv\"}}");
    assertThatThrownBy(
            () ->
                client()
                    .downloadCsv(mapper.readTree("{\"file_id\":\"abc\",\"file_name\":\"a.csv\"}")))
        .isInstanceOf(TelegramClient.ApiException.class);
    assertThat(methods).hasSize(1);
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

  Screen chartScreen() {
    return Screen.title("Progress <literal>")
        .chart(ProgressChartsTest.sample())
        .primary("Month", "progress:period:month")
        .build();
  }

  @Test
  void uploadsChartAndEditsExistingTextOrPhotoWithVersionedStyledButtons() {
    responses.add("{\"ok\":true,\"result\":{\"message_id\":20}}");
    assertThat(client().deliver(1, null, 7, chartScreen())).isEqualTo(20);
    assertThat(methods.get(0)).endsWith("/sendPhoto");
    assertThat(bodies.get(0))
        .contains(
            "Content-Type: image/png",
            "attach://chart",
            "7|progress:period:month",
            "\"style\":\"primary\"",
            "Progress <literal>");
    responses.add("{\"ok\":true,\"result\":{\"message_id\":20}}");
    assertThat(client().deliver(1, 20L, 8, chartScreen())).isEqualTo(20);
    assertThat(methods.get(1)).endsWith("/editMessageMedia");
    assertThat(bodies.get(1)).contains("\"type\":\"photo\"", "8|progress:period:month");
  }

  @Test
  void returningFromChartSendsTextBeforeRemovingOldPhoto() {
    responses.add(
        "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: there is no text in the message to edit\"}");
    responses.add("{\"ok\":true,\"result\":{\"message_id\":21}}");
    responses.add(
        "{\"ok\":false,\"error_code\":400,\"description\":\"message cannot be deleted\"}");
    assertThat(client().deliver(1, 20L, 9, screen())).isEqualTo(21);
    assertThat(methods)
        .containsExactly(
            "/bottest-secret/editMessageText",
            "/bottest-secret/sendMessage",
            "/bottest-secret/deleteMessage");
  }

  @Test
  void chartRetriesRateLimitsAndRecreatesDeletedPhoto() {
    responses.add("{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":12}}");
    assertThatThrownBy(() -> client().deliver(1, 20L, 9, chartScreen()))
        .isInstanceOfSatisfying(
            TelegramClient.ApiException.class, e -> assertThat(e.retryAfter).isEqualTo(12));
    assertThat(methods).hasSize(1);
    responses.add(
        "{\"ok\":false,\"error_code\":400,\"description\":\"message to edit not found\"}");
    responses.add("{\"ok\":true,\"result\":{\"message_id\":22}}");
    assertThat(client().deliver(1, 20L, 9, chartScreen())).isEqualTo(22);
    assertThat(methods.get(2)).endsWith("/sendPhoto");
  }
}
