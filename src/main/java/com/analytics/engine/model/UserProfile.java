package com.analytics.engine.model;

/**
 * Represents the physiological baseline of an athlete.
 *
 * <p>All load calculations in the engine are <em>relative</em> — normalised
 * against this profile so that the same Bench Press volume is correctly
 * weighted as harder work for a lighter / less-experienced athlete.
 *
 * <p>The spec baseline is: 6 ft (≈182.88 cm), 66 kg.
 *
 * <h3>Immutability & Builder</h3>
 * <p>UserProfile is constructed via a fluent Builder.  Fields are final to
 * prevent accidental mutation after construction.  An {@code id} is mutable
 * only by the DAO layer (set after the INSERT round-trip).
 */
public final class UserProfile {

    // ----------------------------------------------------------------- Fields

    private long   id;                        // set by DAO after DB insert
    private final String name;
    private final double heightCm;            // 182.88 ≈ 6 ft
    private final double weightKg;            // 66.0 kg
    private final int    age;
    private final int    restingHeartRate;     // bpm — used for HRR calculation
    private final int    maxHeartRate;         // bpm — typically 220 - age (or measured)
    private final double trainingExperienceYears;

    // ----------------------------------------------------------------- Constructor (via Builder)

    private UserProfile(Builder b) {
        this.name                    = b.name;
        this.heightCm                = b.heightCm;
        this.weightKg                = b.weightKg;
        this.age                     = b.age;
        this.restingHeartRate        = b.restingHeartRate;
        this.maxHeartRate            = b.maxHeartRate;
        this.trainingExperienceYears = b.trainingExperienceYears;
    }

    // ----------------------------------------------------------------- Derived Metrics

    /**
     * Heart Rate Reserve = Max HR − Resting HR.
     * Used in the Karvonen / TRIMP cardiovascular load formula.
     */
    public double getHeartRateReserve() {
        return maxHeartRate - restingHeartRate;
    }

    /**
     * Body Mass Index (kg/m²).
     */
    public double getBMI() {
        double hm = heightCm / 100.0;
        return weightKg / (hm * hm);
    }

    /**
     * Volume Tolerance Factor — scales upward with training experience.
     * Range: 1.0 (novice) → 1.5 (≥5 years).
     * Used to adjust the fatigue threshold so experienced athletes can
     * sustain higher volumes before their readiness score drops.
     */
    public double getVolumeToleranceFactor() {
        return 1.0 + Math.min(trainingExperienceYears, 5.0) * 0.10;
    }

    /**
     * Relative strength baseline coefficient — higher body weight means
     * the same absolute bar load is relatively lighter.
     * Normalised so that the 66 kg reference body returns 1.0.
     */
    public double getRelativeLoadCoefficient() {
        return 66.0 / weightKg;
    }

    // ----------------------------------------------------------------- Getters

    public long   getId()                       { return id; }
    public String getName()                     { return name; }
    public double getHeightCm()                 { return heightCm; }
    public double getWeightKg()                 { return weightKg; }
    public int    getAge()                      { return age; }
    public int    getRestingHeartRate()         { return restingHeartRate; }
    public int    getMaxHeartRate()             { return maxHeartRate; }
    public double getTrainingExperienceYears()  { return trainingExperienceYears; }

    /** Called exclusively by the DAO after persisting the record. */
    public void setId(long id) { this.id = id; }

    // ----------------------------------------------------------------- toString

    @Override
    public String toString() {
        return String.format(
            "UserProfile{name='%s', height=%.1f cm, weight=%.1f kg, age=%d, " +
            "RHR=%d bpm, MaxHR=%d bpm, experience=%.1f yr}",
            name, heightCm, weightKg, age,
            restingHeartRate, maxHeartRate, trainingExperienceYears
        );
    }

    // ================================================================= Builder

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private double heightCm                = 182.88;  // default: 6 ft
        private double weightKg                = 66.0;
        private int    age                     = 25;
        private int    restingHeartRate        = 60;
        private int    maxHeartRate            = 190;
        private double trainingExperienceYears = 2.0;

        private Builder(String name) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Name required");
            this.name = name;
        }

        public Builder heightCm(double cm)             { this.heightCm = cm; return this; }
        public Builder weightKg(double kg)             { this.weightKg = kg; return this; }
        public Builder age(int age)                    { this.age = age; return this; }
        public Builder restingHeartRate(int rhr)       { this.restingHeartRate = rhr; return this; }
        public Builder maxHeartRate(int mhr)           { this.maxHeartRate = mhr; return this; }
        public Builder trainingExperienceYears(double y){ this.trainingExperienceYears = y; return this; }

        public UserProfile build() {
            validate();
            return new UserProfile(this);
        }

        private void validate() {
            if (heightCm <= 0)           throw new IllegalArgumentException("Height must be > 0");
            if (weightKg <= 0)           throw new IllegalArgumentException("Weight must be > 0");
            if (age <= 0 || age > 120)   throw new IllegalArgumentException("Age out of range");
            if (restingHeartRate >= maxHeartRate)
                throw new IllegalArgumentException("Resting HR must be < Max HR");
        }
    }
}
