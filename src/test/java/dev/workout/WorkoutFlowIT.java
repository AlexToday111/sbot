package dev.workout;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.workout.analytics.application.*;
import dev.workout.common.DomainException;
import dev.workout.exercise.application.ExerciseService;
import dev.workout.exercise.domain.MetricType;
import dev.workout.telegram.bot.*;
import dev.workout.telegram.message.Screen;
import dev.workout.user.application.UserService;
import dev.workout.workout.application.*;
import dev.workout.workout.application.WorkoutDtos.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = {"app.telegram.enabled=false", "app.api-key=integration-test-secret"})
@AutoConfigureMockMvc
@Testcontainers
class WorkoutFlowIT {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired UserService users;
  @Autowired ExerciseService exercises;
  @Autowired TemplateService templates;
  @Autowired SessionService sessions;
  @Autowired AnalyticsService analytics;
  @Autowired RecordService records;
  @Autowired JdbcTemplate jdbc;
  @Autowired UpdateProcessor processor;
  @Autowired BotStateRepository states;
  @Autowired OutboxService outbox;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  @Autowired CsvImportService imports;
  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean TelegramClient telegram;
  static AtomicLong identities = new AtomicLong(1000), updates = new AtomicLong(10000);

  long user() {
    return users.register(identities.incrementAndGet(), "athlete", "Athlete").id;
  }

  long exercise(long uid, String name) {
    return exercises.search(uid, name, 0).stream()
        .filter(e -> e.name.equals(name))
        .findFirst()
        .orElseThrow()
        .id;
  }

  TemplateView template(long uid) {
    return templates.save(
        uid,
        null,
        new TemplateInput(
            "Push Day",
            "Strength",
            List.of(
                new Target(exercise(uid, "Bench Press"), 3, 8, new BigDecimal("70"), 90),
                new Target(exercise(uid, "Shoulder Press"), 3, 10, new BigDecimal("20"), 60))));
  }

  SetInput set(long eid, String weight, int reps) {
    return new SetInput(eid, new BigDecimal(weight), reps, null, null, null, null);
  }

  @Test
  void completePushDayPersistsSummariesRecordsHistoryAndPreviousPerformance() {
    long uid = user();
    var t = template(uid);
    var session = sessions.start(uid, t.id(), "push-day");
    assertThat(sessions.start(uid, t.id(), "push-day").id()).isEqualTo(session.id());
    assertThatThrownBy(() -> sessions.start(uid, t.id(), "conflict"))
        .isInstanceOf(DomainException.class);
    long bench = session.exercises().get(0).id(), shoulder = session.exercises().get(1).id();
    for (int i = 0; i < 3; i++)
      sessions.addSet(uid, session.id(), set(bench, "70", 8), "bench-" + i);
    for (int i = 0; i < 3; i++)
      sessions.addSet(uid, session.id(), set(shoulder, "20", i == 2 ? 8 : 10), "shoulder-" + i);
    // A retry with the same key never records a seventh set.
    sessions.addSet(uid, session.id(), set(shoulder, "20", 8), "shoulder-2");
    var done = sessions.finish(uid, session.id());
    assertThat(done.summary().sets()).isEqualTo(6);
    assertThat(done.summary().repetitions()).isEqualTo(52);
    assertThat(done.summary().volume()).isEqualByComparingTo("2240");
    assertThat(sessions.finish(uid, session.id()).summary()).isEqualTo(done.summary());
    var record = records.get(uid, exercise(uid, "Bench Press")).orElseThrow();
    assertThat(record.maxWeight).isEqualByComparingTo("70");
    assertThat(record.bestSessionVolume).isEqualByComparingTo("1680");
    assertThat(record.estimatedOneRm).isEqualByComparingTo("88.667");
    assertThat(sessions.history(uid, YearMonth.now(ZoneOffset.UTC), 0))
        .extracting(SessionView::id)
        .contains(session.id());
    var next = sessions.start(uid, t.id(), "next");
    assertThat(next.exercises().get(0).sets()).isEmpty();
    assertThat(
            sessions
                .exerciseHistory(uid, exercise(uid, "Bench Press"), 0)
                .get(0)
                .exercises()
                .get(0)
                .sets())
        .hasSize(3);
    assertThat(analytics.report(uid, "week").current().sets()).isEqualTo(6);
    assertThat(analytics.progress(uid, exercise(uid, "Bench Press"), "year").points()).hasSize(1);
  }

  @Test
  void undoReplayValidationAndUserIsolation() {
    long uid = user(), other = user();
    var s = sessions.start(uid, template(uid).id(), "start");
    long eid = s.exercises().get(0).id();
    var added = sessions.addSet(uid, s.id(), set(eid, "70", 8), "one");
    long setId = added.exercises().get(0).sets().get(0).id();
    assertThatThrownBy(() -> sessions.addSet(uid, s.id(), set(eid, "75", 8), "one"))
        .isInstanceOf(DomainException.class);
    sessions.undo(uid, s.id(), setId);
    sessions.undo(uid, s.id(), setId);
    assertThat(sessions.addSet(uid, s.id(), set(eid, "70", 8), "one").summary().sets()).isZero();
    assertThatThrownBy(() -> sessions.addSet(uid, s.id(), set(eid, "-1", 8), "bad"))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> sessions.get(other, s.id())).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> templates.get(other, s.templateId()))
        .isInstanceOf(DomainException.class);
    assertThat(sessions.get(uid, s.id()).summary().sets()).isZero();
    sessions.cancel(uid, s.id());
    assertThat(analytics.report(uid, "week").current().workouts()).isZero();
    assertThatThrownBy(() -> sessions.addSet(uid, s.id(), set(eid, "70", 8), "late"))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void editingAndDeletingTemplateCannotRewriteSessionSnapshot() {
    long uid = user();
    var t = template(uid);
    var s = sessions.start(uid, t.id(), "snapshot");
    templates.save(
        uid,
        t.id(),
        new TemplateInput(
            "Renamed",
            null,
            List.of(new Target(exercise(uid, "Squat"), 1, 5, new BigDecimal("100"), null))));
    templates.delete(uid, t.id());
    var loaded = sessions.get(uid, s.id());
    assertThat(loaded.name()).isEqualTo("Push Day");
    assertThat(loaded.exercises().get(0).name()).isEqualTo("Bench Press");
    sessions.finish(uid, s.id());
    assertThat(sessions.repeat(uid, s.id(), "repeat-snapshot").exercises()).hasSize(2);
  }

  @Test
  void allMetricTypesAndTimezoneAwareAnalyticsExcludeIncompleteWorkouts() {
    long uid = user();
    users.settings(uid, "America/New_York");
    var custom = exercises.create(uid, "Custom hold", MetricType.TIMED);
    var t =
        templates.save(
            uid,
            null,
            new TemplateInput(
                "Mixed",
                null,
                List.of(
                    new Target(exercise(uid, "Running"), null, null, null, null),
                    new Target(exercise(uid, "Push Ups"), null, null, null, null),
                    new Target(custom.id, null, null, null, null))));
    var s = sessions.start(uid, t.id(), "mixed");
    sessions.addSet(
        uid,
        s.id(),
        new SetInput(s.exercises().get(0).id(), null, null, 1800, new BigDecimal("5"), null, null),
        "run");
    sessions.addSet(
        uid,
        s.id(),
        new SetInput(
            s.exercises().get(1).id(),
            new BigDecimal("5"),
            12,
            null,
            null,
            new BigDecimal("8"),
            "Good"),
        "push");
    sessions.addSet(
        uid,
        s.id(),
        new SetInput(s.exercises().get(2).id(), null, null, 60, null, null, null),
        "hold");
    sessions.finish(uid, s.id());
    // UTC Jan 1 belongs to Dec 31 in New York.
    jdbc.update(
        "update workout_sessions set started_at=?,finished_at=? where id=?",
        java.sql.Timestamp.from(Instant.parse("2026-01-01T00:30:00Z")),
        java.sql.Timestamp.from(Instant.parse("2026-01-01T01:00:00Z")),
        s.id());
    assertThat(sessions.history(uid, YearMonth.of(2025, 12), 0)).hasSize(1);
    var totals =
        analytics.totals(
            uid,
            LocalDate.of(2025, 12, 31),
            LocalDate.of(2026, 1, 1),
            ZoneId.of("America/New_York"));
    assertThat(totals.durationSeconds()).isEqualTo(1800);
    assertThat(totals.sets()).isEqualTo(3);
    assertThat(totals.volume()).isZero();
  }

  @Test
  void concurrentStartsAndSetsSerializePerUser() throws Exception {
    long uid = user();
    var t = template(uid);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      var start1 = pool.submit(() -> sessions.start(uid, t.id(), "parallel"));
      var start2 = pool.submit(() -> sessions.start(uid, t.id(), "parallel"));
      var first = start1.get(15, TimeUnit.SECONDS);
      assertThat(start2.get(15, TimeUnit.SECONDS).id()).isEqualTo(first.id());
      long eid = first.exercises().get(0).id();
      var a = pool.submit(() -> sessions.addSet(uid, first.id(), set(eid, "70", 8), "duplicate"));
      var b = pool.submit(() -> sessions.addSet(uid, first.id(), set(eid, "70", 8), "duplicate"));
      a.get(15, TimeUnit.SECONDS);
      b.get(15, TimeUnit.SECONDS);
      assertThat(sessions.get(uid, first.id()).summary().sets()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void telegramExactSuccessScenarioAndStaleButtons() throws Exception {
    Driver d = new Driver();
    d.text("/start");
    assertThat(d.screen().text()).contains("Track every set");
    assertThat(d.screen().rows().get(0).get(0).text()).isEqualTo("Start");
    d.tap("menu:onboard");
    d.tap("workout:new");
    d.text("Push Day");
    long uid = d.uid(),
        bench = exercise(uid, "Bench Press"),
        shoulder = exercise(uid, "Shoulder Press");
    d.tap("workout:all");
    d.tap("workout:add:" + bench);
    d.tap("workout:all");
    d.tap("workout:add:" + shoulder);
    d.tap("workout:save");
    long tid = templates.list(uid, 0).get(0).id();
    d.tap("session:start:" + tid);
    var active = sessions.active(uid).orElseThrow();
    long sid = active.id(), eid = active.exercises().get(0).id();
    d.tap("session:add:" + sid + ":" + eid);
    d.text("70");
    d.tap("session:reps:8");
    long revision = d.state().revision;
    d.tap("session:same:" + sid + ":" + eid);
    d.stale("session:same:" + sid + ":" + eid, revision);
    assertThat(sessions.get(uid, sid).summary().sets()).isEqualTo(2);
    d.tap("session:same:" + sid + ":" + eid);
    d.tap("session:nav:" + sid + ":1");
    long second = sessions.get(uid, sid).exercises().get(1).id();
    d.tap("session:add:" + sid + ":" + second);
    d.text("20");
    d.tap("session:reps:10");
    d.tap("session:same:" + sid + ":" + second);
    d.tap("session:add:" + sid + ":" + second);
    d.tap("session:weight:20");
    d.tap("session:reps:8");
    d.tap("session:finishask:" + sid);
    d.tap("session:finish:" + sid);
    assertThat(d.screen().text()).contains("Workout completed", "2240", "Sets: 6");
    d.tap("session:repeat:" + sid);
    assertThat(d.screen().text()).contains("Last workout:", "70 kg × 8");
    d.text("/start");
    assertThat(d.screen().text()).contains("active workout");
    d.text("/progress");
    d.tap("progress:exercise:" + bench + ":quarter");
    assertThat(d.screen().text()).contains("Estimated 1RM", "88.67");
  }

  @Test
  void pendingInputAndActiveSessionSurviveACompletelyNewApplicationContext() throws Exception {
    Driver d = new Driver();
    d.text("/start");
    d.tap("menu:onboard");
    long uid = d.uid();
    var t = template(uid);
    d.tap("session:start:" + t.id());
    var s = sessions.active(uid).orElseThrow();
    d.tap("session:add:" + s.id() + ":" + s.exercises().get(0).id());
    d.text("72.5");
    assertThat(d.state().flow.name()).isEqualTo("REPS");
    try (var fresh =
        new SpringApplicationBuilder(WorkoutApplication.class)
            .run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--app.telegram.enabled=false",
                "--spring.main.web-application-type=none",
                "--spring.jmx.enabled=false")) {
      var recovered = fresh.getBean(SessionService.class).active(uid).orElseThrow();
      assertThat(recovered.id()).isEqualTo(s.id());
      var recoveredState = fresh.getBean(BotStateRepository.class).findById(uid).orElseThrow();
      assertThat(recoveredState.flow.name()).isEqualTo("REPS");
      assertThat(recoveredState.context).contains("72.5");
      fresh.getBean(UpdateProcessor.class).process(d.message("8"));
    }
    assertThat(sessions.get(uid, s.id()).exercises().get(0).sets().get(0).weight())
        .isEqualByComparingTo("72.5");
  }

  @Test
  void internalApiAuthenticationValidationAndOpenApiWork() throws Exception {
    long telegramId = identities.incrementAndGet();
    mvc.perform(get("/api/workouts")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/me")
                .header("X-Api-Key", "integration-test-secret")
                .header("X-Telegram-User-Id", telegramId)
                .contentType("application/json")
                .content("{\"firstName\":\"API user\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/workouts")
                .header("X-Api-Key", "integration-test-secret")
                .header("X-Telegram-User-Id", telegramId))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
    mvc.perform(
            post("/api/sessions")
                .header("X-Api-Key", "integration-test-secret")
                .header("X-Telegram-User-Id", telegramId)
                .contentType("application/json")
                .content("{\"templateId\":1}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/sessions/{id}/sets']").exists());
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void flywayAppliedConstraintsAndCatalogArePresent() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success=true", Integer.class))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from exercises where user_id is null", Integer.class))
        .isGreaterThanOrEqualTo(26);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname='one_active_session_per_user'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void invalidTelegramInputKeepsPromptAndCancelButtonRetainsData() throws Exception {
    Driver d = new Driver();
    d.text("/start");
    d.tap("menu:onboard");
    var t = template(d.uid());
    d.tap("session:start:" + t.id());
    var s = sessions.active(d.uid()).orElseThrow();
    long eid = s.exercises().get(0).id();
    d.tap("session:add:" + s.id() + ":" + eid);
    d.text("-1");
    assertThat(d.state().flow.name()).isEqualTo("WEIGHT");
    assertThat(d.screen().text()).contains("Weight must be between");
    d.text("70");
    d.text("1001");
    assertThat(d.state().flow.name()).isEqualTo("REPS");
    assertThat(sessions.get(d.uid(), s.id()).summary().sets()).isZero();
    d.text("8");
    d.tap("session:cancelask:" + s.id());
    d.tap("session:cancel:" + s.id());
    assertThat(sessions.active(d.uid())).isEmpty();
    assertThat(sessions.get(d.uid(), s.id()).status()).isEqualTo("CANCELLED");
    assertThat(sessions.get(d.uid(), s.id()).summary().sets()).isEqualTo(1);
  }

  @Test
  void customExerciseDraftRecoveryAndTimedMetricsWorkThroughTelegram() throws Exception {
    Driver d = new Driver();
    d.text("/start");
    d.tap("menu:onboard");
    d.tap("workout:new");
    d.text("Core Day");
    d.text("/menu");
    assertThat(d.screen().rows().stream().flatMap(List::stream).map(Screen.Button::action))
        .contains("workout:draft");
    d.tap("workout:draft");
    d.tap("workout:all");
    d.tap("workout:custom");
    d.text("Wall Sit");
    d.tap("workout:type:TIMED");
    d.tap("workout:target:0");
    d.text("3 - - 60");
    d.tap("workout:save");
    var t = templates.list(d.uid(), 0).get(0);
    assertThat(t.exercises().get(0).restSeconds()).isEqualTo(60);
    d.tap("session:start:" + t.id());
    var s = sessions.active(d.uid()).orElseThrow();
    d.tap("session:add:" + s.id() + ":" + s.exercises().get(0).id());
    d.text("60 | 8.5 | Steady");
    var recorded = sessions.get(d.uid(), s.id()).exercises().get(0).sets().get(0);
    assertThat(recorded.durationSeconds()).isEqualTo(60);
    assertThat(recorded.rpe()).isEqualByComparingTo("8.5");
    assertThat(recorded.notes()).isEqualTo("Steady");
    d.text("/cancel");
    assertThat(sessions.active(d.uid())).isPresent();
  }

  @Test
  void olderDeliveryCannotClearANewerPendingScreen() {
    Driver d = new Driver();
    d.text("/start");
    d.tap("menu:onboard");
    processor.process(d.message("/menu"));
    var old =
        outbox.pending().stream().filter(x -> x.userId() == d.uid()).findFirst().orElseThrow();
    processor.process(d.message("/progress"));
    outbox.delivered(old, 100);
    assertThat(d.state().pending).isTrue();
    assertThat(d.state().revision).isGreaterThan(old.revision());
    assertThat(d.state().screen).contains("Progress");
  }

  @Test
  void requestKeysCannotBeReusedForAnotherExerciseOrOperation() {
    long uid = user();
    var t = template(uid);
    var s = sessions.start(uid, t.id(), "operation");
    sessions.addSet(uid, s.id(), set(s.exercises().get(0).id(), "70", 8), "same-key");
    assertThatThrownBy(
            () -> sessions.addSet(uid, s.id(), set(s.exercises().get(1).id(), "20", 8), "same-key"))
        .isInstanceOf(DomainException.class);
    sessions.finish(uid, s.id());
    assertThatThrownBy(() -> sessions.repeat(uid, s.id(), "operation"))
        .isInstanceOf(DomainException.class);
    var repeated = sessions.repeat(uid, s.id(), "repeat-key");
    assertThat(sessions.repeat(uid, s.id(), "repeat-key").id()).isEqualTo(repeated.id());
    assertThatThrownBy(() -> sessions.start(uid, t.id(), "repeat-key"))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void languagePersistsAndDoesNotLeakBetweenUsers() throws Exception {
    Driver ru = new Driver();
    ru.text("/start");
    ru.tap("menu:language");
    ru.tap("menu:setlanguage:ru");
    assertThat(ru.screen().text()).contains("Дневник тренировок");
    ru.tap("menu:onboard");
    ru.text("/menu");
    assertThat(ru.screen().text()).contains("ТРЕНИРОВКИ");
    assertThat(users.get(ru.uid()).language).isEqualTo("ru");
    Driver en = new Driver();
    en.text("/start");
    assertThat(en.screen().text()).contains("Workout Tracker");
    ru.tap("workout:new");
    ru.text(" ");
    assertThat(ru.screen().text()).contains("Введите название");
    ru.text("/menu");
    ru.tap("menu:settings");
    ru.tap("menu:language");
    ru.tap("menu:setlanguage:en");
    assertThat(ru.screen().text()).contains("Language: English");
    assertThat(users.get(ru.uid()).language).isEqualTo("en");
  }

  @Test
  void csvImportIsAtomicAndPrivateAndAvailableViaApi() throws Exception {
    long uid = user();
    String header = String.join(",", WorkoutCsv.HEADER) + "\n";
    String csv = header + "Грудь,Bench Press,STRENGTH,3,8,70,90\nГрудь,Мой жим,STRENGTH,3,10,20,60";
    var t = imports.save(uid, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(t.exercises()).hasSize(2);
    assertThat(t.exercises().get(0).exerciseId()).isEqualTo(exercise(uid, "Bench Press"));
    assertThat(exercises.get(uid, t.exercises().get(1).exerciseId()).userId).isEqualTo(uid);
    long other = user();
    assertThatThrownBy(() -> templates.get(other, t.id())).isInstanceOf(DomainException.class);
    String invalid =
        header + "День,Rollback exercise,STRENGTH,3,8,70,90\nДень,Bench Press,CARDIO,3,8,70,90";
    assertThatThrownBy(
            () -> imports.save(uid, invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isInstanceOf(DomainException.class);
    assertThat(exercises.search(uid, "Rollback exercise", 0)).isEmpty();
    assertThat(templates.list(uid, 0)).hasSize(1);
    mvc.perform(
            multipart("/api/workouts/import")
                .header("X-Api-Key", "integration-test-secret")
                .header("X-Telegram-User-Id", users.get(other).telegramUserId))
        .andExpect(status().isBadRequest());
    mvc.perform(
            multipart("/api/workouts/import")
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "file", "large.csv", "text/csv", new byte[65537]))
                .header("X-Api-Key", "integration-test-secret")
                .header("X-Telegram-User-Id", users.get(other).telegramUserId))
        .andExpect(status().isBadRequest());
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file",
            "workout.csv",
            "text/csv",
            csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    mvc.perform(
            multipart("/api/workouts/import")
                .file(file)
                .header("X-Api-Key", "integration-test-secret")
                .header("X-Telegram-User-Id", users.get(other).telegramUserId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Грудь"));
    mvc.perform(
            patch("/api/me")
                .header("X-Api-Key", "integration-test-secret")
                .header("X-Telegram-User-Id", users.get(other).telegramUserId)
                .contentType("application/json")
                .content("{\"language\":\"ru\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.language").value("ru"));
    mvc.perform(
            patch("/api/me")
                .header("X-Api-Key", "integration-test-secret")
                .header("X-Telegram-User-Id", users.get(other).telegramUserId)
                .contentType("application/json")
                .content("{\"language\":\"de\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void telegramCsvRequiresReviewAndReplayedUpdateDoesNotDuplicate() throws Exception {
    Driver d = new Driver();
    d.text("/start");
    d.tap("menu:onboard");
    d.tap("workout:import");
    byte[] csv =
        (String.join(",", WorkoutCsv.HEADER) + "\nМой план,Bench Press,STRENGTH,3,8,70,90")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    org.mockito.Mockito.doReturn(csv)
        .when(telegram)
        .downloadCsv(org.mockito.ArgumentMatchers.any());
    var update = (com.fasterxml.jackson.databind.node.ObjectNode) d.message("");
    ((com.fasterxml.jackson.databind.node.ObjectNode) update.get("message"))
        .set("document", json.valueToTree(Map.of("file_id", "test", "file_name", "test.csv")));
    processor.process(update);
    d.delivered();
    assertThat(templates.list(d.uid(), 0)).isEmpty();
    assertThat(d.screen().text()).contains("Мой план");
    processor.process(update);
    d.tap("workout:save");
    assertThat(templates.list(d.uid(), 0)).hasSize(1);
    d.tap("workout:import");
    d.text("/cancel");
    assertThat(d.state().flow.name()).isEqualTo("HOME");
  }

  class Driver {
    final long telegramId = identities.incrementAndGet();

    long uid() {
      return users.byTelegram(telegramId).id;
    }

    BotState state() {
      return states.findById(uid()).orElseThrow();
    }

    Screen screen() throws Exception {
      return json.readValue(state().screen, Screen.class);
    }

    JsonNode message(String text) {
      return json.valueToTree(
          Map.of(
              "update_id",
              updates.incrementAndGet(),
              "message",
              Map.of(
                  "message_id",
                  2,
                  "chat",
                  Map.of("id", telegramId, "type", "private"),
                  "from",
                  Map.of("id", telegramId, "first_name", "Tester"),
                  "text",
                  text)));
    }

    void text(String text) {
      processor.process(message(text));
      delivered();
    }

    void tap(String action) {
      stale(action, state().revision);
    }

    void stale(String action, long revision) {
      processor.process(
          json.valueToTree(
              Map.of(
                  "update_id",
                  updates.incrementAndGet(),
                  "callback_query",
                  Map.of(
                      "id",
                      "cb-" + updates.get(),
                      "from",
                      Map.of("id", telegramId, "first_name", "Tester"),
                      "message",
                      Map.of(
                          "message_id", 100, "chat", Map.of("id", telegramId, "type", "private")),
                      "data",
                      revision + "|" + action))));
      delivered();
    }

    void delivered() {
      var s = state();
      outbox.delivered(
          new OutboxService.Delivery(s.userId, s.chatId, s.messageId, s.revision, s.screen), 100);
    }
  }
}
