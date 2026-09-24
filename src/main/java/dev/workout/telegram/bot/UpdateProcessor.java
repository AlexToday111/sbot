package dev.workout.telegram.bot;

import static dev.workout.common.I18n.t;

import com.fasterxml.jackson.databind.*;
import dev.workout.common.*;
import dev.workout.telegram.callback.CallbackRouter;
import dev.workout.telegram.handler.*;
import dev.workout.telegram.message.Screen;
import dev.workout.user.application.UserService;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UpdateProcessor {
  private static final Logger log = LoggerFactory.getLogger(UpdateProcessor.class);
  private final TransactionTemplate tx;
  private final JdbcTemplate jdbc;
  private final UserService users;
  private final BotStateRepository states;
  private final ObjectMapper json;
  private final CallbackRouter router;
  private final MenuHandler menu;
  private final Clock clock;
  private final TelegramClient telegram;
  private final dev.workout.workout.application.CsvImportService imports;

  public UpdateProcessor(
      TransactionTemplate tx,
      JdbcTemplate j,
      UserService u,
      BotStateRepository s,
      ObjectMapper mapper,
      CallbackRouter r,
      MenuHandler m,
      Clock c,
      TelegramClient telegram,
      dev.workout.workout.application.CsvImportService imports) {
    this.telegram = telegram;
    this.imports = imports;
    this.tx = tx;
    jdbc = j;
    users = u;
    states = s;
    json = mapper;
    router = r;
    menu = m;
    clock = c;
  }

  public long offset() {
    return jdbc.queryForObject("select next_update from bot_cursor where id=1", Long.class);
  }

  public void process(JsonNode update) {
    var previousLocale = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
    try {
      tx.executeWithoutResult(status -> apply(update));
    } catch (DomainException ex) {
      error(update, ex.getMessage());
    } catch (DataAccessException ex) {
      throw ex;
    } // Retry without advancing the durable cursor.
    catch (RuntimeException ex) {
      log.error("Telegram update {} rolled back", update.path("update_id").asLong(), ex);
      error(
          update,
          t(
              "This action could not be saved. Please try again. Previously confirmed sets are still saved."));
    } finally {
      org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previousLocale);
    }
  }

  private void apply(JsonNode update) {
    long updateId = update.path("update_id").asLong();
    if (alreadyProcessed(updateId)) return;
    JsonNode callback = update.path("callback_query");
    JsonNode message = callback.isMissingNode() ? update.path("message") : callback.path("message");
    JsonNode from = callback.isMissingNode() ? message.path("from") : callback.path("from");
    if (!validPrivate(message, from)) {
      advance(updateId);
      return;
    }
    var user =
        users.register(
            from.path("id").asLong(), nullable(from, "username"), nullable(from, "first_name"));
    users.lock(user.id);
    dev.workout.common.I18n.language(user.language);
    BotState state =
        states
            .findById(user.id)
            .orElseGet(
                () -> {
                  BotState s = new BotState();
                  s.userId = user.id;
                  s.chatId = message.path("chat").path("id").asLong();
                  return s;
                });
    FlowData data = read(state.context, FlowData.class);
    var c = new Interaction(user, state, data, updateId);
    Screen screen;
    if (!callback.isMissingNode()) {
      String action = callback.path("data").asText();
      int split = action.indexOf('|');
      boolean fresh = false;
      try {
        fresh =
            split > 0
                && Long.parseLong(action.substring(0, split)) == state.revision
                && Objects.equals(state.messageId, message.path("message_id").asLong());
      } catch (NumberFormatException ignored) {
      }
      if (!fresh) {
        state.pending = state.screen != null;
        state.retryAt = clock.instant();
        states.save(state);
        advance(updateId);
        return;
      }
      String route = action.substring(split + 1);
      screen =
          !user.onboarded
                  && !route.equals("menu:onboard")
                  && !route.equals("menu:language")
                  && !route.startsWith("menu:setlanguage:")
              ? menu.welcome()
              : router.route(c, route);
    } else {
      String text = message.path("text").asText("");
      if (message.has("document") && user.onboarded) {
        if (state.flow != Flow.CSV_IMPORT)
          throw new DomainException(t("Choose Workouts → Import CSV before sending a file."));
        var draft = imports.draft(c.uid(), telegram.downloadCsv(message.path("document")));
        data.templateId = null;
        data.name = draft.name();
        data.description = draft.description();
        data.targets.clear();
        data.targets.addAll(draft.exercises());
        screen = router.route(c, "workout:draft");
      } else if (text.startsWith("/")) {
        String command = text.split("\\s+")[0].split("@")[0];
        screen =
            switch (command) {
              case "/start" -> menu.start(c);
              case "/menu" -> user.onboarded ? menu.home(c) : menu.welcome();
              case "/workout" ->
                  user.onboarded ? router.route(c, "workout:list:0") : menu.welcome();
              case "/history" ->
                  user.onboarded ? router.route(c, "history:current") : menu.welcome();
              case "/progress" ->
                  user.onboarded ? router.route(c, "progress:menu") : menu.welcome();
              case "/cancel" -> {
                state.context = "{}";
                data = new FlowData();
                yield user.onboarded
                    ? menu.home(new Interaction(user, state, data, updateId))
                    : menu.welcome();
              }
              default ->
                  Screen.title(t("Use the buttons below, or /menu to open your dashboard."))
                      .home()
                      .build();
            };
      } else if (user.onboarded && state.flow == Flow.CSV_IMPORT) {
        throw new DomainException(t("Send a .csv file as a document."));
      } else screen = !user.onboarded ? menu.welcome() : router.text(c, text);
    }
    state.context = write(data);
    queue(state, screen);
    states.save(state);
    advance(updateId);
  }

  private boolean validPrivate(JsonNode message, JsonNode from) {
    return message.path("chat").path("type").asText().equals("private")
        && !from.path("is_bot").asBoolean()
        && from.path("id").asLong() > 0
        && message.path("chat").path("id").asLong() == from.path("id").asLong();
  }

  private void error(JsonNode update, String message) {
    tx.executeWithoutResult(
        status -> {
          long id = update.path("update_id").asLong();
          if (alreadyProcessed(id)) return;
          var callback = update.path("callback_query");
          var msg = callback.isMissingNode() ? update.path("message") : callback.path("message");
          var from = callback.isMissingNode() ? msg.path("from") : callback.path("from");
          if (!validPrivate(msg, from)) {
            advance(id);
            return;
          }
          var user =
              users.register(
                  from.path("id").asLong(),
                  nullable(from, "username"),
                  nullable(from, "first_name"));
          users.lock(user.id);
          dev.workout.common.I18n.language(user.language);
          var state =
              states
                  .findById(user.id)
                  .orElseGet(
                      () -> {
                        var s = new BotState();
                        s.userId = user.id;
                        s.chatId = user.telegramUserId;
                        return s;
                      });
          Screen old =
              state.screen == null
                  ? Screen.title(t("Open the menu to continue.")).home().build()
                  : read(state.screen, Screen.class);
          // Replace a previous error prefix instead of accumulating messages.
          String separator = "\n\n——\n\n";
          String prompt =
              old.text().contains(separator)
                  ? old.text().substring(old.text().indexOf(separator) + separator.length())
                  : old.text();
          String text = message + "\n\n——\n\n" + prompt;
          if (text.length() > 3900) text = message + t("\n\nPlease use the buttons below.");
          queue(state, new Screen(text, old.rows()));
          states.save(state);
          advance(id);
        });
  }

  private void queue(BotState state, Screen screen) {
    state.revision++;
    state.screen = write(screen);
    state.pending = true;
    state.retryAt = clock.instant();
  }

  private boolean alreadyProcessed(long id) {
    return id
        < jdbc.queryForObject(
            "select next_update from bot_cursor where id=1 for update", Long.class);
  }

  private void advance(long id) {
    jdbc.update("update bot_cursor set next_update=? where id=1", id + 1);
  }

  private String nullable(JsonNode node, String key) {
    return node.hasNonNull(key) ? node.get(key).asText() : null;
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid persisted bot state", ex);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot serialize bot screen", ex);
    }
  }
}
