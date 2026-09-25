package tachyon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The count that keeps a bot that dies over and over from coming back for ever: plain times, in ms. */
class RespawningTest {

    private static final long MINUTE = 60_000;

    @Test
    @DisplayName("five deaths in five minutes it comes back from; the sixth it does not")
    void fiveThenLeaves() {
        Respawning.Deaths d = new Respawning.Deaths();
        for (int i = 0; i < 5; i++) assertTrue(d.comesBack(i * 2_000L), "death " + (i + 1));
        assertFalse(d.comesBack(10_000), "the sixth, within the five minutes");
        assertEquals(5, Respawning.Deaths.MAX);
        assertEquals(5 * MINUTE, Respawning.Deaths.WINDOW_MS);
    }

    @Test
    @DisplayName("a death older than five minutes no longer counts")
    void theWindowSlides() {
        Respawning.Deaths d = new Respawning.Deaths();
        long t0 = 1_000_000;
        assertTrue(d.comesBack(t0));
        for (int i = 1; i <= 4; i++) assertTrue(d.comesBack(t0 + i * MINUTE));
        // Five within the window (t0 .. t0+4min): one more before t0 is five minutes old is too many.
        assertFalse(d.comesBack(t0 + 5 * MINUTE - 1));
        // Once the first is five minutes old, there are four: it comes back, and that is five again.
        assertTrue(d.comesBack(t0 + 5 * MINUTE));
        assertFalse(d.comesBack(t0 + 5 * MINUTE + 1));
        // Deaths spread out, one every two minutes, never add up.
        Respawning.Deaths slow = new Respawning.Deaths();
        for (int i = 0; i < 50; i++) assertTrue(slow.comesBack(i * 2 * MINUTE), "death " + (i + 1));
    }

    @Test
    @DisplayName("a death it did not come back from is not counted")
    void refusedIsNotCounted() {
        Respawning.Deaths d = new Respawning.Deaths();
        for (int i = 0; i < 5; i++) assertTrue(d.comesBack(i));
        for (int i = 0; i < 3; i++) assertFalse(d.comesBack(100 + i));
        // Five minutes after the first five, all of them gone: five more.
        for (int i = 0; i < 5; i++) assertTrue(d.comesBack(5 * MINUTE + 10 + i));
    }

    @Test
    @DisplayName("what it dropped is lost after lava (or dying in it of the burning), the void (or below the world), drowning")
    void lost() {
        assertEquals("lava", Respawning.lost("minecraft:lava", false, false));
        assertEquals("lava", Respawning.lost("minecraft:on_fire", true, false), "burning, in lava");
        assertEquals("void", Respawning.lost("minecraft:out_of_world", false, true));
        assertEquals("void", Respawning.lost("minecraft:generic_kill", false, true), "killed below the world");
        assertEquals("drowning", Respawning.lost("minecraft:drown", false, false));
        for (String other : new String[]{"minecraft:mob_attack", "minecraft:fall", "minecraft:on_fire", "minecraft:hot_floor",
                "minecraft:player_attack", "minecraft:generic_kill", "unknown"}) {
            assertEquals(null, Respawning.lost(other, false, false), other);
        }
    }

    @Test
    @DisplayName("a dimension and a time ago, in words")
    void words() {
        assertEquals("the overworld", Respawning.dimension("minecraft:overworld"));
        assertEquals("the nether", Respawning.dimension("minecraft:the_nether"));
        assertEquals("the end", Respawning.dimension("minecraft:the_end"));
        assertEquals("deep dark", Respawning.dimension("somemod:deep_dark"));
        assertEquals("40 s", Respawning.ago(40_500));
        assertEquals("3 min", Respawning.ago(3 * MINUTE + 5_000));
        assertEquals("119 min", Respawning.ago(119 * MINUTE));
        assertEquals("5 h", Respawning.ago(5 * 60 * MINUTE));
        assertEquals("0 s", Respawning.ago(-10));
    }
}
