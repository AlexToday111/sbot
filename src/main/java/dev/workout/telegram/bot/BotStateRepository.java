package dev.workout.telegram.bot;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotStateRepository extends JpaRepository<BotState, Long> {
  List<BotState> findTop20ByPendingTrueAndRetryAtLessThanEqualOrderByRetryAtAsc(Instant now);
}
