package dev.workout.workout.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "workout_sessions")
public class WorkoutSession {
  public enum Status {
    ACTIVE,
    COMPLETED,
    CANCELLED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "user_id")
  public long userId;

  @Column(name = "workout_template_id")
  public Long templateId;

  @Column(name = "source_session_id")
  public Long sourceSessionId;

  public String name;

  @Enumerated(EnumType.STRING)
  public Status status = Status.ACTIVE;

  @Column(name = "started_at")
  public Instant startedAt;

  @Column(name = "finished_at")
  public Instant finishedAt;

  public String notes;

  @Column(name = "paused_at")
  public Instant pausedAt;

  @Column(name = "paused_seconds")
  public long pausedSeconds;

  @Column(name = "rest_until")
  public Instant restUntil;

  @Column(name = "current_position")
  public int currentPosition;

  @Column(name = "request_key")
  public String requestKey;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "workout_session_id", nullable = false)
  @OrderBy("position ASC")
  public List<SessionExercise> exercises = new ArrayList<>();
}
