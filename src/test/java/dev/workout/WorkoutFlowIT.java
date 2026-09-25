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
  @Autowired TrainingService training;
  @Autowired PlanningService planning;
  @Autowired HistoryTransferService historyTransfer;
  @Autowired ReminderService reminders;
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
    sessions.addSet(uid, s.id(), set(s.exercises().get(0).id(), "70", 8), "snapshot-set");
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
        .isEqualTo(6);
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
    d.text("3 45 60");
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
    d.tap("train:importcopy");
    assertThat(templates.list(d.uid(), 0)).hasSize(1);
    d.tap("workout:import");
    d.text("/cancel");
    assertThat(d.state().flow.name()).isEqualTo("HOME");
  }

  @Test
  void progressChartsAndCompactNavigationWorkInBothLanguages() throws Exception {
    Driver d = new Driver();
    d.text("/start");
    d.tap("menu:onboard");
    assertThat(d.screen().rows()).extracting(List::size).containsExactly(1, 1, 2, 2);
    d.text("/progress");
    assertThat(d.screen().chart()).isNotNull();
    assertThat(d.screen().text().length()).isLessThanOrEqualTo(1024);
    d.tap("progress:period:year");
    assertThat(d.screen().chart().series()).hasSize(2);
    d.tap("progress:exercises:0");
    d.tap("progress:exercise:" + exercise(d.uid(), "Bench Press") + ":quarter");
    assertThat(d.screen().chart().series()).hasSize(1);
    d.text("/menu");
    d.tap("menu:settings");
    d.tap("menu:language");
    d.tap("menu:setlanguage:ru");
    d.text("/progress");
    assertThat(d.screen().chart().title()).isEqualTo("Прогресс тренировок");
    assertThat(d.screen().text().length()).isLessThanOrEqualTo(1024);
    assertThat(d.screen().rows().get(1))
        .extracting(Screen.Button::text)
        .containsExactly("Неделя", "✓ Месяц");
    var t = template(d.uid());
    d.text("/workout");
    d.tap("workout:edit:" + t.id());
    d.tap("workout:item:0");
    d.tap("workout:down:0");
    d.tap("workout:save");
    assertThat(templates.get(d.uid(), t.id()).exercises().get(0).name())
        .isEqualTo("Shoulder Press");
    d.tap("session:start:" + t.id());
    d.text("/menu");
    assertThat(d.screen().rows().stream().flatMap(List::stream).map(Screen.Button::action))
        .noneMatch(a -> a.equals("session:start:" + t.id()));
    assertThat(d.screen().rows().get(0).get(0).action()).startsWith("session:view:");
  }

  @Test
  void commandsCreateVisibleRepliesAndOldDeliveryCannotRestorePreviousMessage() throws Exception {
    Driver d = new Driver();
    d.text("/start");
    d.tap("menu:onboard");
    long originalMessage = d.state().messageId;
    processor.process(d.message("/menu"));
    assertThat(d.state().messageId).isNull();
    var oldDelivery =
        outbox.pending().stream().filter(x -> x.userId() == d.uid()).findFirst().orElseThrow();
    processor.process(d.message("/start"));
    var latestDelivery =
        outbox.pending().stream().filter(x -> x.userId() == d.uid()).findFirst().orElseThrow();
    outbox.delivered(oldDelivery, 200);
    assertThat(d.state().messageId).isNull();
    assertThat(d.state().pending).isTrue();
    outbox.delivered(latestDelivery, 300);
    assertThat(d.state().messageId).isEqualTo(300);
    assertThat(d.state().pending).isFalse();
    outbox.delivered(oldDelivery, 200);
    assertThat(d.state().messageId).isEqualTo(300);
    var callback = json.createObjectNode();
    callback.put("update_id", updates.incrementAndGet());
    var query = callback.putObject("callback_query");
    query.put("data", d.state().revision + "|workout:new");
    query.putObject("from").put("id", d.telegramId);
    var message = query.putObject("message");
    message.put("message_id", originalMessage);
    message.putObject("chat").put("id", d.telegramId).put("type", "private");
    processor.process(callback);
    assertThat(d.state().flow.name()).isEqualTo("HOME");
    assertThat(d.state().messageId).isEqualTo(300);
    callback.put("update_id", updates.incrementAndGet());
    message.put("message_id", 300);
    processor.process(callback);
    assertThat(d.state().flow.name()).isEqualTo("WORKOUT_NAME");
    assertThat(d.state().messageId).isEqualTo(300);
  }

  @Test
  void correctionsRebuildRecordsIncludingDecreasesAndWarmups() {
    long uid = user();
    var t = template(uid);
    var s = sessions.start(uid, t.id(), "correct");
    long eid = s.exercises().get(0).id();
    var one =
        sessions
            .addSet(uid, s.id(), set(eid, "700", 8), "mistake")
            .exercises()
            .get(0)
            .sets()
            .get(0);
    sessions.addSet(uid, s.id(), set(eid, "60", 10), "real");
    sessions.finish(uid, s.id());
    training.editSet(uid, s.id(), one.id(), set(eid, "70", 8));
    assertThat(records.get(uid, exercise(uid, "Bench Press")).orElseThrow().maxWeight)
        .isEqualByComparingTo("70");
    training.editSet(
        uid,
        s.id(),
        one.id(),
        new SetInput(eid, new BigDecimal("70"), 8, null, null, null, null, true));
    assertThat(records.get(uid, exercise(uid, "Bench Press")).orElseThrow().maxWeight)
        .isEqualByComparingTo("60");
    assertThat(analytics.progress(uid, exercise(uid, "Bench Press"), "year", true, true).points())
        .hasSize(1);
    assertThat(analytics.report(uid, "year", true, true).current().sets()).isEqualTo(1);
    sessions.undo(uid, s.id(), one.id());
    assertThat(sessions.get(uid, s.id()).summary().sets()).isEqualTo(1);
    assertThatThrownBy(() -> training.editSet(user(), s.id(), one.id(), set(eid, "80", 8)))
        .isInstanceOf(DomainException.class);
    long remaining = sessions.get(uid, s.id()).exercises().get(0).sets().get(0).id();
    assertThat(sessions.undo(uid, s.id(), remaining).status()).isEqualTo("CANCELLED");
    assertThat(records.get(uid, exercise(uid, "Bench Press"))).isEmpty();
  }

  @Test
  void emptyAndForgottenWorkoutsNeedExplicitHandling() {
    long uid = user();
    var s = sessions.start(uid, template(uid).id(), "old");
    assertThatThrownBy(() -> sessions.finish(uid, s.id())).isInstanceOf(DomainException.class);
    sessions.addSet(uid, s.id(), set(s.exercises().get(0).id(), "70", 8), "last");
    Instant
        start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).minusSeconds(86400),
        end = start.plusSeconds(3600);
    jdbc.update(
        "update workout_sessions set started_at=? where id=?",
        java.sql.Timestamp.from(start),
        s.id());
    jdbc.update(
        "update exercise_sets set created_at=? where workout_session_exercise_id=?",
        java.sql.Timestamp.from(end.minusSeconds(60)),
        s.exercises().get(0).id());
    assertThatThrownBy(() -> sessions.finish(uid, s.id())).isInstanceOf(DomainException.class);
    assertThat(training.finishAt(uid, s.id(), end).summary().durationSeconds()).isEqualTo(3600);
  }

  @Test
  void sessionChangesAreIsolatedAndReorderingSurvivesReload() {
    long uid = user();
    var t = template(uid);
    var s = sessions.start(uid, t.id(), "change");
    long eid = s.exercises().get(0).id();
    training.changeExercise(uid, s.id(), eid, exercise(uid, "Squat"), 1, null);
    var changed = training.changeExercise(uid, s.id(), null, exercise(uid, "Running"), null, null);
    assertThat(changed.exercises()).hasSize(3);
    assertThat(sessions.get(uid, s.id()).exercises().get(1).name()).isEqualTo("Squat");
    training.changeExercise(uid, s.id(), eid, null, null, true);
    assertThat(training.comparison(uid, s.id()).get(1).skipped()).isTrue();
    assertThat(templates.get(uid, t.id()).exercises().get(0).name()).isEqualTo("Bench Press");
    assertThat(training.saveAsTemplate(uid, s.id(), "Adapted").exercises()).hasSize(2);
  }

  @Test
  void timedCardioAndIndividualPlansSurviveSnapshotsAndImport() {
    long uid = user();
    String csv =
        "workout,exercise,metric_type,sets,reps,weight,rest_seconds,duration_seconds,distance,set_plan\nMixed,Running,CARDIO,2,,,90,600,2,\nMixed,Hold,TIMED,3,,,60,45,,";
    var t = imports.save(uid, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var s = sessions.start(uid, t.id(), "metrics");
    assertThat(s.exercises().get(0).targetDistance()).isEqualByComparingTo("2");
    assertThat(s.exercises().get(1).targetDuration()).isEqualTo(45);
    sessions.cancel(uid, s.id());
    var plan =
        List.of(
            new SetPlan(10, new BigDecimal("20"), null, null, true),
            new SetPlan(8, new BigDecimal("70"), null, null, false));
    var individual =
        templates.save(
            uid,
            null,
            new TemplateInput(
                "Sets",
                null,
                List.of(
                    new Target(
                        exercise(uid, "Bench Press"),
                        2,
                        8,
                        new BigDecimal("70"),
                        60,
                        null,
                        null,
                        plan))));
    var next = sessions.start(uid, individual.id(), "plan");
    assertThat(next.exercises().get(0).plan()).hasSize(2);
    assertThat(training.comparison(uid, next.id()).get(0).planned()).isEqualTo(1);
  }

  @Test
  void pauseAndRestPersistAndNotificationsAreOptIn() {
    long uid = user();
    var s = sessions.start(uid, template(uid).id(), "pause");
    var rested = training.rest(uid, s.id(), 60, false);
    assertThat(training.rest(uid, s.id(), 30, true).restUntil())
        .isCloseTo(
            rested.restUntil().plusSeconds(30),
            org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    training.pause(uid, s.id(), true);
    assertThat(sessions.get(uid, s.id()).pausedAt()).isNotNull();
    assertThat(sessions.get(uid, s.id()).restUntil()).isNull();
    assertThatThrownBy(
            () -> sessions.addSet(uid, s.id(), set(s.exercises().get(0).id(), "70", 8), "paused"))
        .isInstanceOf(DomainException.class);
    jdbc.update(
        "update workout_sessions set started_at=now()-interval '120 seconds',paused_at=now()-interval '60 seconds' where id=?",
        s.id());
    var resumed = training.pause(uid, s.id(), false);
    assertThat(resumed.pausedSeconds()).isBetween(59L, 62L);
    assertThat(resumed.summary().durationSeconds()).isBetween(59L, 62L);
    var client = org.mockito.Mockito.mock(TelegramClient.class);
    jdbc.update(
        "update workout_sessions set rest_until=now()-interval '1 second' where id=?", s.id());
    reminders.deliver(uid, client);
    org.mockito.Mockito.verifyNoInteractions(client);
    planning.preferences(
        uid, new PlanningService.Preferences(3, true, new BigDecimal("2.5"), 8, 12));
    jdbc.update(
        "update workout_sessions set rest_until=now()-interval '1 second' where id=?", s.id());
    reminders.deliver(uid, client);
    reminders.deliver(uid, client);
    org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1))
        .call(
            org.mockito.ArgumentMatchers.eq("sendMessage"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(false));
  }

  @Test
  void csvPreviewIsReadOnlyAliasesResolveAndDuplicatesNeedChoice() {
    long uid = user();
    var bytes =
        (String.join(",", WorkoutCsv.HEADER)
                + "\nPlan,жим лёжа,STRENGTH,3,8,70,90\nPlan,New preview exercise,TIMED,3,,,60")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var preview = imports.preview(uid, bytes);
    assertThat(exercises.search(uid, "New preview exercise", 0)).isEmpty();
    var first = imports.save(uid, preview, null, false);
    assertThat(first.exercises().get(0).exerciseId()).isEqualTo(exercise(uid, "Bench Press"));
    assertThatThrownBy(() -> imports.save(uid, preview, null, false))
        .isInstanceOf(DomainException.class);
    assertThat(imports.save(uid, preview, first.id(), false).id()).isEqualTo(first.id());
    assertThat(templates.list(uid, 0)).hasSize(1);
    imports.save(uid, preview, null, true);
    assertThat(templates.list(uid, 0)).hasSize(2);
  }

  @Test
  void historicalCsvRoundTripsAndDuplicateAndInvalidImportsAreAtomic() {
    long uid = user();
    var active = sessions.start(uid, template(uid).id(), "active");
    var past =
        training.historical(
            uid,
            "manual",
            new TrainingService.HistoricalInput(
                "Past",
                Instant.parse("2026-01-10T10:00:00Z"),
                Instant.parse("2026-01-10T11:00:00Z"),
                List.of(
                    new TrainingService.HistoricalExercise(
                        exercise(uid, "Bench Press"),
                        List.of(
                            new SetInput(
                                0,
                                new BigDecimal("70"),
                                8,
                                null,
                                null,
                                new BigDecimal("8"),
                                "comma, quote\"",
                                true),
                            set(0, "80", 8))))));
    assertThat(sessions.active(uid).orElseThrow().id()).isEqualTo(active.id());
    jdbc.update("update workout_sessions set paused_seconds=120 where id=?", past.id());
    var csv =
        historyTransfer
            .export(uid, YearMonth.of(2026, 1))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    long other = user();
    var copied = historyTransfer.importCsv(other, csv);
    assertThat(copied).hasSize(1);
    assertThat(copied.get(0).pausedSeconds()).isEqualTo(120);
    assertThat(copied.get(0).summary().durationSeconds()).isEqualTo(3480);
    assertThat(copied.get(0).exercises().get(0).sets().get(0).notes()).isEqualTo("comma, quote\"");
    assertThat(copied.get(0).summary().volume()).isEqualByComparingTo(past.summary().volume());
    assertThatThrownBy(() -> historyTransfer.importCsv(other, csv))
        .isInstanceOf(DomainException.class);
    var broken =
        new String(csv, java.nio.charset.StandardCharsets.UTF_8)
            .replace("\"80.000\"", "\"-80\"")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThatThrownBy(() -> historyTransfer.importCsv(user(), broken))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void scheduleUsesLocalTimeAndSuggestionsRequireExplicitApply() {
    long uid = user();
    users.settings(uid, "Europe/Moscow");
    var t = template(uid);
    var local = LocalDateTime.now(ZoneId.of("Europe/Moscow")).plusDays(1).withSecond(0).withNano(0);
    var a = planning.schedule(uid, null, t.id(), local, 2, false);
    assertThat(planning.goal(uid).next().id()).isEqualTo(a.id());
    assertThat(planning.schedule(uid, a.id(), t.id(), local.plusDays(1), 1, true).localStart())
        .isEqualTo(local.plusDays(1));
    assertThatThrownBy(() -> planning.cancel(user(), a.id())).isInstanceOf(DomainException.class);
    planning.cancel(uid, a.id());
    assertThat(planning.schedule(uid)).isEmpty();
    var s = sessions.start(uid, t.id(), "progression");
    for (int i = 0; i < 3; i++)
      sessions.addSet(uid, s.id(), set(s.exercises().get(0).id(), "70", 12), "set" + i);
    sessions.finish(uid, s.id());
    var suggestion = planning.suggest(uid, t.id());
    assertThat(suggestion.template().exercises().get(0).weight()).isEqualByComparingTo("72.5");
    assertThat(templates.get(uid, t.id()).exercises().get(0).targetWeight())
        .isEqualByComparingTo("70");
    assertThat(planning.goal(uid).completed()).isEqualTo(1);
    templates.save(uid, t.id(), suggestion.template());
    assertThat(templates.get(uid, t.id()).exercises().get(0).targetReps()).isEqualTo(8);
  }

  @Test
  void sessionGraphsSeparateSameMonthWorkoutsAndEqualPeriodsExcludeLatePreviousData() {
    long uid = user();
    long eid = exercise(uid, "Bench Press");
    var now = Instant.now();
    for (int i = 0; i < 2; i++)
      training.historical(
          uid,
          "point" + i,
          new TrainingService.HistoricalInput(
              "Points",
              now.minusSeconds(3600 + i * 86400),
              now.minusSeconds(1800 + i * 86400),
              List.of(new TrainingService.HistoricalExercise(eid, List.of(set(0, "70", 8))))));
    assertThat(analytics.progress(uid, eid, "year", true, true).points()).hasSize(2);
    assertThat(analytics.progress(uid, eid, "year", true, false).points()).hasSize(1);
    var fixed =
        new AnalyticsService(
            jdbc,
            users,
            exercises,
            records,
            Clock.fixed(Instant.parse("2026-01-10T12:00:00Z"), ZoneOffset.UTC));
    training.historical(
        uid,
        "prev-early",
        new TrainingService.HistoricalInput(
            "Early",
            Instant.parse("2025-12-05T10:00:00Z"),
            Instant.parse("2025-12-05T11:00:00Z"),
            List.of(new TrainingService.HistoricalExercise(eid, List.of(set(0, "70", 8))))));
    training.historical(
        uid,
        "prev-late",
        new TrainingService.HistoricalInput(
            "Late",
            Instant.parse("2025-12-20T10:00:00Z"),
            Instant.parse("2025-12-20T11:00:00Z"),
            List.of(new TrainingService.HistoricalExercise(eid, List.of(set(0, "70", 8))))));
    assertThat(fixed.report(uid, "month", true, true).previous().workouts()).isEqualTo(1);
    assertThat(fixed.report(uid, "month", true, false).previous().workouts()).isEqualTo(2);
  }

  @Test
  void telegramAdvancedFlowsRemainUsableAfterStateReload() throws Exception {
    Driver d = new Driver();
    d.text("/start");
    d.tap("menu:onboard");
    var t = template(d.uid());
    d.tap("session:start:" + t.id());
    var active = sessions.active(d.uid()).orElseThrow();
    long eid = active.exercises().get(0).id();
    d.tap("session:add:" + active.id() + ":" + eid);
    d.tap("session:manual");
    d.text("w 20 10");
    assertThat(sessions.get(d.uid(), active.id()).exercises().get(0).sets().get(0).warmup())
        .isTrue();
    long setId = sessions.get(d.uid(), active.id()).exercises().get(0).sets().get(0).id();
    d.tap("train:edit:" + active.id() + ":" + eid + ":" + setId);
    d.text("r 70 8 | 8 | fixed");
    assertThat(sessions.get(d.uid(), active.id()).exercises().get(0).sets().get(0).warmup())
        .isFalse();
    d.tap("train:pause:" + active.id() + ":true");
    assertThat(d.screen().text()).contains("Paused");
    d.tap("train:pause:" + active.id() + ":false");
    d.tap("session:finish:" + active.id());
    d.tap("train:compare:" + active.id() + ":0");
    assertThat(d.screen().text()).contains("1 / 3");
    d.tap("train:historicaltemplate:" + t.id());
    d.text("2026-01-10 18:00 19:00");
    d.text("70 8\n70 8");
    d.text("-");
    d.tap("train:historicalsave");
    assertThat(d.screen().text()).contains("Workout completed");
    d.tap("train:export:2026-01");
    assertThat(d.screen().attachment().content()).contains("exercise_position");
    d.tap("train:settings");
    d.tap("train:prefs");
    d.text("4 1.25 6 10");
    assertThat(planning.preferences(d.uid()).weeklyGoal()).isEqualTo(4);
  }

  @Test
  void recurringRemindersRespectTimezoneAndAdvanceOnce() {
    long uid = user();
    users.settings(uid, "Europe/Moscow");
    var t = template(uid);
    ZoneId zone = ZoneId.of("Europe/Moscow");
    var now = LocalDateTime.now(zone);
    var a = planning.schedule(uid, null, t.id(), now.plusDays(1), 2, true);
    var past = now.minusMinutes(1).withNano(0);
    jdbc.update(
        "update training_schedule set local_start=? where id=?",
        java.sql.Timestamp.valueOf(past),
        a.id());
    assertThat(reminders.dueUsers()).contains(uid);
    var client = org.mockito.Mockito.mock(TelegramClient.class);
    reminders.deliver(uid, client);
    reminders.deliver(uid, client);
    assertThat(planning.schedule(uid).get(0).localStart()).isEqualTo(past.plusWeeks(2));
    org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1))
        .call(
            org.mockito.ArgumentMatchers.eq("sendMessage"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(false));
  }

  @Test
  void documentedCsvExamplesAreValidAndPreviewCreatesNothing() throws Exception {
    long uid = user();
    assertThat(
            imports
                .preview(
                    uid,
                    java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of("docs/workout-example.csv")))
                .rows())
        .hasSize(4);
    assertThat(
            imports
                .preview(
                    uid,
                    java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of("docs/workout-per-set-example.csv")))
                .rows()
                .get(0)
                .plan())
        .hasSize(3);
    assertThat(
            historyTransfer
                .preview(
                    uid,
                    java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of("docs/history-example.csv")))
                .get(0)
                .pausedSeconds())
        .isEqualTo(120);
    assertThat(templates.list(uid, 0)).isEmpty();
    assertThat(exercises.search(uid, "Планка", 0)).isEmpty();
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
