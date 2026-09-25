package dev.workout.workout.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "workout_template_exercises")
public class TemplateExercise {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "exercise_id")
  public long exerciseId;

  public int position;

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
}
