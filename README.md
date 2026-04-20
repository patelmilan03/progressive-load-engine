# ⚡ Forgeload — Progressive Load & Recovery Analytics Engine

> *Train smarter. Recover faster. Know when to push and when to rest — mathematically.*

Forgeload is a **command-line Java application** that acts as your personal sports science coach. Every time you launch it, it asks what you did in the gym or on the road, saves it to a local database, and then gives you a data-driven answer to the most important question in training:

**"Should I train hard today, maintain, or rest?"**

---

## Table of Contents

- [The Problem](#the-problem)
- [The Solution](#the-solution)
- [Features](#features)
- [How It Works](#how-it-works)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Quick Start — Local Machine](#quick-start--local-machine)
- [Quick Start — GitHub Codespaces](#quick-start--github-codespaces)
- [First Launch Walkthrough](#first-launch-walkthrough)
- [Daily Usage](#daily-usage)
- [The Science Behind It](#the-science-behind-it)
- [Running the Tests](#running-the-tests)
- [OOP & Design Patterns](#oop--design-patterns)
- [Database Schema](#database-schema)
- [Contributing](#contributing)

---

## The Problem

If you run **and** lift weights, you face a problem that most fitness apps completely ignore.

Your legs are sore from squats on Monday. You have a 5K run on Tuesday. A Pull day on Wednesday. Then Legs again on Thursday. How do you know if your quads have recovered enough from Monday to train them hard again on Thursday? How do you know if Tuesday's run is going to cost you on Wednesday's deadlifts? Most people guess. Most people either overtrain and get injured, or undertrain and plateau.

The harder problem is that **strength fatigue and cardio fatigue are completely different**. A heavy squat session damages muscle fibres — that takes 48–72 hours to repair. A hard tempo run stresses the cardiovascular system and connective tissue — that recovers differently, at a different rate. No single number captures both.

On top of that, even if you track your workouts, the raw numbers are almost useless without context. "I did 5 sets of 10 at 80 kg" tells you nothing about whether that was easy or hard for **you**, or how much recovery you need before doing it again.

---

## The Solution

Forgeload models your body mathematically.

It uses **two different load formulas** — one for strength (RPE-weighted volume load) and one for cardio (Bannister TRIMP, the formula used in elite sport) — to calculate how much physiological stress each session actually placed on your body. It then models how that stress **decays over time**, muscle group by muscle group, using exponential decay with half-lives calibrated from sports science research (quads take ~72 hours to half-recover, calves take ~24 hours).

Every time you open the app, it evaluates all sessions from the last 48 hours, calculates the residual fatigue in every muscle group, and produces:

1. A **Readiness Score** from 1–100
2. A **primary recommendation** (push harder / maintain / deload / rest)
3. A **7-day training plan** adapted to your current fatigue state
4. An **injury risk alert** if your recent training has spiked dangerously above your normal baseline
5. A **PR board** that automatically detects when you break a personal record

All of this is stored locally in a SQLite database file. Your data belongs to you and never leaves your machine.

---

## Features

### Core Analytics
| Feature | What It Does |
|---|---|
| **Readiness Score (1–100)** | Composite fatigue score across all muscle groups and the cardiovascular system |
| **Exponential Decay Model** | Per-muscle recovery modelling with physiologically calibrated half-lives |
| **RPE-Weighted Volume Load** | Strength load formula: `sets × reps × weight × (RPE/10)^1.5 ÷ bodyweight` |
| **Bannister TRIMP** | Cardio load formula used in elite sport: `duration × %HRR × 0.64 × e^(1.92 × %HRR)` |
| **5 Score Bands** | Optimal → Good → Moderate → High Load → Critical, each with specific guidance |

### Planning & Progression
| Feature | What It Does |
|---|---|
| **7-Day Training Plan** | Dynamically adjusts your PPL + 5K week based on current fatigue — deloads or substitutes REST when needed |
| **Progressive Overload Advisory** | Tells you exactly which sessions to increase (+5%), maintain, or reduce (−10% / −50%) |
| **ACWR Injury Risk** | Acute:Chronic Workload Ratio (the gold-standard injury prevention metric from elite sport) |

### Tracking & Records
| Feature | What It Does |
|---|---|
| **Personal Record Detection** | Automatically detects new PRs using the Epley e1RM formula after every session |
| **Wilks Score** | Normalises your lifts against body weight for fair cross-athlete comparison |
| **Strength Standards** | Labels each lift: Beginner / Intermediate / Advanced / Elite |
| **Training Metrics** | Total volume, avg RPE, sessions/week, streak, muscle group distribution, cardio totals |

### User Experience
| Feature | What It Does |
|---|---|
| **First-launch Wizard** | Collects your profile once — height (cm or feet+inches), weight (kg or lbs), HR, experience |
| **Interactive Session Logger** | Guided prompts for logging any session: Push / Pull / Legs / 5K Run / Rest |
| **Colour-coded Terminal UI** | ANSI colours, progress bars, fatigue bars — readable in any terminal |
| **Persistent Local Database** | SQLite file, survives every restart, data never leaves your machine |

---

## How It Works

```
You open Forgeload
        │
        ▼
First time?  ──YES──▶  Onboarding wizard (name, height, weight, HR, experience)
        │                       │
       NO                       │
        │◀──────────────────────┘
        ▼
"Log a session today?"
        │
        ├─ Push / Pull / Legs ──▶ Enter exercises, sets, reps, weight, RPE
        │                                  │
        ├─ 5K Run ─────────────▶ Enter distance, pace, avg HR, max HR
        │                                  │
        └─ Rest ────────────────▶ Noted, skipped                    │
                                           │
                                           ▼
                                  Saved to SQLite
                                           │
                                           ▼
                              Load all sessions from last 48h
                                           │
                                           ▼
                         Calculate residual fatigue per muscle group
                         (exponential decay applied per session age)
                                           │
                                           ▼
                              Readiness Score + Recommendations
                                           │
                                           ▼
                    ┌──────────────────────┼──────────────────────┐
                    │                      │                      │
               7-Day Plan          ACWR Injury Risk          PR Board
               Overload Advisory   Metrics Summary
```

---

## Tech Stack

| Component | Technology |
|---|---|
| Language | Java 17+ |
| Build tool | Apache Maven 3.8+ |
| Database | SQLite (embedded, no server needed) |
| JDBC Driver | `org.xerial:sqlite-jdbc:3.45.1.0` |
| Testing | JUnit 5.10.2 (Jupiter) |
| Packaging | Maven Shade Plugin (fat JAR) |

No Spring, no Hibernate, no external services. Pure Java and a single SQLite file.

---

## Project Structure

```
progressive-load-engine/
├── pom.xml                             Maven build file
├── schema.sql                          SQL table definitions (reference copy)
├── compile_and_run.sh                  Shell script — run without Maven
│
└── src/
    ├── main/
    │   ├── java/com/analytics/engine/
    │   │   │
    │   │   ├── Main.java               Entry point (8 lines — just boots the CLI)
    │   │   │
    │   │   ├── cli/                    ── Terminal Interface ──
    │   │   │   ├── InteractiveCLI.java     Top-level controller
    │   │   │   ├── OnboardingFlow.java     First-launch profile wizard
    │   │   │   ├── SessionLoggerFlow.java  Interactive session entry
    │   │   │   ├── DashboardRenderer.java  Post-session analytics display
    │   │   │   └── InputReader.java        Validated Scanner + ANSI colours
    │   │   │
    │   │   ├── analytics/              ── Algorithms ──
    │   │   │   ├── RecoveryCalculator.java       48h exponential decay → Readiness Score
    │   │   │   ├── LoadCalculator.java           RPE-weighted & TRIMP load formulae
    │   │   │   ├── ProgressiveOverloadAdvisor.java  Volume prescription engine
    │   │   │   ├── WeeklyPlannerService.java     7-day adaptive training plan
    │   │   │   ├── AcuteChronicWorkloadRatio.java   ACWR injury risk (Classic + EWMA)
    │   │   │   ├── PersonalRecordTracker.java    Epley e1RM PR detection
    │   │   │   └── WorkoutMetricsAggregator.java Statistics over any time window
    │   │   │
    │   │   ├── dao/                    ── Database Layer ──
    │   │   │   ├── WorkoutSessionDAO.java    Generic DAO interface (CRUD contract)
    │   │   │   ├── HypertrophySessionDAO.java  3-table transactional CRUD
    │   │   │   ├── CardioSessionDAO.java       2-table transactional CRUD
    │   │   │   ├── UserProfileDAO.java         User CRUD
    │   │   │   ├── PersonalRecordDAO.java       PR CRUD + PR board query
    │   │   │   └── DatabaseManager.java        Singleton connection + schema bootstrap
    │   │   │
    │   │   ├── model/                  ── Domain Objects ──
    │   │   │   ├── WorkoutSession.java     Abstract base (Template Method pattern)
    │   │   │   ├── HypertrophySession.java PPL strength sessions
    │   │   │   ├── CardioSession.java      5K running sessions
    │   │   │   ├── ExerciseSet.java        One exercise entry (sets × reps × weight @ RPE)
    │   │   │   ├── UserProfile.java        Athlete profile (Builder pattern)
    │   │   │   ├── PersonalRecord.java     PR with Epley e1RM + Wilks score
    │   │   │   ├── ReadinessReport.java    Immutable analytics result object
    │   │   │   ├── MuscleGroup.java        Enum with recovery half-lives + fatigue weights
    │   │   │   └── WorkoutType.java        Enum: PUSH | PULL | LEGS | CARDIO_5K | REST
    │   │   │
    │   │   └── util/
    │   │       └── ConsoleFormatter.java   Formatting utilities (legacy demo runner)
    │   │
    │   └── resources/
    │       └── schema.sql              Classpath copy — loaded on first run
    │
    └── test/
        └── java/com/analytics/engine/
            └── EngineTest.java         85 tests across 10 nested suites
```

---

## Quick Start — Local Machine

### Prerequisites

You need two things installed:

- **Java 17 or higher** — [Download from Adoptium](https://adoptium.net) (free, open source)
- **Apache Maven 3.8+** — [Download from Apache](https://maven.apache.org/download.cgi)

To check if you already have them:

```bash
java -version    # should show 17 or higher
mvn -version     # should show 3.8 or higher
```

---

### Step 1 — Clone the repository

```bash
git clone https://github.com/YOUR-USERNAME/forgeload.git
cd forgeload
```

> Replace `YOUR-USERNAME/forgeload` with your actual GitHub repository path.

---

### Step 2 — Build the project

```bash
mvn compile
```

This downloads the two dependencies (SQLite JDBC driver and JUnit) and compiles all 30 Java source files. You should see `BUILD SUCCESS` at the end.

---

### Step 3 — Run it

```bash
mvn exec:java
```

That's it. The application starts, detects that no database exists, and launches the first-time setup wizard.

**Alternative — build a standalone JAR and run it directly:**

```bash
mvn package
java -jar target/progressive-load-engine-1.0.0.jar
```

The JAR includes everything (including the SQLite driver) so you can copy it anywhere and run it without Maven.

---

## Quick Start — GitHub Codespaces

GitHub Codespaces gives you a full Linux development environment in your browser with zero local installation. This is the fastest way to try Forgeload.

### Step 1 — Open in Codespaces

1. Go to your GitHub repository page
2. Click the green **`<> Code`** button
3. Click the **`Codespaces`** tab
4. Click **`Create codespace on main`**

Wait about 30–60 seconds for the environment to spin up. You will see VS Code open in your browser with a terminal at the bottom.

---

### Step 2 — Install Java and Maven in the terminal

Codespaces comes with Java pre-installed but may need Maven. Run:

```bash
# Check Java (should be 17+)
java -version

# Install Maven if not present
sudo apt-get update -q && sudo apt-get install -y maven

# Verify Maven
mvn -version
```

If Java is below version 17, install it:

```bash
sudo apt-get install -y openjdk-21-jdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
```

---

### Step 3 — Navigate to the project

```bash
# If your repo has the project in a subdirectory:
cd progressive-load-engine

# Verify you can see pom.xml
ls pom.xml
```

---

### Step 4 — Build and run

```bash
mvn compile
mvn exec:java
```

The interactive terminal session runs directly in the Codespaces terminal. All data is saved to `training_engine.db` inside your Codespace — it persists as long as the Codespace exists.

> **Note on Codespace persistence:** Your data is saved within the Codespace environment. If you delete the Codespace, `training_engine.db` is deleted too. To keep your data permanently, copy `training_engine.db` to your local machine or commit it to your repo.

---

### Codespaces — Add a `devcontainer.json` for automatic setup (optional)

If you want Codespaces to auto-install everything without manual commands, add this file to your repository:

**`.devcontainer/devcontainer.json`**

```json
{
  "name": "Forgeload Dev Environment",
  "image": "mcr.microsoft.com/devcontainers/java:21",
  "features": {
    "ghcr.io/devcontainers/features/java:1": {
      "version": "21",
      "installMaven": "true"
    }
  },
  "postCreateCommand": "mvn compile -q",
  "customizations": {
    "vscode": {
      "extensions": [
        "vscjava.vscode-java-pack"
      ]
    }
  }
}
```

With this file in place, the next time someone opens your repo in Codespaces, Java 21 and Maven will be pre-installed and the project will be pre-compiled automatically.

---

## First Launch Walkthrough

When you run Forgeload for the first time, the database file does not exist yet. The app detects this and runs the onboarding wizard:

```
  ╔══════════════════════════════════════════════════════════╗
  ║    ⚡  Progressive Load & Recovery Analytics Engine  ⚡   ║
  ╚══════════════════════════════════════════════════════════╝

  Welcome! This looks like your first time here.

  ══  Let's set up your athlete profile  ══

  This only runs once. Everything is saved to training_engine.db

  Your full name: Alex Rivera

  Height — enter in cm (e.g. 182) or feet+inches (e.g. 6'1)
  Height: 6'1

  Body weight — enter in kg (e.g. 82.5) or lbs (e.g. 182lbs)
  Weight: 185lbs
  Converting 185.0 lbs → 83.9 kg

  Your age (14–99): 27
  Resting heart rate (bpm) [60]: 58
  Max heart rate (bpm) [193]: 192
  Years of training experience [1.0]: 3.5

  ✔  Profile created!
  Does this look correct? [Y/n]: y
```

**Notes on input format:**
- Height accepts `182` (cm) or `6'1` / `6ft1` / `6 ft 1 in` (feet and inches)
- Weight accepts `82.5` (kg) or `182lbs` / `182 lbs` (pounds — auto-converted)
- Heart rate fields have defaults in brackets — press Enter to accept them
- Experience is in decimal years: `0.5` = 6 months, `2` = two years, `5` = five years

After the wizard, it immediately asks if you want to log today's session.

---

## Daily Usage

After setup, every subsequent launch looks like this:

```
  ╔══════════════════════════════════════════════════════════╗
  ║    ⚡  Welcome back, Alex!                               ║
  ╚══════════════════════════════════════════════════════════╝

  Log a session from today? [Y/n]:
```

**If you trained today — say Yes, then pick your session type:**

```
  1  Push Day     (Chest · Shoulders · Triceps)
  2  Pull Day     (Back · Biceps · Rear Delts)
  3  Legs Day     (Quads · Hamstrings · Glutes · Calves)
  4  5K Run       (Cardio session)
  5  Rest Day     (Full rest or light walk)

  Your choice →: 1
```

**For a strength session, enter exercises one by one:**

```
  Exercise name (or 'done' to finish): Barbell Bench Press
  Primary muscle: 1 (CHEST)
  Sets (1–20): 4
  Reps per set (1–100): 8
  Weight (kg, use 0 for bodyweight) (0–500): 80
  RPE (1–10): 8.0
  ✔  Added: Barbell Bench Press  4x8 @ 80.0 kg  RPE 8.0  → e1RM 101.3 kg

  Exercise name (or 'done' to finish): done

  ✔  Session logged: 1 exercise, 4 sets, 2560 kg total volume.
```

**After logging, you see your Readiness Score and can choose what analytics to view:**

```
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  🟢 READINESS SCORE   91 / 100   Optimal
  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  [████████████████████]  91%

  ✔  Readiness is OPTIMAL. Consider progressive overload —
     increase Push Day volume by +5% (target: CHEST).

  What would you like to see?
  1  7-Day Training Plan
  2  Overload Advisory
  3  ACWR Injury Risk
  4  Metrics Summary
  5  PR Board
  6  All of the above
  7  Nothing — I'm done
```

**If you didn't train — say No:**

The app skips session entry and goes straight to your Readiness Score and dashboard based on your existing history.

---

## The Science Behind It

### Strength Load Formula (RPE-Weighted Volume Load)

```
raw_volume  = sets × reps × weight_kg
adjusted_VL = raw_volume × (RPE / 10) ^ 1.5
session_load = Σ adjusted_VL / bodyweight_kg × relative_coeff × tolerance_factor
```

The RPE exponent of 1.5 means working at RPE 9.5 produces roughly 2.7× the fatigue of RPE 7 for the same bar weight and rep count — reflecting the real disproportionate cost of training close to failure.

### Cardio Load Formula (Bannister TRIMP)

```
%HRR  = (avg_heart_rate − resting_HR) / (max_HR − resting_HR)
TRIMP = duration_minutes × %HRR × 0.64 × e^(1.92 × %HRR)
load  = TRIMP / 100
```

The exponential term `e^(1.92 × %HRR)` means a Zone 4 run (84% HRR) accumulates roughly 3× more fatigue than a Zone 2 jog (65% HRR) for the same duration.

### Recovery Model (Exponential Decay)

```
F(t) = F₀ × e^(−λ × t)     where λ = ln(2) / t½
```

Each muscle group has its own half-life `t½`, the time for 50% of its fatigue to clear:

| Muscle Group | Half-life |
|---|---|
| Quads, Hamstrings | 72 hours |
| Back, Glutes | 60 hours |
| Chest | 48 hours |
| Shoulders, Biceps, Triceps | 36 hours |
| Calves, Core | 24 hours |

### ACWR Injury Risk (Gabbett 2016)

```
Acute Load   = total training load in the last 7 days
Chronic Load = average weekly load over the last 28 days
ACWR         = Acute Load / Chronic Load
```

| ACWR | Zone | Risk |
|---|---|---|
| < 0.80 | Under-Training | High (detraining risk) |
| 0.80–1.30 | Sweet Spot | Low |
| 1.30–1.50 | Caution | Moderate (+15%) |
| > 1.50 | Danger Zone | High (2–4× injury risk) |

---

## Running the Tests

```bash
# Run all 85 tests
mvn test

# Run with detailed output showing each test name
mvn test -Dsurefire.useFile=false
```

The test suite is split into two categories:

**Pure unit tests** — test mathematical formulas and business logic in isolation, no database involved. These cover Epley e1RM accuracy, TRIMP load values, exponential decay constants, score band boundaries, RPE validation, and more.

**DAO integration tests** — test database round-trips against an in-memory SQLite database. These verify that data written across multiple tables comes back correctly assembled, that transactions roll back on failure, that cascade deletes work, and that time-window filtering is accurate.

Expected result: `85 tests, 0 failures`.

---

## OOP & Design Patterns

The project is built on core Java OOP principles throughout:

| Concept | Where Used |
|---|---|
| **Inheritance** | `HypertrophySession` and `CardioSession` both extend abstract `WorkoutSession` |
| **Polymorphism** | Analytics engine accepts `List<WorkoutSession>` — processes both session types uniformly via runtime dispatch |
| **Encapsulation** | All fields `private final`; no public field access anywhere; unmodifiable list/map wrappers |
| **Abstraction** | `WorkoutSession` (abstract class) hides load formula details; `WorkoutSessionDAO<T>` (interface) hides SQL details |
| **Template Method** | `WorkoutSession.calculateLoad()` is `final`; subclasses implement `computeRawLoad()` hook only |
| **Builder Pattern** | `UserProfile.builder()` and `ExerciseSet.builder()` — validated construction with fluent API |
| **Singleton Pattern** | `DatabaseManager.getInstance()` — single shared SQLite connection |
| **DAO Pattern** | All SQL isolated in DAO classes — analytics layer never imports JDBC |
| **Composition** | Analytics services composed together, not inherited — each independently testable |

---

## Database Schema

All data is stored in a single local file `training_engine.db` in your working directory.

```
users
  └── workout_sessions
        ├── hypertrophy_sessions
        │     └── exercise_sets  (one row per exercise per session)
        └── cardio_sessions

  └── personal_records
```

The database is created automatically on first run. All tables use `CREATE TABLE IF NOT EXISTS` — your data survives every restart. Cascade deletes are enabled: deleting a session automatically removes all its exercises.

**To reset everything and start fresh:**

```bash
rm training_engine.db
mvn exec:java
```

**To back up your data:**

```bash
cp training_engine.db training_engine_backup_$(date +%Y%m%d).db
```

---

## Resetting Your Profile

If you want to re-enter your athlete profile (for example, you changed body weight significantly):

```bash
# Full reset — deletes all sessions AND profile
rm training_engine.db
mvn exec:java

# Weight update only — use the SQLite CLI if installed
sqlite3 training_engine.db "UPDATE users SET weight_kg = 80.0 WHERE id = 1;"
```

---

## Contributing

Pull requests are welcome. For significant changes, open an issue first to discuss the direction.

**To add a new exercise type or workout structure:**
- Add a value to `WorkoutType.java` if needed
- Add recovery constants to `MuscleGroup.java` for new muscle groups
- Extend `SessionLoggerFlow.relevantMuscles()` to surface the right muscles in the CLI

**To add a new analytics feature:**
- Add a new class in `com.analytics.engine.analytics`
- Wire it into `DashboardRenderer` with a new menu option
- Add tests in a new `@Nested` class in `EngineTest.java`


---

<div align="center">

**Built with Java 17 · SQLite · Maven · JUnit 5**

*No cloud. No subscription. No data collection. Just math and your training log.*

</div>
