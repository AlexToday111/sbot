package dev.workout.telegram.handler;

import dev.workout.workout.application.WorkoutDtos.Target;
import java.math.BigDecimal;
import java.util.*;

public class FlowData {
  public String trainingAction;
  public boolean allSets;
  public String historyStart, historyEnd;
  public int historyPosition;
  public List<dev.workout.workout.application.TrainingService.HistoricalExercise>
      historicalExercises = new ArrayList<>();

  public dev.workout.workout.application.WorkoutCsv.Parsed csvDraft;
  public Long importReplaceId;
  public Long editingSetId;
  public Long scheduleId;
  public Long planningTemplateId;
  public Long historicalTemplateId;
  public Long manageExerciseId;
  public boolean replacingExercise;
  public boolean warmup;
  public String historyCsv;
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
