package dev.workout;

import static org.assertj.core.api.Assertions.*;

import dev.workout.analytics.application.AnalyticsService;
import dev.workout.analytics.domain.Periods;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

class AnalyticsTest {
  @Test
  void weeksStartOnMondayAcrossYearBoundary() {
    var w = Periods.of("week", LocalDate.of(2026, 1, 1));
    assertThat(w.start()).isEqualTo(LocalDate.of(2025, 12, 29));
    assertThat(w.end()).isEqualTo(LocalDate.of(2026, 1, 5));
    assertThat(w.previousEnd()).isEqualTo(w.start());
  }

  @Test
  void leapFebruaryAndRollingThreeMonths() {
    var m = Periods.of("month", LocalDate.of(2024, 2, 29));
    assertThat(m.end()).isEqualTo(LocalDate.of(2024, 3, 1));
    var q = Periods.of("quarter", LocalDate.of(2026, 1, 15));
    assertThat(q.start()).isEqualTo(LocalDate.of(2025, 11, 1));
    assertThat(q.end()).isEqualTo(LocalDate.of(2026, 2, 1));
  }

  @Test
  void percentageDoesNotInventGrowthWithoutBaseline() {
    assertThat(AnalyticsService.change(new BigDecimal("120"), new BigDecimal("100")))
        .isEqualByComparingTo("20");
    assertThat(AnalyticsService.change(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
    assertThat(AnalyticsService.change(BigDecimal.ZERO, BigDecimal.TEN))
        .isEqualByComparingTo("-100");
  }
}
