package dev.workout.workout.domain;

import dev.workout.exercise.domain.MetricType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.*;

@Entity
@Table(name = "workout_session_exercises")
public class SessionExercise {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "exercise_id")
  public long exerciseId;

  public String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "metric_type")
  public MetricType metricType;

  public int position;
  public boolean skipped;

  @Column(name = "target_sets")
  public Integer targetSets;

  @Column(name = "target_reps")
  public Integer targetReps;

  @Column(name = "target_weight")
  public BigDecimal targetWeight;

  @Column(name = "target_duration")
  public Integer targetDuration;

  @Column(name = "target_distance")
  public BigDecimal targetDistance;

  @Column(name = "set_plan")
  public String setPlan = "[]";

  @Column(name = "rest_seconds")
  public Integer restSeconds;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "workout_session_exercise_id", nullable = false)
  @OrderBy("setNumber ASC")
  public List<ExerciseSet> sets = new ArrayList<>();

  public List<ExerciseSet> recordedSets() {
    return sets.stream().filter(s -> !s.voided).toList();
  }
}
