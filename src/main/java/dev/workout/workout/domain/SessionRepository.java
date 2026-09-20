package dev.workout.workout.domain;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<WorkoutSession, Long> {
  Optional<WorkoutSession> findByUserIdAndStatus(long userId, WorkoutSession.Status status);

  Optional<WorkoutSession> findByUserIdAndRequestKey(long userId, String requestKey);

  List<WorkoutSession>
      findByUserIdAndStatusAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDesc(
          long userId, WorkoutSession.Status status, Instant start, Instant end, Pageable page);

  @Query(
      "select distinct s from WorkoutSession s join s.exercises e where s.userId=:uid and s.status=dev.workout.workout.domain.WorkoutSession$Status.COMPLETED and e.exerciseId=:eid order by s.startedAt desc")
  List<WorkoutSession> exerciseHistory(
      @Param("uid") long uid, @Param("eid") long eid, Pageable page);
}
