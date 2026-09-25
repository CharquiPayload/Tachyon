package tachyon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the reflexes decide from plain numbers, without a game: when a bot eats by itself,
 * which creepers it leaves alone, which attackers it does not go for, and a distance in
 * words. (Fighting, running and digging need a world: the end-to-end tests have them.)
 */
class ReflexesTest {

    @Test
    @DisplayName("it eats starving whatever its health; to heal below 18; badly hurt below 20; else not")
    void whenItEats() {
        assertEquals("starving", Needs.hungry(9, 20, 20));
        assertEquals("starving", Needs.hungry(0, 3, 20));
        assertNull(Needs.hungry(10, 20, 20), "hunger 10 with full health: nothing to eat for");
        assertEquals("to heal", Needs.hungry(17, 19, 20), "below 18 health does not come back by itself");
        assertNull(Needs.hungry(18, 19, 20), "at 18 it heals by itself");
        assertEquals("badly hurt", Needs.hungry(19, 9, 20), "under half its health, 20 heals fastest");
        assertNull(Needs.hungry(19, 10, 20), "half its health is not under half");
        assertNull(Needs.hungry(20, 2, 20), "a full bar takes nothing more");
        assertEquals("to heal", Needs.hungry(17, 29, 30), "the health it can have, not 20: a Health Boost counts");
    }

    @Test
    @DisplayName("the third scare by one creeper within 90 s leaves it alone for 90 s; spread out, never")
    void scaredTooOften() {
        Creepers.Known known = new Creepers.Known();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertFalse(known.scared(a, 0, 10, 10, false));
        assertFalse(known.scared(b, 10, 50, 50, false), "another creeper's scares are its own");
        assertFalse(known.scared(a, 600, 10.5, 10, false));
        assertFalse(known.leftAlone(a, 700));
        assertTrue(known.scared(a, 1200, 11, 10.5, false), "three within 90 s (1800 ticks), where it stood");
        assertTrue(known.leftAlone(a, 1200 + Creepers.SCARED_TICKS - 1));
        assertFalse(known.leftAlone(a, 1200 + Creepers.SCARED_TICKS), "for 90 s, not more");
        assertFalse(known.leftAlone(b, 1200));
        assertFalse(known.scared(a, 5000, 11, 10.5, false), "the count started again");

        Creepers.Known spread = new Creepers.Known();
        UUID c = UUID.randomUUID();
        for (long t = 0; t < 20_000; t += Creepers.SCARES_WINDOW / 2 + 1) {
            assertFalse(spread.scared(c, t, 0, 0, false), "two within 90 s at most, at " + t);
        }
    }

    @Test
    @DisplayName("a creeper coming at it, or one that moved since the last scare, is no scare too many: it is danger every time")
    void comingIsNeverTooOften() {
        Creepers.Known known = new Creepers.Known();
        UUID a = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            assertFalse(known.scared(a, i * 40L, 0, i * 2.0, true), "walking at it, scare " + (i + 1));
        }
        assertFalse(known.leftAlone(a, 400));

        Creepers.Known moving = new Creepers.Known();
        UUID b = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            assertFalse(moving.scared(b, i * 40L, i * Creepers.MOVED, 0, false), "3 blocks from the last scare's spot, scare " + (i + 1));
        }
        assertFalse(moving.leftAlone(b, 400));

        Creepers.Known after = new Creepers.Known();
        UUID c = UUID.randomUUID();
        assertFalse(after.scared(c, 0, 0, 0, false));
        assertFalse(after.scared(c, 40, 0, 0, false));
        assertFalse(after.scared(c, 80, 0, 0, true), "coming: the count starts again");
        assertFalse(after.scared(c, 120, 0, 0, false));
        assertFalse(after.scared(c, 160, 0, 0, false));
        assertTrue(after.scared(c, 200, 0, 0, false), "three after it stopped coming, staying where it stands");
    }

    @Test
    @DisplayName("an attacker it found no way to is not gone for again for 30 s, and forgotten after")
    void noWay() {
        Defending.NoWay noWay = new Defending.NoWay();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertFalse(noWay.has(a, 0));
        noWay.add(a, 100);
        assertTrue(noWay.has(a, 100));
        assertTrue(noWay.has(a, 100 + Defending.NO_WAY_TICKS - 1));
        assertFalse(noWay.has(a, 100 + Defending.NO_WAY_TICKS), "30 s, not more");
        assertFalse(noWay.has(b, 200), "another attacker is not it");
    }

    @Test
    @DisplayName("what it left alone, it forgets once the time is up: the maps stay small")
    void forgets() {
        Creepers.Known known = new Creepers.Known();
        UUID a = UUID.randomUUID();
        known.leaveAlone(a, 100);
        assertTrue(known.leftAlone(a, 99));
        known.forget(100);
        assertFalse(known.leftAlone(a, 50), "forgotten, not just over");
    }

    @Test
    @DisplayName("a distance in words: one block, and the rest in blocks, rounded")
    void distanceInWords() {
        assertEquals("1 block", Threats.blocks(0.6));
        assertEquals("1 block", Threats.blocks(1.4));
        assertEquals("0 blocks", Threats.blocks(0.2));
        assertEquals("12 blocks", Threats.blocks(11.5));
    }
}
