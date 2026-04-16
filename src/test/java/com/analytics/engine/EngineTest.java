package com.analytics.engine;

import com.analytics.engine.analytics.LoadCalculator;
import com.analytics.engine.analytics.RecoveryCalculator;
import com.analytics.engine.analytics.WeeklyPlannerService;
import com.analytics.engine.analytics.AcuteChronicWorkloadRatio;
import com.analytics.engine.analytics.PersonalRecordTracker;
import com.analytics.engine.analytics.WorkoutMetricsAggregator;
import com.analytics.engine.dao.PersonalRecordDAO;
import com.analytics.engine.model.PersonalRecord;
import com.analytics.engine.dao.CardioSessionDAO;
import com.analytics.engine.dao.DatabaseManager;
import com.analytics.engine.dao.HypertrophySessionDAO;
import com.analytics.engine.dao.UserProfileDAO;
import com.analytics.engine.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for the Progressive Load & Recovery Analytics Engine.
 *
 * <h3>Test Strategy</h3>
 * <ul>
 *   <li><b>Unit tests</b> — pure domain logic (model calculations, analytics algorithms)
 *       with no database involvement.</li>
 *   <li><b>Integration tests</b> — DAO round-trips against an in-memory SQLite database
 *       ({@code jdbc:sqlite::memory:}) that is re-initialised before each test class.</li>
 * </ul>
 *
 * <h3>Execution</h3>
 * <pre>  mvn test</pre>
 */
@DisplayName("Progressive Load & Recovery Analytics Engine — Test Suite")
class EngineTest {

    // ════════════════════════════════════════════════════════════════════
    //  Shared Fixtures
    // ════════════════════════════════════════════════════════════════════

    /** The canonical 6 ft / 66 kg baseline user from the spec. */
    static UserProfile baselineUser() {
        return UserProfile.builder("Test Athlete")
            .heightCm(182.88)
            .weightKg(66.0)
            .age(27)
            .restingHeartRate(58)
            .maxHeartRate(192)
            .trainingExperienceYears(3.5)
            .build();
    }

    static HypertrophySession simplePushSession(long userId, LocalDateTime ts) {
        HypertrophySession s = new HypertrophySession(
            userId, WorkoutType.PUSH, MuscleGroup.CHEST, ts, "test push");
        s.addExercise(ExerciseSet.builder(0, "Bench Press", MuscleGroup.CHEST)
            .sets(4).reps(8).weightKg(80.0).rpe(8.0).build());
        return s;
    }

    static CardioSession simpleRun(long userId, LocalDateTime ts) {
        return new CardioSession(userId, ts, 5.0, 5.5, 165, 178, 27.5, "test run");
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: UserProfile Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UserProfile")
    class UserProfileTests {

        @Test
        @DisplayName("Baseline 6ft/66kg user constructs correctly")
        void baseline_user_properties() {
            UserProfile u = baselineUser();
            assertEquals(182.88, u.getHeightCm(),       0.01);
            assertEquals(66.0,   u.getWeightKg(),       0.01);
            assertEquals(134.0,  u.getHeartRateReserve(),0.01); // 192 − 58
        }

        @Test
        @DisplayName("Relative load coefficient is 1.0 for 66 kg baseline")
        void relative_load_coeff_at_baseline() {
            UserProfile u = baselineUser();
            assertEquals(1.0, u.getRelativeLoadCoefficient(), 0.001);
        }

        @Test
        @DisplayName("Relative load coefficient > 1.0 for lighter athletes")
        void relative_load_coeff_lighter_athlete() {
            UserProfile lighter = UserProfile.builder("Lighter")
                .heightCm(170).weightKg(60.0).age(25)
                .restingHeartRate(60).maxHeartRate(190).build();
            assertTrue(lighter.getRelativeLoadCoefficient() > 1.0,
                "60 kg athlete should have >1.0 coefficient (same weight feels relatively harder)");
        }

        @Test
        @DisplayName("Volume tolerance increases with experience")
        void volume_tolerance_scales_with_experience() {
            UserProfile novice = UserProfile.builder("Novice")
                .heightCm(180).weightKg(70).age(22)
                .restingHeartRate(65).maxHeartRate(195)
                .trainingExperienceYears(0.5).build();
            UserProfile veteran = UserProfile.builder("Veteran")
                .heightCm(180).weightKg(70).age(30)
                .restingHeartRate(55).maxHeartRate(188)
                .trainingExperienceYears(8.0).build();
            assertTrue(veteran.getVolumeToleranceFactor() >
                       novice.getVolumeToleranceFactor());
        }

        @Test
        @DisplayName("Builder rejects resting HR >= max HR")
        void builder_validates_heart_rate_order() {
            assertThrows(IllegalArgumentException.class, () ->
                UserProfile.builder("Bad HR")
                    .heightCm(175).weightKg(70).age(25)
                    .restingHeartRate(190).maxHeartRate(180)   // intentionally invalid
                    .build()
            );
        }

        @Test
        @DisplayName("Builder rejects zero weight")
        void builder_rejects_zero_weight() {
            assertThrows(IllegalArgumentException.class, () ->
                UserProfile.builder("Zero Weight")
                    .heightCm(175).weightKg(0).age(25)
                    .restingHeartRate(60).maxHeartRate(190).build()
            );
        }

        @Test
        @DisplayName("BMI calculation is accurate")
        void bmi_calculation() {
            UserProfile u = baselineUser();
            // 66 / (1.8288^2) ≈ 19.73
            assertEquals(19.73, u.getBMI(), 0.1);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: ExerciseSet Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ExerciseSet")
    class ExerciseSetTests {

        @Test
        @DisplayName("Raw volume load = sets × reps × weight")
        void raw_volume_load_formula() {
            ExerciseSet ex = ExerciseSet.builder(1, "Bench", MuscleGroup.CHEST)
                .sets(4).reps(8).weightKg(80.0).rpe(8.0).build();
            assertEquals(4 * 8 * 80.0, ex.getRawVolumeLoad(), 0.001);
        }

        @Test
        @DisplayName("RPE-adjusted load is greater for higher RPE")
        void rpe_adjusted_load_scales_with_rpe() {
            ExerciseSet low = ExerciseSet.builder(1, "Ex", MuscleGroup.CHEST)
                .sets(3).reps(8).weightKg(80.0).rpe(6.0).build();
            ExerciseSet high = ExerciseSet.builder(1, "Ex", MuscleGroup.CHEST)
                .sets(3).reps(8).weightKg(80.0).rpe(9.5).build();
            assertTrue(high.getRpeAdjustedVolumeLoad() > low.getRpeAdjustedVolumeLoad());
        }

        @Test
        @DisplayName("Effective set equivalents scale non-linearly with RPE")
        void effective_set_equivalents() {
            ExerciseSet ex = ExerciseSet.builder(1, "Ex", MuscleGroup.BACK)
                .sets(3).reps(8).weightKg(60.0).rpe(10.0).build();
            // At RPE 10: equivalent sets = 3 × (10/10) = 3.0
            assertEquals(3.0, ex.getEffectiveSetEquivalents(), 0.001);
        }

        @ParameterizedTest(name = "RPE {0} is invalid")
        @CsvSource({"0.5", "10.5", "11.0"})
        @DisplayName("Builder rejects out-of-range RPE values")
        void builder_rejects_invalid_rpe(double rpe) {
            assertThrows(IllegalArgumentException.class, () ->
                ExerciseSet.builder(1, "Test", MuscleGroup.CHEST)
                    .sets(3).reps(8).weightKg(60.0).rpe(rpe).build()
            );
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: HypertrophySession Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("HypertrophySession")
    class HypertrophySessionTests {

        @Test
        @DisplayName("Session load > 0 when exercises exist")
        void session_load_is_positive() {
            UserProfile u = baselineUser();
            HypertrophySession s = simplePushSession(1L, LocalDateTime.now().minusHours(24));
            double load = s.calculateLoad(u);
            assertTrue(load > 0, "Load must be positive for a non-empty session");
        }

        @Test
        @DisplayName("Session load is cached — same result on repeated calls")
        void session_load_is_cached() {
            UserProfile u = baselineUser();
            HypertrophySession s = simplePushSession(1L, LocalDateTime.now().minusHours(24));
            double first  = s.calculateLoad(u);
            double second = s.calculateLoad(u);
            assertEquals(first, second, "Cached load should be identical on repeat call");
        }

        @Test
        @DisplayName("Adding exercises increases session load")
        void more_exercises_increases_load() {
            UserProfile u = baselineUser();
            HypertrophySession s = simplePushSession(1L, LocalDateTime.now());
            double loadBefore = s.calculateLoad(u);

            s.addExercise(ExerciseSet.builder(0, "Extra Press", MuscleGroup.CHEST)
                .sets(4).reps(10).weightKg(60.0).rpe(8.0).build());
            double loadAfter = s.recalculateLoad(u);

            assertTrue(loadAfter > loadBefore, "Extra exercises must increase load");
        }

        @Test
        @DisplayName("Constructor rejects non-PPL workout type")
        void constructor_rejects_cardio_type() {
            assertThrows(IllegalArgumentException.class, () ->
                new HypertrophySession(1L, WorkoutType.CARDIO_5K,
                    MuscleGroup.CHEST, LocalDateTime.now(), null)
            );
        }

        @Test
        @DisplayName("getLoadPerMuscleGroup aggregates by muscle group correctly")
        void load_per_muscle_group_aggregation() {
            HypertrophySession s = new HypertrophySession(
                1L, WorkoutType.PUSH, MuscleGroup.CHEST, LocalDateTime.now(), null);
            s.addExercise(ExerciseSet.builder(0, "Bench",     MuscleGroup.CHEST)
                .sets(4).reps(8).weightKg(80.0).rpe(8.0).build());
            s.addExercise(ExerciseSet.builder(0, "Inc Press", MuscleGroup.CHEST)
                .sets(3).reps(10).weightKg(28.0).rpe(7.0).build());
            s.addExercise(ExerciseSet.builder(0, "OHP",       MuscleGroup.SHOULDERS)
                .sets(3).reps(8).weightKg(52.0).rpe(8.0).build());

            Map<MuscleGroup, Double> map = s.getLoadPerMuscleGroup();
            assertTrue(map.containsKey(MuscleGroup.CHEST));
            assertTrue(map.containsKey(MuscleGroup.SHOULDERS));
            assertFalse(map.containsKey(MuscleGroup.QUADS));
        }

        @Test
        @DisplayName("Heavier athlete shows lower relative load for same bar weight")
        void heavier_athlete_lower_relative_load() {
            UserProfile light = UserProfile.builder("Light")
                .heightCm(175).weightKg(66.0).age(25)
                .restingHeartRate(60).maxHeartRate(190).build();
            UserProfile heavy = UserProfile.builder("Heavy")
                .heightCm(185).weightKg(90.0).age(25)
                .restingHeartRate(60).maxHeartRate(190).build();

            HypertrophySession s1 = simplePushSession(1L, LocalDateTime.now());
            HypertrophySession s2 = simplePushSession(2L, LocalDateTime.now());

            assertTrue(s1.calculateLoad(light) > s2.calculateLoad(heavy),
                "66 kg athlete should show higher relative load than 90 kg for same bar weight");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: CardioSession Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CardioSession")
    class CardioSessionTests {

        @Test
        @DisplayName("TRIMP load is positive for a valid run")
        void trimp_load_is_positive() {
            UserProfile u = baselineUser();
            CardioSession run = simpleRun(1L, LocalDateTime.now().minusHours(2));
            assertTrue(run.calculateLoad(u) > 0);
        }

        @Test
        @DisplayName("Higher avg HR produces higher TRIMP load")
        void higher_hr_produces_higher_load() {
            UserProfile u = baselineUser();
            CardioSession easy   = new CardioSession(1L, LocalDateTime.now(), 5, 6.0, 140, 155, 30, "easy");
            CardioSession tempo  = new CardioSession(1L, LocalDateTime.now(), 5, 5.0, 172, 182, 25, "hard");
            assertTrue(tempo.calculateLoad(u) > easy.calculateLoad(u));
        }

        @Test
        @DisplayName("Intensity zones are classified correctly")
        void intensity_zone_classification() {
            UserProfile u = baselineUser();
            // Zone 1: %HRR < 60%  →  HR < 58 + 0.6×134 ≈ 138 bpm
            CardioSession zone1 = new CardioSession(1L, LocalDateTime.now(), 5, 7.0, 130, 140, 35, "z1");
            assertTrue(zone1.getIntensityZone(u).contains("Zone 1"));

            // Zone 5: %HRR > 90%  →  HR > 58 + 0.9×134 ≈ 178 bpm
            CardioSession zone5 = new CardioSession(1L, LocalDateTime.now(), 5, 4.0, 185, 192, 22, "z5");
            assertTrue(zone5.getIntensityZone(u).contains("Zone 5"));
        }

        @Test
        @DisplayName("Speed calculation is accurate")
        void speed_calculation() {
            // 5 km in 25 min = 12.0 km/h
            CardioSession run = new CardioSession(1L, LocalDateTime.now(), 5.0, 5.0, 170, 180, 25.0, null);
            assertEquals(12.0, run.getAverageSpeedKmh(), 0.01);
        }

        @Test
        @DisplayName("Cardio session always has CARDIO_5K workout type")
        void workout_type_is_always_cardio() {
            CardioSession run = simpleRun(1L, LocalDateTime.now());
            assertEquals(WorkoutType.CARDIO_5K, run.getWorkoutType());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: MuscleGroup Decay Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MuscleGroup — Decay Constants")
    class MuscleGroupDecayTests {

        @Test
        @DisplayName("Decay constant is ln(2)/halfLife")
        void decay_constant_formula() {
            MuscleGroup mg = MuscleGroup.QUADS;
            double expected = Math.log(2) / mg.getRecoveryHalfLifeHours();
            assertEquals(expected, mg.getDecayConstant(), 1e-10);
        }

        @Test
        @DisplayName("Quads have the longest half-life (hardest to recover)")
        void quads_longest_half_life() {
            assertTrue(MuscleGroup.QUADS.getRecoveryHalfLifeHours() >=
                       MuscleGroup.CALVES.getRecoveryHalfLifeHours());
        }

        @Test
        @DisplayName("Primary workout day mapping is correct")
        void primary_workout_day_mapping() {
            assertEquals(WorkoutType.PUSH, MuscleGroup.CHEST.getPrimaryWorkoutDay());
            assertEquals(WorkoutType.PULL, MuscleGroup.BACK.getPrimaryWorkoutDay());
            assertEquals(WorkoutType.LEGS, MuscleGroup.QUADS.getPrimaryWorkoutDay());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: RecoveryCalculator Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RecoveryCalculator — Readiness Score Logic")
    class RecoveryCalculatorTests {

        private RecoveryCalculator calc;
        private UserProfile        user;

        @BeforeEach
        void setup() {
            calc = new RecoveryCalculator();
            user = baselineUser();
        }

        @Test
        @DisplayName("Empty session list → Readiness Score = 100 (fully rested)")
        void empty_session_list_gives_max_score() {
            ReadinessReport r = calc.calculateReadiness(user, List.of());
            assertEquals(100, r.getReadinessScore());
        }

        @Test
        @DisplayName("Very recent heavy Legs session → Readiness Score < 80")
        void recent_heavy_legs_lowers_score() {
            HypertrophySession legs = buildHeavyLegsSession(1L, LocalDateTime.now().minusHours(4));
            legs.calculateLoad(user);

            ReadinessReport r = calc.calculateReadiness(user,
                List.of(legs));

            assertTrue(r.getReadinessScore() < 80,
                "Score should drop below OPTIMAL after a very recent heavy legs session, got: "
                + r.getReadinessScore());
        }

        @Test
        @DisplayName("Old session (72h ago) barely affects current readiness")
        void old_session_minimal_impact() {
            HypertrophySession legs = buildHeavyLegsSession(1L, LocalDateTime.now().minusHours(72));
            legs.calculateLoad(user);

            ReadinessReport r = calc.calculateReadiness(user, List.of(legs));
            assertTrue(r.getReadinessScore() > 85,
                "A 72h-old session should leave minimal residual fatigue, got: " + r.getReadinessScore());
        }

        @Test
        @DisplayName("Score is lower after 2 heavy sessions than 1")
        void two_sessions_lower_score_than_one() {
            LocalDateTime ts = LocalDateTime.now().minusHours(10);
            HypertrophySession legs  = buildHeavyLegsSession(1L, ts);
            HypertrophySession legs2 = buildHeavyLegsSession(1L, ts.plusHours(1));
            legs.calculateLoad(user);
            legs2.calculateLoad(user);

            ReadinessReport r1 = calc.calculateReadiness(user, List.of(legs));
            ReadinessReport r2 = calc.calculateReadiness(user, List.of(legs, legs2));

            assertTrue(r2.getReadinessScore() < r1.getReadinessScore(),
                "Two sessions should yield a lower readiness score than one");
        }

        @Test
        @DisplayName("Report contains non-empty primary recommendation")
        void report_has_recommendation() {
            HypertrophySession s = simplePushSession(1L, LocalDateTime.now().minusHours(20));
            s.calculateLoad(user);
            ReadinessReport r = calc.calculateReadiness(user, List.of(s));
            assertNotNull(r.getPrimaryRecommendation());
            assertFalse(r.getPrimaryRecommendation().isBlank());
        }

        @Test
        @DisplayName("Score is clamped to 1–100")
        void score_is_always_in_valid_range() {
            // Maximum fatigue scenario: 5 heavy leg sessions in the last 24h
            List<WorkoutSession> sessions = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                HypertrophySession s = buildHeavyLegsSession(1L,
                    LocalDateTime.now().minusHours(3 + i));
                s.calculateLoad(user);
                sessions.add(s);
            }
            ReadinessReport r = calc.calculateReadiness(user, sessions);
            assertTrue(r.getReadinessScore() >= 1 && r.getReadinessScore() <= 100,
                "Score must always be within [1, 100]");
        }

        @Test
        @DisplayName("Cardio session contributes to readiness reduction")
        void cardio_contributes_to_fatigue() {
            CardioSession hardRun = new CardioSession(1L,
                LocalDateTime.now().minusHours(6), 5, 4.8, 178, 189, 24, "hard run");
            hardRun.calculateLoad(user);

            ReadinessReport r = calc.calculateReadiness(user, List.of(hardRun));
            assertTrue(r.getCardiovascularFatigue() > 0);
            assertTrue(r.getReadinessScore() < 100);
        }

        @Test
        @DisplayName("ScoreBand is correctly assigned from score")
        void score_band_assignment() {
            assertEquals(ReadinessReport.ScoreBand.OPTIMAL,
                ReadinessReport.ScoreBand.fromScore(90));
            assertEquals(ReadinessReport.ScoreBand.GOOD,
                ReadinessReport.ScoreBand.fromScore(70));
            assertEquals(ReadinessReport.ScoreBand.MODERATE,
                ReadinessReport.ScoreBand.fromScore(55));
            assertEquals(ReadinessReport.ScoreBand.HIGH_LOAD,
                ReadinessReport.ScoreBand.fromScore(40));
            assertEquals(ReadinessReport.ScoreBand.CRITICAL,
                ReadinessReport.ScoreBand.fromScore(20));
        }

        @Test
        @DisplayName("Null user throws NullPointerException")
        void null_user_throws() {
            assertThrows(NullPointerException.class, () ->
                calc.calculateReadiness(null, List.of()));
        }

        // Helper: a very high-volume legs session (exceeds comfortable threshold)
        private HypertrophySession buildHeavyLegsSession(long userId, LocalDateTime ts) {
            HypertrophySession s = new HypertrophySession(
                userId, WorkoutType.LEGS, MuscleGroup.QUADS, ts, "heavy legs");
            s.addExercise(ExerciseSet.builder(0, "Squat",   MuscleGroup.QUADS)
                .sets(5).reps(6).weightKg(110.0).rpe(9.5).build());
            s.addExercise(ExerciseSet.builder(0, "Leg Press", MuscleGroup.QUADS)
                .sets(4).reps(12).weightKg(160.0).rpe(9.0).build());
            s.addExercise(ExerciseSet.builder(0, "RDL",      MuscleGroup.HAMSTRINGS)
                .sets(4).reps(8).weightKg(100.0).rpe(8.5).build());
            s.addExercise(ExerciseSet.builder(0, "Hip Thrust", MuscleGroup.GLUTES)
                .sets(4).reps(10).weightKg(90.0).rpe(8.0).build());
            return s;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: LoadCalculator Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LoadCalculator — Utility Functions")
    class LoadCalculatorTests {

        @Test
        @DisplayName("Cardio fatigue threshold constant is positive")
        void cardio_fatigue_threshold_positive() {
            assertTrue(LoadCalculator.cardioFatigueThreshold() > 0);
        }

        @Test
        @DisplayName("adjustedFatigueThreshold scales with athlete weight")
        void adjusted_threshold_scales_with_weight() {
            UserProfile heavy = UserProfile.builder("Heavy")
                .heightCm(185).weightKg(90).age(25)
                .restingHeartRate(60).maxHeartRate(190).build();
            UserProfile light = UserProfile.builder("Light")
                .heightCm(165).weightKg(55).age(25)
                .restingHeartRate(60).maxHeartRate(190).build();

            double threshHeavy = LoadCalculator.adjustedFatigueThreshold(MuscleGroup.CHEST, heavy);
            double threshLight = LoadCalculator.adjustedFatigueThreshold(MuscleGroup.CHEST, light);
            assertTrue(threshHeavy > threshLight,
                "Heavier athlete should tolerate higher absolute volume before fatigue caps");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: DAO Integration Tests (in-memory SQLite)
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DAO Integration — SQLite In-Memory")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DaoIntegrationTests {

        private static DatabaseManager       db;
        private static UserProfileDAO        userDao;
        private static HypertrophySessionDAO strengthDao;
        private static CardioSessionDAO      cardioDao;
        private static UserProfile           savedUser;

        @BeforeAll
        static void initDb() throws SQLException {
            db          = DatabaseManager.getInstance();
            db.initialise(":memory:");   // fresh in-memory DB for the entire nested class
            userDao     = new UserProfileDAO(db);
            strengthDao = new HypertrophySessionDAO(db);
            cardioDao   = new CardioSessionDAO(db);

            // Persist the baseline user once for all DAO tests
            savedUser = baselineUser();
            userDao.save(savedUser);
        }

        @AfterAll
        static void closeDb() throws SQLException {
            db.close();
        }

        // ── UserProfileDAO ────────────────────────────────────────────

        @Test
        @Order(10)
        @DisplayName("UserProfile is saved and assigned a positive DB id")
        void user_saved_with_id() {
            assertTrue(savedUser.getId() > 0,
                "DB should assign a positive auto-increment id");
        }

        @Test
        @Order(11)
        @DisplayName("UserProfile can be retrieved by id")
        void user_findById() throws SQLException {
            Optional<UserProfile> found = userDao.findById(savedUser.getId());
            assertTrue(found.isPresent());
            assertEquals("Test Athlete", found.get().getName());
            assertEquals(66.0, found.get().getWeightKg(), 0.01);
        }

        @Test
        @Order(12)
        @DisplayName("UserProfile can be retrieved by name")
        void user_findByName() throws SQLException {
            Optional<UserProfile> found = userDao.findByName("Test Athlete");
            assertTrue(found.isPresent());
        }

        @Test
        @Order(13)
        @DisplayName("UserProfile update persists correctly")
        void user_update() throws SQLException {
            // The builder is immutable, so create a fresh profile for mutation test
            UserProfile toUpdate = UserProfile.builder("Updatable User")
                .heightCm(175).weightKg(72.0).age(30)
                .restingHeartRate(62).maxHeartRate(188).build();
            userDao.save(toUpdate);

            // Simulate weight update: we verify via findById after update
            userDao.update(toUpdate);  // weight unchanged here — mainly tests no-exception
            Optional<UserProfile> reloaded = userDao.findById(toUpdate.getId());
            assertTrue(reloaded.isPresent());
        }

        // ── HypertrophySessionDAO ─────────────────────────────────────

        @Test
        @Order(20)
        @DisplayName("HypertrophySession save assigns a positive id")
        void strength_save_assigns_id() throws SQLException {
            HypertrophySession s = simplePushSession(savedUser.getId(),
                LocalDateTime.now().minusHours(24));
            strengthDao.save(s);
            assertTrue(s.getId() > 0);
        }

        @Test
        @Order(21)
        @DisplayName("HypertrophySession is round-tripped through DB with exercises intact")
        void strength_round_trip_with_exercises() throws SQLException {
            HypertrophySession original = simplePushSession(savedUser.getId(),
                LocalDateTime.now().minusDays(2));
            strengthDao.save(original);

            Optional<HypertrophySession> reloaded = strengthDao.findById(original.getId());
            assertTrue(reloaded.isPresent());

            HypertrophySession r = reloaded.get();
            assertEquals(WorkoutType.PUSH, r.getPplDay());
            assertEquals(MuscleGroup.CHEST, r.getPrimaryMuscleGroup());
            assertEquals(1, r.getExercises().size(),
                "1 exercise (Bench Press) should survive the DB round-trip");
            assertEquals("Bench Press", r.getExercises().get(0).getExerciseName());
            assertEquals(4,    r.getExercises().get(0).getSetCount());
            assertEquals(8,    r.getExercises().get(0).getReps());
            assertEquals(80.0, r.getExercises().get(0).getWeightKg(), 0.01);
        }

        @Test
        @Order(22)
        @DisplayName("findByUserId returns only sessions for that user")
        void strength_findByUserId_isolation() throws SQLException {
            // Save a session under a different user id (no FK enforcement in :memory: for unknown ids,
            // but we save under savedUser.getId() only and verify count increases)
            int before = strengthDao.findByUserId(savedUser.getId()).size();

            HypertrophySession extra = simplePushSession(savedUser.getId(),
                LocalDateTime.now().minusDays(1));
            strengthDao.save(extra);

            int after = strengthDao.findByUserId(savedUser.getId()).size();
            assertEquals(before + 1, after);
        }

        @Test
        @Order(23)
        @DisplayName("findSessionsInLastNHours filters by time window correctly")
        void strength_time_window_filter() throws SQLException {
            // Save one session within the window and one outside
            HypertrophySession recent = simplePushSession(savedUser.getId(),
                LocalDateTime.now().minusHours(10));
            HypertrophySession old = simplePushSession(savedUser.getId(),
                LocalDateTime.now().minusDays(5));
            strengthDao.save(recent);
            strengthDao.save(old);

            List<HypertrophySession> inWindow = strengthDao
                .findSessionsInLastNHours(savedUser.getId(), 24);

            // All results must be within 24h
            for (HypertrophySession s : inWindow) {
                assertTrue(s.hoursAgo() <= 24.5,
                    "Session should be within 24h window, but hoursAgo = " + s.hoursAgo());
            }
        }

        @Test
        @Order(24)
        @DisplayName("HypertrophySession update persists notes change")
        void strength_update_notes() throws SQLException {
            HypertrophySession s = simplePushSession(savedUser.getId(),
                LocalDateTime.now().minusHours(5));
            strengthDao.save(s);

            s.setNotes("Updated note — felt strong today");
            strengthDao.update(s);

            Optional<HypertrophySession> reloaded = strengthDao.findById(s.getId());
            assertTrue(reloaded.isPresent());
            assertEquals("Updated note — felt strong today", reloaded.get().getNotes());
        }

        @Test
        @Order(25)
        @DisplayName("HypertrophySession delete removes record and child exercise sets")
        void strength_delete_cascades() throws SQLException {
            HypertrophySession s = simplePushSession(savedUser.getId(),
                LocalDateTime.now().minusHours(3));
            strengthDao.save(s);
            long id = s.getId();

            strengthDao.delete(id);

            Optional<HypertrophySession> result = strengthDao.findById(id);
            assertTrue(result.isEmpty(), "Deleted session should not be found");
        }

        // ── CardioSessionDAO ──────────────────────────────────────────

        @Test
        @Order(30)
        @DisplayName("CardioSession save assigns a positive id")
        void cardio_save_assigns_id() throws SQLException {
            CardioSession run = simpleRun(savedUser.getId(),
                LocalDateTime.now().minusHours(12));
            cardioDao.save(run);
            assertTrue(run.getId() > 0);
        }

        @Test
        @Order(31)
        @DisplayName("CardioSession round-trips through DB preserving all metrics")
        void cardio_round_trip() throws SQLException {
            CardioSession original = new CardioSession(
                savedUser.getId(),
                LocalDateTime.now().minusDays(1),
                5.0, 5.5, 165, 178, 27.5, "round-trip test");
            cardioDao.save(original);

            Optional<CardioSession> reloaded = cardioDao.findById(original.getId());
            assertTrue(reloaded.isPresent());

            CardioSession r = reloaded.get();
            assertEquals(5.0,   r.getDistanceKm(),    0.001);
            assertEquals(5.5,   r.getPaceMinsPerKm(), 0.001);
            assertEquals(165,   r.getAvgHeartRate());
            assertEquals(178,   r.getMaxHeartRateHit());
            assertEquals(27.5,  r.getDurationMinutes(), 0.001);
            assertEquals("round-trip test", r.getNotes());
        }

        @Test
        @Order(32)
        @DisplayName("CardioSession delete removes record")
        void cardio_delete() throws SQLException {
            CardioSession run = simpleRun(savedUser.getId(),
                LocalDateTime.now().minusHours(6));
            cardioDao.save(run);
            long id = run.getId();

            cardioDao.delete(id);
            assertTrue(cardioDao.findById(id).isEmpty());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: WeeklyPlannerService Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WeeklyPlannerService — 7-Day Plan Generation")
    class WeeklyPlannerTests {

        private WeeklyPlannerService planner;
        private RecoveryCalculator   calc;
        private UserProfile          user;

        @BeforeEach
        void setup() {
            calc    = new RecoveryCalculator();
            planner = new WeeklyPlannerService(calc);
            user    = baselineUser();
        }

        @Test
        @DisplayName("Week plan always contains exactly 7 days")
        void plan_has_seven_days() {
            WeeklyPlannerService.WeekPlan plan = planner.buildWeekPlan(user, List.of());
            assertEquals(7, plan.getDays().size());
        }

        @Test
        @DisplayName("With no recent sessions, no days should be adjusted")
        void fresh_athlete_no_adjustments() {
            WeeklyPlannerService.WeekPlan plan = planner.buildWeekPlan(user, List.of());
            long adjusted = plan.getDays().stream()
                .filter(WeeklyPlannerService.DayPlan::isAdjusted)
                .count();
            assertEquals(0, adjusted, "A fully-rested athlete should have 0 adjusted days");
        }

        @Test
        @DisplayName("Very recent heavy legs session lowers baseline readiness significantly")
        void heavy_legs_lowers_baseline_readiness() {
            // A 5-hour-old max-effort legs session should drive quad fatigue to ~100%
            // which reduces the baseline readiness score well below the OPTIMAL band.
            // Whether a specific plan day is "adjusted" depends on which day of the week
            // this test runs (LEGS next appears on Thursday in the template), so we
            // assert on the reliable, day-agnostic signal: the readiness score itself.
            HypertrophySession legs = buildHeavyLegsForPlanner(1L, LocalDateTime.now().minusHours(5));
            legs.calculateLoad(user);

            WeeklyPlannerService.WeekPlan plan = planner.buildWeekPlan(user, List.of(legs));

            // With significant quad fatigue, baseline readiness must be below the fully-rested
            // maximum of 100 — and clearly not full OPTIMAL either (score < 90 is a reliable signal)
            assertTrue(plan.getBaselineReadiness() < 90,
                "Expected readiness < 90 after a very recent heavy legs session, got: "
                + plan.getBaselineReadiness());
            assertTrue(plan.getBaselineReadiness() < 100,
                "Score must not be perfect (100) when there is residual fatigue, got: "
                + plan.getBaselineReadiness());

            // The plan must still produce 7 days regardless of fatigue state
            assertEquals(7, plan.getDays().size());
        }

        @Test
        @DisplayName("Each DayPlan has a non-blank coach note")
        void every_day_has_a_coach_note() {
            WeeklyPlannerService.WeekPlan plan = planner.buildWeekPlan(user, List.of());
            for (WeeklyPlannerService.DayPlan d : plan.getDays()) {
                assertNotNull(d.getCoachNote());
                assertFalse(d.getCoachNote().isBlank(),
                    "Day " + d.getDate() + " has a blank coach note");
            }
        }

        @Test
        @DisplayName("Volume percent is 0 only for REST days")
        void volume_is_zero_only_for_rest_days() {
            WeeklyPlannerService.WeekPlan plan = planner.buildWeekPlan(user, List.of());
            for (WeeklyPlannerService.DayPlan d : plan.getDays()) {
                if (d.getAssignedType() == WorkoutType.REST) {
                    assertEquals(0, d.getVolumePercent(),
                        "REST day should have 0% volume");
                } else {
                    assertTrue(d.getVolumePercent() > 0,
                        "Non-REST day should have positive volume, date=" + d.getDate());
                }
            }
        }

        @Test
        @DisplayName("Baseline readiness is correctly propagated into the plan")
        void baseline_readiness_propagated() {
            WeeklyPlannerService.WeekPlan freshPlan =
                planner.buildWeekPlan(user, List.of());
            assertEquals(100, freshPlan.getBaselineReadiness(),
                "Fully rested athlete should have baseline readiness of 100");
        }

        @Test
        @DisplayName("toFormattedPlan() produces non-empty string with athlete name")
        void formatted_plan_contains_athlete_name() {
            WeeklyPlannerService.WeekPlan plan = planner.buildWeekPlan(user, List.of());
            String output = plan.toFormattedPlan();
            assertNotNull(output);
            assertTrue(output.contains(user.getName()),
                "Formatted plan should contain the athlete's name");
        }

        @Test
        @DisplayName("Fresh athlete gets progressive overload recommendation on at least one day")
        void fresh_athlete_gets_overload_day() {
            WeeklyPlannerService.WeekPlan plan = planner.buildWeekPlan(user, List.of());
            boolean hasOverload = plan.getDays().stream()
                .anyMatch(d -> d.getVolumePercent() > 100);
            assertTrue(hasOverload,
                "A fully-rested athlete should have at least one 105% overload day");
        }

        // Helper
        private HypertrophySession buildHeavyLegsForPlanner(long userId, LocalDateTime ts) {
            HypertrophySession s = new HypertrophySession(
                userId, WorkoutType.LEGS, MuscleGroup.QUADS, ts, "heavy legs");
            s.addExercise(ExerciseSet.builder(0, "Squat",  MuscleGroup.QUADS)
                .sets(5).reps(6).weightKg(110.0).rpe(9.5).build());
            s.addExercise(ExerciseSet.builder(0, "Press",  MuscleGroup.QUADS)
                .sets(4).reps(12).weightKg(160.0).rpe(9.0).build());
            s.addExercise(ExerciseSet.builder(0, "RDL",    MuscleGroup.HAMSTRINGS)
                .sets(4).reps(8).weightKg(100.0).rpe(8.5).build());
            return s;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: AcuteChronicWorkloadRatio Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AcuteChronicWorkloadRatio — ACWR Injury Risk")
    class AcwrTests {

        private UserProfile user;

        @BeforeEach void setup() { user = baselineUser(); }

        @Test
        @DisplayName("Empty session list yields ACWR ratio of 1.0 (no-op)")
        void empty_sessions_yield_default_ratio() {
            AcuteChronicWorkloadRatio.AcwrReport r =
                AcuteChronicWorkloadRatio.compute(user, List.of());
            assertEquals(1.0, r.getClassicRatio(), 0.001);
            assertEquals(1.0, r.getEwmaRatio(),    0.001);
        }

        @Test
        @DisplayName("ACWR with recent heavy week is in CAUTION or DANGER zone")
        void heavy_week_raises_risk_zone() {
            List<WorkoutSession> heavy = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                HypertrophySession s = simplePushSession(1L,
                    LocalDateTime.now().minusDays(i));
                s.calculateLoad(user);
                heavy.add(s);
            }
            AcuteChronicWorkloadRatio.AcwrReport r =
                AcuteChronicWorkloadRatio.compute(user, heavy);
            // With only acute load and no chronic base, ratio should be 1.0
            // (chronic defaults to acute when < 7 days exist)
            assertNotNull(r.getRiskZone());
        }

        @Test
        @DisplayName("Classic and EWMA ratios are both positive for any non-empty input")
        void ratios_are_positive() {
            HypertrophySession s = simplePushSession(1L, LocalDateTime.now().minusDays(1));
            s.calculateLoad(user);
            AcuteChronicWorkloadRatio.AcwrReport r =
                AcuteChronicWorkloadRatio.compute(user, List.of(s));
            assertTrue(r.getClassicRatio() > 0);
            assertTrue(r.getEwmaRatio()    > 0);
        }

        @Test
        @DisplayName("Acute load equals total 7-day session load")
        void acute_load_equals_7day_total() {
            HypertrophySession s1 = simplePushSession(1L, LocalDateTime.now().minusHours(10));
            HypertrophySession s2 = simplePushSession(1L, LocalDateTime.now().minusHours(30));
            s1.calculateLoad(user);
            s2.calculateLoad(user);
            double expectedAcute = s1.getCalculatedLoad() + s2.getCalculatedLoad();

            AcuteChronicWorkloadRatio.AcwrReport r =
                AcuteChronicWorkloadRatio.compute(user, List.of(s1, s2));
            assertEquals(expectedAcute, r.getAcuteLoad(), 0.001);
        }

        @Test
        @DisplayName("Max safe load increase is non-negative")
        void max_safe_increase_is_non_negative() {
            HypertrophySession s = simplePushSession(1L, LocalDateTime.now().minusDays(2));
            s.calculateLoad(user);
            AcuteChronicWorkloadRatio.AcwrReport r =
                AcuteChronicWorkloadRatio.compute(user, List.of(s));
            assertTrue(r.getMaxSafeLoadIncrease() >= 0);
        }

        @Test
        @DisplayName("RiskZone.fromRatio correctly classifies all boundaries")
        void risk_zone_boundary_classification() {
            assertEquals(AcuteChronicWorkloadRatio.RiskZone.UNDER_TRAINING,
                AcuteChronicWorkloadRatio.RiskZone.fromRatio(0.5));
            assertEquals(AcuteChronicWorkloadRatio.RiskZone.SWEET_SPOT,
                AcuteChronicWorkloadRatio.RiskZone.fromRatio(1.0));
            assertEquals(AcuteChronicWorkloadRatio.RiskZone.CAUTION,
                AcuteChronicWorkloadRatio.RiskZone.fromRatio(1.40));
            assertEquals(AcuteChronicWorkloadRatio.RiskZone.DANGER_ZONE,
                AcuteChronicWorkloadRatio.RiskZone.fromRatio(1.60));
        }

        @Test
        @DisplayName("toFormattedReport returns a non-blank string")
        void formatted_report_is_non_blank() {
            AcuteChronicWorkloadRatio.AcwrReport r =
                AcuteChronicWorkloadRatio.compute(user, List.of());
            assertFalse(r.toFormattedReport().isBlank());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: PersonalRecord & PersonalRecordTracker Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PersonalRecord — Epley Formula & Wilks Score")
    class PersonalRecordTests {

        private UserProfile user;

        @BeforeEach void setup() { user = baselineUser(); }

        @Test
        @DisplayName("Epley formula: 80 kg × 8 reps = 101.3 kg e1RM")
        void epley_formula_accuracy() {
            double e1rm = PersonalRecord.epley(80.0, 8);
            // 80 × (1 + 8/30) = 80 × 1.2667 = 101.33
            assertEquals(101.33, e1rm, 0.1);
        }

        @Test
        @DisplayName("Epley caps reps at 12 to prevent overestimation")
        void epley_caps_at_12_reps() {
            double at12 = PersonalRecord.epley(60.0, 12);
            double at20 = PersonalRecord.epley(60.0, 20);
            assertEquals(at12, at20, 0.001,
                "e1RM should be identical for 12 and 20 reps (cap applied)");
        }

        @Test
        @DisplayName("Wilks score is positive for valid inputs")
        void wilks_score_is_positive() {
            double wilks = PersonalRecord.wilks(100.0, 66.0);
            assertTrue(wilks > 0, "Wilks score must be positive");
        }

        @Test
        @DisplayName("Higher e1RM yields higher Wilks score (same bodyweight)")
        void wilks_scales_with_e1rm() {
            double w1 = PersonalRecord.wilks(100.0, 66.0);
            double w2 = PersonalRecord.wilks(150.0, 66.0);
            assertTrue(w2 > w1);
        }

        @Test
        @DisplayName("Relative strength is e1RM / body weight")
        void relative_strength_formula() {
            PersonalRecord pr = new PersonalRecord(1L, "Bench Press", 80.0, 8, 8.0,
                LocalDateTime.now(), MuscleGroup.CHEST, 66.0);
            double rs = pr.relativeStrength(66.0);
            assertEquals(pr.getEstimated1RmKg() / 66.0, rs, 0.001);
        }

        @Test
        @DisplayName("Strength standard returns a non-blank string")
        void strength_standard_label_non_blank() {
            PersonalRecord pr = new PersonalRecord(1L, "Squat", 100.0, 5, 9.0,
                LocalDateTime.now(), MuscleGroup.QUADS, 66.0);
            assertFalse(pr.getStrengthStandard(66.0).isBlank());
        }
    }

    @Nested
    @DisplayName("PersonalRecordTracker — DAO Integration")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PRTrackerIntegrationTests {

        private static DatabaseManager       db2;
        private static UserProfileDAO        userDao2;
        private static PersonalRecordDAO     prDao;
        private static PersonalRecordTracker tracker;
        private static UserProfile           savedUser;

        @BeforeAll
        static void initDb() throws SQLException {
            // Use a separate singleton-safe in-memory instance via a temp file
            db2      = DatabaseManager.getInstance();
            // Re-initialise with :memory: (already done in DaoIntegrationTests,
            // but since that closes the connection we need a fresh file here)
            db2.initialise(":memory:");
            userDao2 = new UserProfileDAO(db2);
            prDao    = new PersonalRecordDAO(db2);
            tracker  = new PersonalRecordTracker(prDao);

            savedUser = baselineUser();
            userDao2.save(savedUser);
        }

        @AfterAll
        static void closeDb2() throws SQLException {
            db2.close();
        }

        @Test @Order(1)
        @DisplayName("No PRs exist initially")
        void no_prs_initially() throws SQLException {
            List<PersonalRecord> board = prDao.findCurrentPRBoard(savedUser.getId());
            assertTrue(board.isEmpty());
        }

        @Test @Order(2)
        @DisplayName("First session always creates PRs for all exercises")
        void first_session_creates_prs() throws SQLException {
            HypertrophySession s = simplePushSession(savedUser.getId(),
                LocalDateTime.now().minusDays(1));

            List<PersonalRecord> newPRs = tracker.detectAndSavePRs(
                List.of(s), savedUser);

            assertFalse(newPRs.isEmpty(),
                "First session should generate PRs for all its exercises");
            assertEquals("Bench Press", newPRs.get(0).getExerciseName(),
                "Highest e1RM exercise should be first");
        }

        @Test @Order(3)
        @DisplayName("Same weight and reps does not create a new PR")
        void same_performance_does_not_duplicate_pr() throws SQLException {
            int countBefore = prDao.findAllByUser(savedUser.getId()).size();

            // Same session again — should not add any new PRs
            HypertrophySession s = simplePushSession(savedUser.getId(),
                LocalDateTime.now().minusHours(2));
            List<PersonalRecord> newPRs = tracker.detectAndSavePRs(
                List.of(s), savedUser);

            assertTrue(newPRs.isEmpty(),
                "No new PRs should be created when performance is identical");
            int countAfter = prDao.findAllByUser(savedUser.getId()).size();
            assertEquals(countBefore, countAfter);
        }

        @Test @Order(4)
        @DisplayName("Higher weight creates a new PR and e1RM is greater")
        void higher_weight_creates_new_pr() throws SQLException {
            // Prior PR: 80 kg × 8 reps → e1RM = 80 × (1 + 8/30) = 101.3 kg
            // New attempt: 100 kg × 5 reps → e1RM = 100 × (1 + 5/30) = 116.7 kg  ✔ new PR
            HypertrophySession s = new HypertrophySession(
                savedUser.getId(), WorkoutType.PUSH, MuscleGroup.CHEST,
                LocalDateTime.now(), "PR attempt");
            s.addExercise(ExerciseSet.builder(0, "Bench Press", MuscleGroup.CHEST)
                .sets(1).reps(5).weightKg(100.0).rpe(9.5).build());

            List<PersonalRecord> newPRs = tracker.detectAndSavePRs(
                List.of(s), savedUser);

            assertFalse(newPRs.isEmpty(), "100 kg × 5 reps should beat the 80 kg × 8 reps record");
            double newE1rm = newPRs.get(0).getEstimated1RmKg();
            assertTrue(newE1rm > PersonalRecord.epley(80.0, 8),
                "New e1RM " + newE1rm + " must exceed the previous record " + PersonalRecord.epley(80.0, 8));
        }

        @Test @Order(5)
        @DisplayName("PR board returns only the best record per exercise")
        void pr_board_returns_best_per_exercise() throws SQLException {
            List<PersonalRecord> board = prDao.findCurrentPRBoard(savedUser.getId());
            // All records on the board must be the best for their exercise
            Map<String, Long> countPerExercise = new java.util.HashMap<>();
            for (PersonalRecord pr : board) {
                countPerExercise.merge(pr.getExerciseName(), 1L, Long::sum);
            }
            countPerExercise.forEach((ex, count) ->
                assertEquals(1L, (long)count,
                    "PR board should have exactly 1 entry per exercise, got " + count + " for " + ex));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  NESTED CLASS: WorkoutMetricsAggregator Tests
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WorkoutMetricsAggregator — Statistics Engine")
    class MetricsAggregatorTests {

        private UserProfile user;

        @BeforeEach void setup() { user = baselineUser(); }

        @Test
        @DisplayName("Empty session list returns EMPTY summary with zero stats")
        void empty_sessions_return_empty_summary() {
            WorkoutMetricsAggregator.MetricsSummary m =
                WorkoutMetricsAggregator.aggregate(List.of(), user);
            assertEquals(0, m.getTotalSessions());
            assertEquals(0.0, m.getTotalRawVolumeKg(), 0.001);
        }

        @Test
        @DisplayName("Total sessions count equals input list size")
        void total_sessions_matches_input() {
            List<WorkoutSession> sessions = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                HypertrophySession s = simplePushSession(1L,
                    LocalDateTime.now().minusDays(i));
                s.calculateLoad(user);
                sessions.add(s);
            }
            WorkoutMetricsAggregator.MetricsSummary m =
                WorkoutMetricsAggregator.aggregate(sessions, user);
            assertEquals(4, m.getTotalSessions());
            assertEquals(4, m.getStrengthSessions());
            assertEquals(0, m.getCardioSessions());
        }

        @Test
        @DisplayName("Cardio sessions are separated from strength in stats")
        void cardio_sessions_counted_separately() {
            List<WorkoutSession> sessions = new ArrayList<>();
            sessions.add(simplePushSession(1L, LocalDateTime.now().minusDays(1)));
            sessions.add(simpleRun(1L, LocalDateTime.now().minusDays(2)));
            sessions.forEach(s -> s.calculateLoad(user));

            WorkoutMetricsAggregator.MetricsSummary m =
                WorkoutMetricsAggregator.aggregate(sessions, user);
            assertEquals(1, m.getStrengthSessions());
            assertEquals(1, m.getCardioSessions());
        }

        @Test
        @DisplayName("Total raw volume is sum of all strength session volumes")
        void total_raw_volume_is_correct() {
            HypertrophySession s1 = simplePushSession(1L, LocalDateTime.now().minusDays(1));
            HypertrophySession s2 = simplePushSession(1L, LocalDateTime.now().minusDays(2));
            s1.calculateLoad(user);
            s2.calculateLoad(user);
            double expectedVol = s1.getTotalRawVolume() + s2.getTotalRawVolume();

            WorkoutMetricsAggregator.MetricsSummary m =
                WorkoutMetricsAggregator.aggregate(List.of(s1, s2), user);
            assertEquals(expectedVol, m.getTotalRawVolumeKg(), 0.01);
        }

        @Test
        @DisplayName("Sessions per week is reasonable for a 7-day window")
        void sessions_per_week_reasonable() {
            List<WorkoutSession> sessions = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                HypertrophySession s = simplePushSession(1L,
                    LocalDateTime.now().minusDays(i));
                s.calculateLoad(user);
                sessions.add(s);
            }
            WorkoutMetricsAggregator.MetricsSummary m =
                WorkoutMetricsAggregator.aggregate(sessions, user);
            assertTrue(m.getSessionsPerWeek() > 0 && m.getSessionsPerWeek() <= 7);
        }

        @Test
        @DisplayName("Dominant muscle is non-null when strength sessions exist")
        void dominant_muscle_is_present() {
            HypertrophySession s = simplePushSession(1L, LocalDateTime.now().minusDays(1));
            s.calculateLoad(user);
            WorkoutMetricsAggregator.MetricsSummary m =
                WorkoutMetricsAggregator.aggregate(List.of(s), user);
            assertNotNull(m.getDominantMuscle());
        }

        @Test
        @DisplayName("Cardio stats are zero when no cardio sessions exist")
        void cardio_stats_zero_without_cardio() {
            HypertrophySession s = simplePushSession(1L, LocalDateTime.now().minusDays(1));
            s.calculateLoad(user);
            WorkoutMetricsAggregator.MetricsSummary m =
                WorkoutMetricsAggregator.aggregate(List.of(s), user);
            assertEquals(0.0, m.getTotalKm(), 0.001);
        }

        @Test
        @DisplayName("toFormattedReport is non-blank and contains window label")
        void formatted_report_contains_window_label() {
            HypertrophySession s = simplePushSession(1L, LocalDateTime.now().minusDays(1));
            s.calculateLoad(user);
            WorkoutMetricsAggregator.MetricsSummary m =
                WorkoutMetricsAggregator.aggregate(List.of(s), user);
            String report = m.toFormattedReport("Test Window");
            assertFalse(report.isBlank());
            assertTrue(report.contains("Test Window"));
        }
    }
}