package com.hanchen.veritycustomapi.client;

public final class VerityHorrorDirectorTest {
    private VerityHorrorDirectorTest() {
    }

    public static void main(String[] args) {
        float distant = VerityHorrorDirector.pressureFor(92.0D, 0, 0, false, false, 1.0F);
        float stalking = VerityHorrorDirector.pressureFor(34.0D, 0, 1, false, true, 1.0F);
        float chase = VerityHorrorDirector.pressureFor(18.0D, 1, 1, true, false, 1.0F);
        float critical = VerityHorrorDirector.pressureFor(5.0D, 1, 1, true, true, 0.15F);
        require(distant >= 0.0F && distant < stalking, "stalking pressure did not exceed distant pressure");
        require(stalking < chase, "chase pressure did not exceed stalking pressure");
        require(chase <= critical && critical <= 1.0F, "critical pressure was not clamped");

        require(VerityHorrorDirector.stageFor(80.0D, 0, 0, false, false, 1.0F) == 1,
                "distant demon did not use the stalking stage");
        require(VerityHorrorDirector.stageFor(30.0D, 0, 1, false, true, 1.0F) == 2,
                "window hunt did not use the observed stage");
        require(VerityHorrorDirector.stageFor(20.0D, 1, 1, true, false, 1.0F) == 3,
                "active chase did not use the chase stage");
        require(VerityHorrorDirector.stageFor(6.0D, 1, 1, true, true, 0.2F) == 4,
                "close low-health chase did not use the critical stage");

        require(VerityHorrorDirector.ambientContextWeight(false, false, false, false, false) == 0,
                "calm daylight context had unexpected anomaly weight");
        require(VerityHorrorDirector.ambientContextWeight(true, true, true, true, true) == 11,
                "maximum eerie context weight was incorrect");
        double disabledChance = VerityHorrorDirector.ambientChance(11, 0.0D);
        double calmChance = VerityHorrorDirector.ambientChance(0, 1.0D);
        double eerieChance = VerityHorrorDirector.ambientChance(11, 1.0D);
        double cappedChance = VerityHorrorDirector.ambientChance(11, 3.0D);
        require(disabledChance == 0.0D, "zero anomaly frequency did not disable rolls");
        require(calmChance > 0.0D && calmChance < eerieChance, "environment did not increase anomaly chance");
        require(cappedChance <= 0.018D, "ambient anomaly roll exceeded its hard cap");
        System.out.println("Verity horror director pressure, stage, and ambient anomaly checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
