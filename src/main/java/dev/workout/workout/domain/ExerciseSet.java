package dev.workout.workout.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "exercise_sets")
public class ExerciseSet {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "set_number")
  public int setNumber;

  public BigDecimal weight;
  public Integer repetitions;

  @Column(name = "duration_seconds")
  public Integer durationSeconds;

  public BigDecimal distance;
  public BigDecimal rpe;
  public String notes;

  @Column(name = "created_at")
  public Instant createdAt;

  @Column(name = "request_key")
  public String requestKey;

  public boolean voided;
}
