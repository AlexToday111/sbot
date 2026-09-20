package dev.workout.telegram.bot;

import dev.workout.telegram.handler.Flow;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "bot_states")
public class BotState {
  @Id
  @Column(name = "user_id")
  public Long userId;

  @Column(name = "chat_id")
  public long chatId;

  @Column(name = "message_id")
  public Long messageId;

  public long revision;

  @Enumerated(EnumType.STRING)
  public Flow flow = Flow.HOME;

  @Column(columnDefinition = "text")
  public String context = "{}";

  @Column(columnDefinition = "text")
  public String screen;

  public boolean pending;

  @Column(name = "retry_at")
  public Instant retryAt = Instant.EPOCH;
}
