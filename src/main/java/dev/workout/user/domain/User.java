package dev.workout.user.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "telegram_user_id", nullable = false, unique = true)
  public long telegramUserId;

  public String username;

  @Column(name = "first_name")
  public String firstName;

  public String language = "en";
  public String timezone = "UTC";
  public boolean onboarded;

  @Column(name = "created_at")
  public Instant createdAt;

  @Column(name = "updated_at")
  public Instant updatedAt;
}
