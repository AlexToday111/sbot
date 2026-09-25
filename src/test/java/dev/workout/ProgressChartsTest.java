package dev.workout;

import static org.assertj.core.api.Assertions.*;

import dev.workout.analytics.application.AnalyticsService.*;
import dev.workout.common.I18n;
import dev.workout.telegram.message.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.context.i18n.LocaleContextHolder;

class ProgressChartsTest {
  @AfterEach
  void resetLocale() {
    LocaleContextHolder.resetLocaleContext();
  }

  static Chart sample() {
    I18n.language("ru");
    var totals = new Totals(3, 6, 18, 144, 7200, new BigDecimal("7000"));
    return ProgressCharts.report(
        new Report(
            "week",
            LocalDate.of(2026, 9, 21),
            LocalDate.of(2026, 9, 28),
            totals,
            totals,
            0,
            BigDecimal.ZERO,
            List.of(
                new Day(LocalDate.of(2026, 9, 21), 1, new BigDecimal("2240")),
                new Day(LocalDate.of(2026, 9, 23), 2, new BigDecimal("4760")))),
        LocalDate.of(2026, 9, 24));
  }

  @Test
  void includesRestDaysButNeverFutureDays() {
    Chart chart = sample();
    assertThat(chart.series().get(0).points())
        .extracting(Chart.Point::value)
        .containsExactly(
            new BigDecimal("2240"), BigDecimal.ZERO, new BigDecimal("4760"), BigDecimal.ZERO);
    assertThat(chart.series().get(1).points())
        .extracting(Chart.Point::value)
        .containsExactly(BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("2"), BigDecimal.ZERO);
    assertThat(chart.title()).isEqualTo("Прогресс тренировок");
  }

  @Test
  void keepsMissingMonthsAsGapsAndZeroAsRealMeasurement() {
    var p =
        new Progress(
            1,
            "Жим",
            "Estimated 1RM",
            "kg",
            List.of(
                new Point("2026-07", BigDecimal.ZERO),
                new Point("2026-09", new BigDecimal("88.5"))),
            null,
            null);
    var chart = ProgressCharts.exercise(p, "quarter", LocalDate.of(2026, 9, 24));
    assertThat(chart.series().get(0).points())
        .extracting(Chart.Point::label)
        .containsExactly("2026-07", "2026-08", "2026-09");
    assertThat(chart.series().get(0).points())
        .extracting(Chart.Point::value)
        .containsExactly(BigDecimal.ZERO, null, new BigDecimal("88.5"));
  }

  @Test
  void rendersLocalizedPngAndEmptyAndSinglePointCharts() throws Exception {
    var png = ChartRenderer.render(sample());
    var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    assertThat(image.getWidth()).isEqualTo(1200);
    assertThat(image.getHeight()).isEqualTo(1000);
    assertThat(png.length).isBetween(10000, 1000000);
    java.nio.file.Files.write(java.nio.file.Path.of("target/progress-preview.png"), png);
    for (var points :
        List.of(List.<Point>of(), List.of(new Point("2026-09", new BigDecimal("88.5"))))) {
      var chart =
          ProgressCharts.exercise(
              new Progress(1, "Жим лёжа", "Estimated 1RM", "kg", points, null, null),
              "quarter",
              LocalDate.of(2026, 9, 24));
      byte[] bytes = ChartRenderer.render(chart);
      assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes)).getHeight())
          .isEqualTo(720);
      java.nio.file.Files.write(
          java.nio.file.Path.of("target/exercise-" + points.size() + "-preview.png"), bytes);
    }
  }

  @Test
  void readsPreviouslyPersistedScreensAndRoundTripsNewScreens() throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var old =
        json.readValue(
            "{\"text\":\"Menu\",\"rows\":[[{\"text\":\"Start\",\"action\":\"menu:onboard\"}]]}",
            Screen.class);
    assertThat(old.chart()).isNull();
    assertThat(old.rows().get(0).get(0).style()).isNull();
    var screen =
        Screen.title("Прогресс").chart(sample()).primary("Начать", "workout:list:0").build();
    assertThat(json.readValue(json.writeValueAsString(screen), Screen.class)).isEqualTo(screen);
  }
}
