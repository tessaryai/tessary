// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.Survival.Curve;
import ai.tessary.endusers.judge.Survival.Observation;
import ai.tessary.endusers.judge.Survival.Plateau;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Kaplan-Meier curves turn an ID's silence into its nominal stop odds; the past check builds its calibration from
 * the same curves. A wrong step, plateau or lookup moves the stop odds of every silent ID.
 */
class SurvivalTest {

    private static Curve m3() {
        List<Observation> obs = new ArrayList<>();
        for (double t : new double[] {4, 1, 2, 3, 1, 2}) {
            obs.add(new Observation(t, true));
        }
        for (int i = 0; i < 4; i++) {
            obs.add(new Observation(6, false));
        }
        return Survival.km(obs);
    }

    @Test
    void km_stepsAtEachEventTime() {
        Curve c = m3();
        assertAll(
                () -> assertEquals(List.of(1.0, 2.0, 3.0, 4.0), c.times(), "a censored time makes no step"),
                () -> assertEquals(
                        List.of(0.8, 0.6000000000000001, 0.5000000000000001, 0.40000000000000013),
                        c.survival(),
                        "M3: 0.8, 0.6, 0.5, 0.4 in the reference operation order"),
                () -> assertEquals(List.of(10, 8, 6, 5), c.atRisk()));
    }

    @Test
    void surv_isTheStepAtOrBeforeX() {
        Curve c = m3();
        assertAll(
                () -> assertEquals(1.0, Survival.surv(c, 0.99), "before the first event"),
                () -> assertEquals(0.8, Survival.surv(c, 1.0), "the step counts at its own time"),
                () -> assertEquals(0.8, Survival.surv(c, 1.5)),
                () -> assertEquals(0.40000000000000013, Survival.surv(c, 6)));
    }

    @Test
    void plateau_isTheLastEventWithFiveAtRisk() {
        assertEquals(new Plateau(4, 0.40000000000000013), Survival.plateau(m3()), "M3: x_T = 4, S_inf = 0.4");
    }

    @Test
    void plateau_stopsAtTheFirstEventWhenFewAreAtRisk() {
        Curve c = Survival.km(List.of(new Observation(1, true), new Observation(2, true)));
        assertAll(
                () -> assertEquals(new Plateau(1, 0.5), Survival.plateau(c)),
                () -> assertEquals(0.5, Survival.pNominal(c, 0.5)));
    }

    @Test
    void pNominal_risesWithSilenceToOne() {
        Curve c = m3();
        assertAll(
                () -> assertEquals(0.667, Survival.pNominal(c, 2), 0.0005, "M4: 0.4 / 0.6"),
                () -> assertEquals(0.4, Survival.pNominal(c, 0.5), 1e-12, "M4: before any event"),
                () -> assertEquals(1.0, Survival.pNominal(c, 10), "M4: past the plateau"));
    }

    @Test
    void pNominal_withNoCurveIsZero() {
        assertEquals(0.0, Survival.pNominal(Survival.km(List.of(new Observation(3, false))), 5));
    }
}
