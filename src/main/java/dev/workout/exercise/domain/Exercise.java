package dev.workout.exercise.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "exercises")
public class Exercise {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "user_id")
  public Long userId;

  public String name;
  public String category;

  @Enumerated(EnumType.STRING)
  @Column(name = "metric_type")
  public MetricType metricType;

  @Column(name = "is_custom")
  public boolean custom;

  @Column(name = "created_at")
  public Instant createdAt;
}
