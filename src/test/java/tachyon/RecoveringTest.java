package tachyon;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Going back for what it dropped, as plain data: the graves a death leaves, which of them
 * it goes to and why not, and the words of how it went. Ticks, positions and ids, no game.
 */
class RecoveringTest {

    private static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
    private static final long MINUTE = 1200;

    private static List<UUID> ids(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
    }

    @Test
    @DisplayName("a death that dropped something leaves a grave; one that dropped nothing anywhere else leaves none")
    void aGrave() {
        Recovering.Graves g = new Recovering.Graves();
        assertNull(g.died(OVERWORLD, new BlockPos(0, 64, 0), 100, List.of(), 0, null));
        assertNull(g.latest);
        Recovering.Grave a = g.died(OVERWORLD, new BlockPos(10, 64, 10), 200, ids(3), 70, null);
        assertNotNull(a);
        assertSame(a, g.latest);
        assertEquals(70, a.dropped);
        assertEquals(70, a.left());
        assertEquals(List.of(a), g.toVisit(OVERWORLD, 240));
        assertNull(a.skip(OVERWORLD, 240));
    }

    @Test
    @DisplayName("a death near a grave is the same grave: its things added, its tries kept; farther, a grave of its own")
    void sameGrave() {
        Recovering.Graves g = new Recovering.Graves();
        Recovering.Grave a = g.died(OVERWORLD, new BlockPos(10, 64, 10), 0, ids(2), 20, null);
        a.tries = 1;
        a.picked = 5;
        Recovering.Grave again = g.died(OVERWORLD, new BlockPos(18, 64, 15), 2 * MINUTE, ids(1), 7, null);
        assertSame(a, again, "within 12 blocks");
        assertEquals(27, a.dropped);
        assertEquals(22, a.left());
        assertEquals(1, a.tries, "its tries go on: dying there is the try that failed");
        assertEquals(2 * MINUTE, a.died, "what it dropped now vanishes from now");
        assertEquals(3, a.items.size());

        Recovering.Grave emptyHanded = g.died(OVERWORLD, new BlockPos(10, 65, 11), 3 * MINUTE, List.of(), 0, null);
        assertSame(a, emptyHanded, "a death there with nothing: still that grave, the latest");
        assertEquals(2 * MINUTE, a.died, "and what lies there vanishes when it did");

        Recovering.Grave b = g.died(OVERWORLD, new BlockPos(40, 64, 10), 3 * MINUTE, ids(1), 1, null);
        assertTrue(b != a, "30 blocks away: another grave");
        Recovering.Grave c = g.died(NETHER, new BlockPos(10, 64, 10), 3 * MINUTE, ids(1), 1, null);
        assertTrue(c != a, "the same place in another dimension: another grave");
        assertEquals(List.of(b, a), g.toVisit(OVERWORLD, 3 * MINUTE), "the latest first; the nether's is not for here");
    }

    @Test
    @DisplayName("never after lava, the void or drowning: said with the numbers; the place kills, so its grave is lost too")
    void lostGraves() {
        Recovering.Graves g = new Recovering.Graves();
        Recovering.Grave lava = g.died(OVERWORLD, new BlockPos(1, 12, 2), 0, ids(4), 230, "lava");
        assertEquals("died in lava at 1 12 2: the 230 items it had burned there; it is not going back",
                lava.skip(OVERWORLD, 50));
        assertTrue(g.toVisit(OVERWORLD, 50).isEmpty());
        Recovering.Grave v = g.died(OVERWORLD, new BlockPos(100, -70, 2), 0, ids(1), 5, "void");
        assertEquals("died in the void at 100 -70 2: the 5 items it had fell with it; it is not going back",
                v.skip(OVERWORLD, 50));
        Recovering.Grave w = g.died(OVERWORLD, new BlockPos(300, 50, 2), 0, ids(1), 9, "drowning");
        assertEquals("drowned at 300 50 2: the 9 items it had are under the water that drowned it; it is not going back",
                w.skip(OVERWORLD, 50));

        Recovering.Grave fine = g.died(OVERWORLD, new BlockPos(500, 64, 0), 0, ids(1), 9, null);
        assertEquals(List.of(fine), g.toVisit(OVERWORLD, 50));
        g.died(OVERWORLD, new BlockPos(503, 64, 0), 60, List.of(), 0, "drowning");
        assertEquals("drowning", fine.lost, "drowned where it went back to: the place kills");
        assertTrue(g.toVisit(OVERWORLD, 80).isEmpty());
    }

    @Test
    @DisplayName("two tries, within 6 minutes, in the dimension it is in; each refusal said with what is left and for how long")
    void theBrakes() {
        Recovering.Graves g = new Recovering.Graves();
        Recovering.Grave a = g.died(OVERWORLD, new BlockPos(10, 64, -3), 0, ids(3), 30, null);
        a.picked = 10;
        assertEquals("died in the overworld at 10 64 -3 and is back in the nether: it does not cross dimensions yet,"
                + " so the 20 items it dropped there are left, for about 5 more minutes", a.skip(NETHER, 0));
        a.tries = 2;
        assertEquals("went back to 10 64 -3 twice and died again: it does not try once more; 20 of the 30 items it"
                + " dropped there are left, for about 3 more minutes", a.skip(OVERWORLD, 2 * MINUTE));
        a.tries = 1;
        assertNull(a.skip(OVERWORLD, 6 * MINUTE), "6 minutes: still");
        assertEquals("died at 10 64 -3 6 minutes ago: the 20 items it dropped there have vanished by now; it is not"
                + " going back", a.skip(OVERWORLD, 6 * MINUTE + 1));
        assertEquals("for less than a minute more", a.leftFor(4 * MINUTE + 1));
        assertEquals("though they may have vanished by now", a.leftFor(5 * MINUTE));
        assertEquals("for about 1 more minute", a.leftFor(4 * MINUTE - 1));

        g.forget(6 * MINUTE + 1);
        assertTrue(g.list.isEmpty(), "too old: let go of");
        assertNull(g.latest);
    }

    @Test
    @DisplayName("what it picks up is counted for the grave it dropped it at; a grave with nothing left is not gone to")
    void pickedUp() {
        Recovering.Graves g = new Recovering.Graves();
        List<UUID> things = ids(2);
        Recovering.Grave a = g.died(OVERWORLD, new BlockPos(0, 64, 0), 0, things, 12, null);
        g.picked(UUID.randomUUID(), 64);
        assertEquals(0, a.picked, "not its own");
        g.picked(things.get(0), 8);
        g.picked(things.get(1), 4);
        assertEquals(12, a.picked);
        assertEquals(0, a.left());
        assertTrue(g.toVisit(OVERWORLD, 10).isEmpty());
        a.picked = 3;
        a.done = true;
        assertTrue(g.toVisit(OVERWORLD, 10).isEmpty(), "given up on: not again");
        g.died(OVERWORLD, new BlockPos(1, 64, 0), 20, ids(1), 5, null);
        assertFalse(a.done, "it died there with new things: those are worth the trip");
        assertEquals(List.of(a), g.toVisit(OVERWORLD, 30));
    }

    @Test
    @DisplayName("how a grave went, in words, with the numbers")
    void outcomes() {
        assertEquals("got back all 230 items it dropped when it died at 1 2 3", Recover.outcome("1 2 3", 230, 230, 0, false));
        assertEquals("got back all 230 items it dropped when it died at 1 2 3", Recover.outcome("1 2 3", 230, 240, 0, false),
                "what merged into its things from elsewhere is not more than it dropped");
        assertEquals("got back 212 of the 230 items it dropped when it died at 1 2 3; 18 were gone (vanished, or taken by"
                + " someone)", Recover.outcome("1 2 3", 230, 212, 0, false));
        assertEquals("got back 200 of the 230 items it dropped when it died at 1 2 3; 18 were gone (vanished, or taken by"
                + " someone); 12 it saw there and could not get to", Recover.outcome("1 2 3", 230, 200, 12, false));
        assertEquals("got back 200 of the 230 items it dropped when it died at 1 2 3; 30 it saw there and could not get"
                + " to or had no room for", Recover.outcome("1 2 3", 230, 200, 30, true));
        assertEquals("went back to 1 2 3, where it died: none of the 230 items it dropped was left (vanished, or taken by"
                + " someone)", Recover.outcome("1 2 3", 230, 0, 0, false));
        assertEquals("got back 0 of the 230 items it dropped when it died at 1 2 3; 5 were gone (vanished, or taken by"
                + " someone); 225 it saw there and could not get to", Recover.outcome("1 2 3", 230, 0, 225, false));
    }

    @Test
    @DisplayName("the setting: recover_items, true, basic, in the Life group")
    void theSetting() {
        Settings.Setting s = Abilities.settings().get(Recovering.RECOVER);
        assertTrue(s.isSwitch);
        assertEquals(1, s.byDefault);
        assertEquals("Life", s.group);
        assertEquals(Settings.Level.BASIC, s.level);
    }
}
