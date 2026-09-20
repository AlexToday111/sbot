package dev.workout.user.application;

import dev.workout.common.*;
import dev.workout.user.domain.*;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserService {
  private final UserRepository users;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public UserService(UserRepository users, JdbcTemplate jdbc, Clock clock) {
    this.users = users;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public User register(long telegramId, String username, String firstName) {
    if (telegramId <= 0) throw new DomainException("Invalid Telegram identity.");
    // ON CONFLICT makes simultaneous first updates safe across REST and Telegram.
    jdbc.update(
        "insert into users(telegram_user_id,username,first_name,created_at,updated_at) values (?,?,?,?,?) on conflict (telegram_user_id) do nothing",
        telegramId,
        Checks.text(username, 80),
        Checks.text(firstName, 100),
        java.sql.Timestamp.from(clock.instant()),
        java.sql.Timestamp.from(clock.instant()));
    return users.findByTelegramUserId(telegramId).orElseThrow(DomainException::missing);
  }

  @Transactional(readOnly = true)
  public User byTelegram(long telegramId) {
    return users
        .findByTelegramUserId(telegramId)
        .orElseThrow(
            () ->
                new DomainException(
                    404, "Start the Telegram bot first, or register through /api/me."));
  }

  @Transactional(readOnly = true)
  public User get(long id) {
    return users.findById(id).orElseThrow(DomainException::missing);
  }

  public User lock(long id) {
    return users.lock(id).orElseThrow(DomainException::missing);
  }

  public User settings(long id, String timezone) {
    try {
      ZoneId.of(timezone);
    } catch (Exception ex) {
      throw new DomainException(
          "Use an IANA timezone, for example Europe/Moscow or America/New_York.");
    }
    User u = lock(id);
    u.timezone = timezone;
    u.updatedAt = clock.instant();
    return u;
  }

  public void onboard(long id) {
    User u = lock(id);
    u.onboarded = true;
    u.updatedAt = clock.instant();
  }
}
