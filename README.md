# Telegram Workout Tracker

A Telegram-first workout log: **Plan → Train → Record → Analyze → Repeat**.

Java 17, Spring Boot 3.5, Spring Data JPA, PostgreSQL 17, Flyway, Maven and Docker Compose. The bot uses inline keyboards and edits one persistent interface message. Confirmed sets are committed immediately.

## Run with Docker

Requirements: Docker Engine / Docker Desktop with Compose, and a Telegram bot token to enable Telegram. No local Java or Maven installation is needed for Docker deployment.

1. Create a bot by messaging [@BotFather](https://t.me/BotFather) in Telegram. Send `/newbot`, choose a name and username, and copy the token into your **local** `.env` file.
2. Copy `.env.example` to `.env` if it does not already exist:

   ```sh
   cp .env.example .env
   # PowerShell: Copy-Item .env.example .env
   ```

3. Set `POSTGRES_PASSWORD` and `INTERNAL_API_KEY` to separate random secrets. Set `TELEGRAM_BOT_TOKEN` and `TELEGRAM_ENABLED=true` to enable the bot. Keep `.env` private; it is ignored by Git and Docker build context.
4. Start:

   ```sh
   docker compose up --build -d
   docker compose logs -f app
   ```

5. Open the bot in a **private chat**, send `/start`, and tap **Start**. Commands are registered automatically when Telegram is enabled.

The app is available at `http://localhost:8080`. Compose binds HTTP to loopback and does not publish PostgreSQL. You can leave `TELEGRAM_ENABLED=false` to use the API and tests without a bot token. An enabled bot with an empty token fails startup.

If this workspace already contains a generated `.env`, it has local database/API secrets and Telegram disabled. Add your own bot token and enable it, then run `docker compose up -d app` to apply environment changes.

```sh
docker compose ps
curl http://localhost:8080/actuator/health
docker compose restart app       # Exercise restart recovery
docker compose stop              # Stop, preserving containers and database
docker compose down              # Remove containers; database volume remains
```

Do not remove the `postgres_data` volume if you want to keep workout history. Back up PostgreSQL before migrations or host maintenance. An example backup command is `docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' > workout.sql`; keep backups outside the repository and test restoration separately.

## Use the bot

| Command | Purpose |
| --- | --- |
| `/start` | Welcome, dashboard, or active workout recovery |
| `/menu` | Main dashboard |
| `/workout` | Select or create a template |
| `/history` | Completed workout history |
| `/progress` | Period reports and exercise progress |
| `/cancel` | Leave the current input flow; does **not** cancel a workout |

Create a workout through **Workouts → Create workout → name → Add exercise → Save workout**. Browse the seeded catalog, search by name, or create a custom exercise. Edit a saved template to rename it, change its description, add/remove/reorder exercises, or configure targets. Changes to templates never rewrite session history.

Optional targets use `sets reps weight rest_seconds`, with `-` for omitted values. For example, `4 8 70 90` or `3 12 - 60`. The editor keeps its draft in PostgreSQL until Save. Leaving a flow through `/cancel` discards the input draft, not a saved template or session.

During a workout:

- **Add set → weight → repetitions** saves a strength/bodyweight set. Weight and rep suggestions come from current or previous sets, then template targets.
- **Repeat [previous set]** records another set in one tap. RPE and notes are deliberately not copied.
- **Undo last set** reverses that specific set. Repeated undo taps do not remove additional sets.
- Previous/next exercise navigation preserves every confirmed set.
- Finish and cancel both require a confirmation screen. Cancelling retains data but excludes the workout from completed history and analytics.
- `/start` offers Continue, Finish and Cancel when an active workout exists, including after a restart.

Manual set entry:

| Type | Format | Example |
| --- | --- | --- |
| Strength | `weight_kg reps` | `70 8` |
| Bodyweight | `reps [added_weight_kg]` | `12` or `12 5` |
| Cardio | `duration_seconds distance_km` | `1800 5` |
| Timed | `duration_seconds` | `60` |

Append optional `| RPE | notes`, such as `70 8 | 8.5 | Good depth`. Use `-` to omit RPE. These inputs save immediately. Weight and distance support up to three decimal places; RPE supports one. Bounds are enforced in the application and database.

## History and progress

History is paginated by month; each workout has a summary and paginated exercise/set details. Exercise history shows recent performance and links to full workouts.

Progress includes this week, this month, the last three calendar months (including the current month), and this calendar year. Reports include workout count, exercises performed, sets, repetitions, training duration, strength volume, daily frequency, and comparison with the **previous full period**. Partial current periods are therefore not like-for-like comparisons. A zero baseline is shown without a percentage.

Calendar boundaries use the user's IANA timezone, configurable in Settings; weeks start on Monday. Session start time determines which period contains the workout. Only completed workouts count. An exercise is counted as performed only if it has a non-voided set. Frequency averages use elapsed calendar days.

Strength volume is `sum(weight × reps)` for strength exercises. Bodyweight, timed and cardio metrics do not inflate strength volume. Session duration is elapsed wall-clock time between start and finish; there is no pause clock.

After completion, strength records track max weight, max repetitions, best set volume, best session volume, and Epley estimated 1RM. A single-rep set uses its actual weight; zero-rep sets have no 1RM estimate. Exercise progression groups monthly maxima: estimated 1RM for strength, reps for bodyweight, distance for cardio, duration for timed exercises. High-rep 1RM estimates are approximate.

## Internal REST API and OpenAPI

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Generated specification snapshot: [docs/openapi.json](docs/openapi.json)
- Health: `http://localhost:8080/actuator/health`
- Metrics: `http://localhost:8080/actuator/prometheus` (requires `X-Api-Key`)

OpenAPI and health are public on the local HTTP listener; workout data is not. Every `/api/*` request requires:

```text
X-Api-Key: <INTERNAL_API_KEY>
X-Telegram-User-Id: <positive Telegram user ID>
```

The key authenticates a **trusted internal caller allowed to act for users**. This is not end-user authentication. Never ship this key in a browser/mobile frontend or expose the service directly to the internet. Add an authentication gateway that verifies Telegram identity before a public frontend. An empty API key disables internal API access with HTTP 503.

Use Swagger's Authorize control to supply both headers. `POST /api/me` permits trusted development/API callers to register an identity without opening Telegram.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| POST / GET / PATCH | `/api/me` | Register, read profile, update timezone |
| GET / POST | `/api/exercises` | Search catalog / create custom exercise |
| GET / POST | `/api/workouts` | List / create templates |
| GET / PUT / DELETE | `/api/workouts/{id}` | Read / replace / soft-delete template |
| POST | `/api/sessions` | Start a template |
| GET | `/api/sessions/active` | Recover active session |
| GET | `/api/sessions/{id}` | Full session and calculated summary |
| POST | `/api/sessions/{id}/sets` | Record metrics |
| DELETE | `/api/sessions/{id}/sets/{setId}` | Undo a specific active-session set |
| PATCH | `/api/sessions/{id}/position` | Select current exercise |
| POST | `/api/sessions/{id}/finish` | Complete and update records |
| POST | `/api/sessions/{id}/cancel` | Cancel, retaining data |
| POST | `/api/sessions/{id}/repeat` | Start from a completed session snapshot |
| GET | `/api/history?month=2026-09&page=0` | Completed workouts in a local month |
| GET | `/api/exercises/{id}/history?page=0` | Exercise history |
| GET | `/api/exercises/{id}/progress?period=quarter` | Progress and all-time records |
| GET | `/api/analytics/{period}` | `week`, `month`, `quarter`, `year`; aliases `weekly`, `monthly`, `yearly` |

Lists have fixed page sizes: 8 templates/catalog/history items or 5 exercise-history workouts. Start, repeat and set-recording requests require an `Idempotency-Key` header (1–100 characters). Reuse it for a network retry; use a new one for an intentional new action. A conflicting payload with the same key returns 409. Finish, cancel and undo are naturally idempotent. Template creation and custom-exercise creation do not use request idempotency keys.

Example template body (obtain exercise IDs through `/api/exercises?q=Bench` first):

```json
{
  "name": "Push Day",
  "description": "Chest and shoulders",
  "exercises": [
    {"exerciseId": 1, "sets": 3, "reps": 8, "weight": 70, "restSeconds": 90},
    {"exerciseId": 16, "sets": 3, "reps": 10, "weight": 20, "restSeconds": 60}
  ]
}
```

Start body: `{"templateId": 1}`. Record body: `{"sessionExerciseId": 1, "weight": 70, "repetitions": 8}`. Use the **session exercise ID returned by the started session**, not a catalog exercise ID. Settings body: `{"timezone":"Europe/Moscow"}`. API errors use Problem Detail responses without stack traces.

## Architecture

The application is a modular monolith. Telegram handlers and REST controllers share transactional application services; calculations, validation and persistence do not live in the bot transport.

```text
telegram/{bot,callback,handler,message}  Transport, durable UI, routing and screens
workout/{domain,application,api}         Templates, sessions, sets and session rules
exercise/{domain,application,api}        Global catalog and private custom exercises
analytics/{domain,application,api}       Period read models and personal records
user/{domain,application,api}            Telegram identity and settings
common/{api}                           Validation, errors, API authentication, OpenAPI
```

Spring Data repository interfaces persist aggregate roots. JPA owns transactional writes; analytics uses parameterized SQL projections to avoid materializing every historic set. `Clock` is injectable. API DTOs are separate from JPA entities. There are no AI services or unnecessary distributed components.

Reliability decisions:

- Per-user database locks serialize mutations across Telegram and REST. A PostgreSQL partial unique index is the final guard against multiple active workouts.
- Session exercises snapshot the template's order, name, metric type and targets. Template deletion is soft; repeat can use a completed snapshot after its template is deleted.
- Request keys make start/repeat and set recording retry-safe. Undo marks a set voided instead of deleting its idempotency record.
- Each Telegram screen has a revision in its callback data. Buttons from older revisions or another message cannot mutate the current flow.
- An update's business writes, next polling offset, flow state and pending screen commit in one transaction. Database errors leave the update unacknowledged for retry.
- A PostgreSQL-backed screen outbox retries Telegram failures without repeating business writes. Message edits are preferred; a deleted/uneditable interface gets a replacement message. Rate limits honor `retry_after`. API URLs containing the bot token are excluded from transport exceptions.
- No database schema is created by Hibernate: `ddl-auto=validate`; Flyway is authoritative.

## Database

| Table | Role |
| --- | --- |
| `users` | Telegram identity, onboarding and timezone |
| `exercises` | Seeded global catalog / user-owned custom exercises |
| `workout_templates` | Reusable definitions with soft deletion |
| `workout_template_exercises` | Ordered exercises and optional targets |
| `workout_sessions` | ACTIVE / COMPLETED / CANCELLED, timing and request identity |
| `workout_session_exercises` | Immutable template snapshots |
| `exercise_sets` | Metrics, notes, RPE, request keys and undo tombstones |
| `personal_records` | Strength records updated atomically on completion |
| `bot_states` | Flow, draft JSON, screen revision, message identity, pending delivery |
| `bot_cursor` | Next durable Telegram polling offset |

Versioned migrations live under `src/main/resources/db/migration`. The initial catalog contains 27 exercises across chest, back, legs, shoulders, arms, core and cardio, including the requested Bench Press and Shoulder Press. New migrations must be appended; do not edit migrations already applied to a real database.

## Develop and test

Install JDK 17+ and run Docker. The Maven wrapper downloads Maven 3.9.11 automatically.

```sh
./mvnw test          # Unit, domain and Telegram HTTP-adapter tests
./mvnw verify        # Also real PostgreSQL Testcontainers tests and formatting checks
./mvnw spotless:apply
# Windows: use .\mvnw.cmd in place of ./mvnw
```

Integration tests require Docker and fail if it is unavailable; they are not silently skipped. They use temporary databases, never your Compose volume. CI runs `verify` on Ubuntu.

For a running Compose deployment, `powershell -File scripts/smoke-test.ps1 -Restart` exercises the REST workout loop and restarts the app to verify persistence. It creates and retains data under a new synthetic identity, without contacting Telegram. Omit `-Restart` to leave the app running throughout the check.

The tests cover the exact Push Day scenario (three 70×8 sets and 20×10, 20×10, 20×8), 2,240 kg volume, previous performance, stale callbacks, concurrent duplicates, undo replay, ownership, all metric types, timezone boundaries, record detection, immutable snapshots, migrations, API authentication, OpenAPI, and recovery in a new Spring application context. The Telegram HTTP adapter is tested with a local fake Telegram server.

For a local Java process, provide `SPRING_DATASOURCE_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `INTERNAL_API_KEY`, and optionally the Telegram variables in the process environment, then run `./mvnw spring-boot:run`. Spring does not automatically load `.env`; Compose does. You can run a development database with the included override:

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db
# Local Java URL: jdbc:postgresql://localhost:5433/workout
```

Structured ECS JSON logs go to stdout. Actuator supplies health and Prometheus metrics; `workout.telegram.updates` and `workout.telegram.errors` track transport activity. Do not enable HTTP wire logging when using a real bot token.

## MVP boundaries

- One polling application instance per bot/database; no webhook or multi-replica coordination. Disable/remove any existing Telegram webhook before using long polling. A 409 from Telegram generally means another consumer or webhook is active.
- Live Telegram delivery requires your token and an outbound HTTPS connection. Automated tests do not contact a real Telegram account.
- English UI and kg/km only; timezone is configurable. No calorie/nutrition tracking, AI coaching, social features, payments, wearables, or generated chart images.
- Rest intervals are reference targets, not notifications/timers. There is no pause clock or editing of completed workouts.
- Templates are user-created; the exercise catalog is seeded, but predefined workout programs are not.
- Limits: 30 exercises/template, 100 live sets/exercise, 80-character names, 500-character notes/descriptions. Workout detail screens paginate sets; compact screens abbreviate long exercise names or show recent sets.
- Initial message creation cannot be exactly-once across a crash between Telegram accepting `sendMessage` and saving its message ID. A rare duplicate interface message is possible; confirmed sets remain deduplicated.
- UTC is the onboarding default. Identity rows are created on first contact to store the welcome screen; onboarding completes after Start is pressed.

API and platform references: [Telegram Bot API](https://core.telegram.org/bots/api), [Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
