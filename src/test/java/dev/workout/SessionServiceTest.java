package dev.workout;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.workout.analytics.application.RecordService;
import dev.workout.exercise.domain.MetricType;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.*;
import dev.workout.workout.application.WorkoutDtos.*;
import dev.workout.workout.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SessionServiceTest {
  SessionRepository repo = mock(SessionRepository.class);
  TemplateService templates = mock(TemplateService.class);
  UserService users = mock(UserService.class);
  RecordService records = mock(RecordService.class);
  Clock clock = Clock.fixed(Instant.parse("2026-09-18T10:00:00Z"), ZoneOffset.UTC);
  SessionService service = new SessionService(repo, templates, users, records, clock);

  @Test
  void startSnapshotsTemplateTargetsAndReturnsSameSessionForRetry() {
    when(repo.findByUserIdAndStatus(1, WorkoutSession.Status.ACTIVE)).thenReturn(Optional.empty());
    when(repo.findByUserIdAndRequestKey(1, "start-1")).thenReturn(Optional.empty());
    when(templates.get(1, 4))
        .thenReturn(
            new TemplateView(
                4,
                "Push",
                null,
                List.of(
                    new TemplateItem(
                        2, "Bench", MetricType.STRENGTH, 0, 4, 8, new BigDecimal("70"), 90))));
    when(repo.saveAndFlush(any()))
        .thenAnswer(
            inv -> {
              WorkoutSession s = inv.getArgument(0);
              s.id = 9L;
              s.exercises.get(0).id = 10L;
              return s;
            });
    var s = service.start(1, 4, "start-1");
    assertThat(s.exercises().get(0).targetWeight()).isEqualByComparingTo("70");
    assertThat(s.status()).isEqualTo("ACTIVE");
  }

  @Test
  void completionIsIdempotentAndDetectsRecordsOnlyOnce() {
    WorkoutSession s = new WorkoutSession();
    s.id = 2L;
    s.userId = 1;
    s.startedAt = clock.instant().minusSeconds(100);
    when(repo.findById(2L)).thenReturn(Optional.of(s));
    var result = service.finish(1, 2);
    service.finish(1, 2);
    assertThat(result.summary().durationSeconds()).isEqualTo(100);
    assertThat(result.status()).isEqualTo("COMPLETED");
    verify(records, times(1)).onCompleted(s);
  }

  @Test
  void foreignUserCannotMutateAWorkout() {
    WorkoutSession s = new WorkoutSession();
    s.id = 2L;
    s.userId = 2;
    when(repo.findById(2L)).thenReturn(Optional.of(s));
    assertThatThrownBy(() -> service.finish(1, 2))
        .isInstanceOf(dev.workout.common.DomainException.class);
    verifyNoInteractions(records);
  }
}
