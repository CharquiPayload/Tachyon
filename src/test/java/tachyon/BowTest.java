package tachyon;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bow's numbers, without a game: an arrow flown as the game flies it, the pitch that
 * meets a point, and the shared count of arrows that did no harm.
 */
class BowTest {

    @AfterEach
    void forget() {
        Bow.Misses.forget();
    }

    @Test
    @DisplayName("a level shot drops, more the farther it goes, and flies about a tick per 3 blocks at first")
    void aLevelShotDrops() {
        double[] at10 = Bow.fly(0, 10), at25 = Bow.fly(0, 25);
        assertNotNull(at10);
        assertNotNull(at25);
        assertTrue(at10[0] < 0 && at25[0] < at10[0], "it falls: " + at10[0] + ", " + at25[0]);
        assertEquals(10 / 3.0, at10[1], 0.2, "3 blocks a tick, a little less with the air");
        assertTrue(at25[1] > 25 / 3.0, "slowed by the air");
    }

    @Test
    @DisplayName("the pitch found meets the point it was asked for, and flying it again says so")
    void thePitchMeetsThePoint() {
        for (double d : new double[]{5, 10, 17.5, 25, 40}) {
            for (double h : new double[]{-6, -1.2, 0, 1.5, 5}) {
                double[] shot = Bow.pitch(d, h);
                assertNotNull(shot, d + "/" + h);
                double[] flown = Bow.fly(shot[0], d);
                assertNotNull(flown);
                assertEquals(h, flown[0], 0.05, "at " + d + " across and " + h + " up");
                assertEquals(flown[1], shot[1], 1e-9, "the ticks it flies");
            }
        }
    }

    @Test
    @DisplayName("a flat shot aims about as high as Masurium's d*d/340 said, and never lower than straight at it")
    void asMasuriumAimed() {
        for (double d : new double[]{10, 15, 20, 25}) {
            double[] shot = Bow.pitch(d, 0);
            double rise = Math.tan(Math.toRadians(shot[0])) * d;
            double masurium = d * d / 340;
            assertTrue(rise > 0, "above the target at " + d);
            assertEquals(masurium, rise, masurium * 0.35 + 0.05, "at " + d + ": " + rise + " against " + masurium);
        }
    }

    @Test
    @DisplayName("what no pitch reaches is out of a bow's range: none")
    void outOfRange() {
        assertNull(Bow.pitch(400, 0));
        assertNull(Bow.pitch(10, 150), "up a cliff higher than an arrow climbs");
    }

    @Test
    @DisplayName("three arrows that do no harm and a target is not worth another, for every shooter; a hit or its hurting a bot starts it again")
    void misses() {
        UUID creeper = UUID.randomUUID();
        assertTrue(Bow.Misses.worthIt(creeper));
        assertEquals(1, Bow.Misses.missed(creeper));
        assertEquals(2, Bow.Misses.missed(creeper));
        Bow.Misses.hit(creeper);
        assertEquals(1, Bow.Misses.missed(creeper), "a hit started it again");
        Bow.Misses.missed(creeper);
        Bow.Misses.missed(creeper);
        assertFalse(Bow.Misses.worthIt(creeper));
        Bow.Misses.hurtMe(creeper);
        assertTrue(Bow.Misses.worthIt(creeper), "it hurt a bot: it can be reached");
    }

    @Test
    @DisplayName("the last 1024 targets asked about are remembered: more than a horde aims at; one asked about stays")
    void bounded() {
        Bow.Misses.forget();
        UUID first = UUID.randomUUID(), kept = UUID.randomUUID();
        for (int i = 0; i < 3; i++) Bow.Misses.missed(first);
        for (int i = 0; i < 3; i++) Bow.Misses.missed(kept);
        for (int i = 0; i < 600; i++) Bow.Misses.missed(UUID.randomUUID());
        assertFalse(Bow.Misses.worthIt(first), "600 other targets, a crowd's worth, and its count holds");
        for (int i = 0; i < Bow.Misses.REMEMBERED; i++) {
            Bow.Misses.missed(UUID.randomUUID());
            if (i % 100 == 0) assertFalse(Bow.Misses.worthIt(kept), "asked about all along, it is kept");
        }
        assertEquals(Bow.Misses.REMEMBERED, Bow.Misses.remembered());
        assertTrue(Bow.Misses.worthIt(first), "the one asked about longest ago was forgotten");
        assertFalse(Bow.Misses.worthIt(kept));
    }
}
