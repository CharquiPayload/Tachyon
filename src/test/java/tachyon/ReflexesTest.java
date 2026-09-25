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
 * which creepers it leaves alone, and a distance in words. (Fighting, running and digging
 * need a world: the end-to-end tests have them.)
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
        assertFalse(known.scared(a, 0));
        assertFalse(known.scared(b, 10), "another creeper's scares are its own");
        assertFalse(known.scared(a, 600));
        assertFalse(known.leftAlone(a, 700));
        assertTrue(known.scared(a, 1200), "three within 90 s (1800 ticks)");
        assertTrue(known.leftAlone(a, 1200 + Creepers.SCARED_TICKS - 1));
        assertFalse(known.leftAlone(a, 1200 + Creepers.SCARED_TICKS), "for 90 s, not more");
        assertFalse(known.leftAlone(b, 1200));
        assertFalse(known.scared(a, 5000), "the count started again");

        Creepers.Known spread = new Creepers.Known();
        UUID c = UUID.randomUUID();
        for (long t = 0; t < 20_000; t += Creepers.SCARES_WINDOW / 2 + 1) {
            assertFalse(spread.scared(c, t), "two within 90 s at most, at " + t);
        }
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
