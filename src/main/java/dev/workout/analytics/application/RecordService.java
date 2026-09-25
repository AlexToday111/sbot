package dev.workout.analytics.application;

import dev.workout.analytics.domain.*;
import dev.workout.exercise.domain.MetricType;
import dev.workout.workout.domain.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RecordService {
  private final RecordRepository records;
  private final Clock clock;
  private final SessionRepository sessions;

  public RecordService(RecordRepository r, Clock c, SessionRepository sessions) {
    this.sessions = sessions;
    records = r;
    clock = c;
  }

  public void onCompleted(WorkoutSession session) {
    if (session.status != WorkoutSession.Status.COMPLETED)
      throw new IllegalStateException("Records require a completed workout.");
    Map<Long, List<ExerciseSet>> grouped = new HashMap<>();
    session.exercises.stream()
        .filter(e -> e.metricType == MetricType.STRENGTH)
        .forEach(
            e ->
                grouped
                    .computeIfAbsent(e.exerciseId, k -> new ArrayList<>())
                    .addAll(e.recordedSets().stream().filter(s -> !s.warmup).toList()));
    grouped.forEach(
        (eid, sets) -> {
          if (sets.isEmpty()) return;
          PersonalRecord r =
              records.findByUserIdAndExerciseId(session.userId, eid).orElseGet(PersonalRecord::new);
          r.userId = session.userId;
          r.exerciseId = eid;
          BigDecimal sessionVolume = BigDecimal.ZERO;
          for (ExerciseSet set : sets) {
            r.maxWeight = r.maxWeight.max(set.weight);
            r.maxReps = Math.max(r.maxReps, set.repetitions);
            BigDecimal volume = WorkoutMath.volume(MetricType.STRENGTH, set);
            sessionVolume = sessionVolume.add(volume);
            r.bestSetVolume = r.bestSetVolume.max(volume);
            r.estimatedOneRm = r.estimatedOneRm.max(WorkoutMath.oneRm(set));
          }
          r.bestSessionVolume = r.bestSessionVolume.max(sessionVolume);
          r.updatedAt = clock.instant();
          records.save(r);
        });
  }

  public void rebuild(long uid) {
    sessions.flush();
    records.deleteByUserId(uid);
    records.flush();
    sessions
        .findAllByUserIdAndStatus(uid, WorkoutSession.Status.COMPLETED)
        .forEach(this::onCompleted);
  }

  @Transactional(readOnly = true)
  public Optional<PersonalRecord> get(long uid, long eid) {
    return records.findByUserIdAndExerciseId(uid, eid);
  }
}
