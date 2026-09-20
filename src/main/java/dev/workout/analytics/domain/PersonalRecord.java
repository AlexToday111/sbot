package dev.workout.analytics.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "personal_records")
public class PersonalRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "user_id")
  public long userId;

  @Column(name = "exercise_id")
  public long exerciseId;

  @Column(name = "max_weight")
  public BigDecimal maxWeight = BigDecimal.ZERO;

  @Column(name = "max_reps")
  public int maxReps;

  @Column(name = "best_set_volume")
  public BigDecimal bestSetVolume = BigDecimal.ZERO;

  @Column(name = "best_session_volume")
  public BigDecimal bestSessionVolume = BigDecimal.ZERO;

  @Column(name = "estimated_one_rm")
  public BigDecimal estimatedOneRm = BigDecimal.ZERO;

  @Column(name = "updated_at")
  public Instant updatedAt;
}
