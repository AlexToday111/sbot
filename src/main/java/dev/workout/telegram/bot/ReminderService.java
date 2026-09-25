package dev.workout.telegram.bot;

import dev.workout.common.I18n;
import dev.workout.user.application.UserService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReminderService {
  private final JdbcTemplate jdbc;
  private final UserService users;
  private final Clock clock;

  public ReminderService(JdbcTemplate jdbc, UserService users, Clock clock) {
    this.jdbc = jdbc;
    this.users = users;
    this.clock = clock;
  }

  public List<Long> dueUsers() {
    return jdbc.queryForList(
        "select distinct user_id from (select s.user_id from workout_sessions s where s.status='ACTIVE' and s.rest_until<=? union select p.user_id from training_schedule p join users u on u.id=p.user_id where not p.cancelled and p.notified_at is null and p.local_start at time zone u.timezone<=?) q limit 20",
        Long.class,
        java.sql.Timestamp.from(clock.instant()),
        java.sql.Timestamp.from(clock.instant()));
  }

  @Transactional
  public void deliver(long uid, TelegramClient client) {
    var user = users.lock(uid);
    var previous = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext();
    I18n.language(user.language);
    try {
      var rest =
          jdbc.queryForList(
              "select id,rest_until from workout_sessions where user_id=? and status='ACTIVE' and paused_at is null and rest_until<=?",
              uid,
              java.sql.Timestamp.from(clock.instant()));
      for (var row : rest) {
        boolean enabled =
            Boolean.TRUE.equals(
                jdbc.query(
                    "select rest_notifications from training_preferences where user_id=?",
                    rs -> rs.next() && rs.getBoolean(1),
                    uid));
        Instant due = ((java.sql.Timestamp) row.get("rest_until")).toInstant();
        if (enabled && due.isAfter(clock.instant().minusSeconds(120)))
          send(client, user.telegramUserId, I18n.t("Rest is over. Ready for the next set?"));
        jdbc.update("update workout_sessions set rest_until=null where id=?", row.get("id"));
      }
      var appointments =
          jdbc.queryForList(
              "select p.*,t.name,t.deleted from training_schedule p join workout_templates t on t.id=p.template_id where p.user_id=? and not p.cancelled and p.notified_at is null and p.local_start<=?",
              uid,
              java.sql.Timestamp.valueOf(
                  LocalDateTime.now(clock.withZone(ZoneId.of(user.timezone)))));
      for (var row : appointments) {
        LocalDateTime start = ((java.sql.Timestamp) row.get("local_start")).toLocalDateTime();
        if (!(boolean) row.get("deleted")
            && (boolean) row.get("reminders")
            && start
                .atZone(ZoneId.of(user.timezone))
                .toInstant()
                .isAfter(clock.instant().minusSeconds(86400)))
          send(
              client,
              user.telegramUserId,
              I18n.t("Planned workout: ")
                  + row.get("name")
                  + "\n"
                  + I18n.t("Open /workout when you are ready."));
        int repeat = (int) row.get("repeat_weeks");
        if (repeat > 0 && !(boolean) row.get("deleted")) {
          do {
            start = start.plusWeeks(repeat);
          } while (!start.atZone(ZoneId.of(user.timezone)).toInstant().isAfter(clock.instant()));
          jdbc.update(
              "update training_schedule set local_start=? where id=?",
              java.sql.Timestamp.valueOf(start),
              row.get("id"));
        } else
          jdbc.update(
              "update training_schedule set notified_at=? where id=?",
              java.sql.Timestamp.from(clock.instant()),
              row.get("id"));
      }
    } finally {
      org.springframework.context.i18n.LocaleContextHolder.setLocaleContext(previous);
    }
  }

  private void send(TelegramClient client, long chat, String text) {
    try {
      client.call("sendMessage", Map.of("chat_id", chat, "text", text), false);
    } catch (TelegramClient.ApiException ex) {
      if (ex.code != 403) throw ex;
    }
  }
}
