package dev.workout.telegram.handler;

import dev.workout.workout.application.WorkoutDtos.Target;
import java.math.BigDecimal;
import java.util.*;

public class FlowData {
  public Long templateId;
  public String name;
  public String description;
  public List<Target> targets = new ArrayList<>();
  public String search = "";
  public String customName;
  public Long sessionId;
  public Long sessionExerciseId;
  public BigDecimal weight;
  public int targetPosition;
}
