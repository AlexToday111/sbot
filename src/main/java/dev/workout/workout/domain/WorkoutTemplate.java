package dev.workout.workout.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "workout_templates")
public class WorkoutTemplate {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "user_id")
  public long userId;

  public String name;
  public String description;
  public boolean deleted;

  @Column(name = "created_at")
  public Instant createdAt;

  @Column(name = "updated_at")
  public Instant updatedAt;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "workout_template_id", nullable = false)
  @OrderBy("position ASC")
  public List<TemplateExercise> exercises = new ArrayList<>();
}
