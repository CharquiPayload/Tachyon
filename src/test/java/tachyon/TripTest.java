package tachyon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tachyon.path.Route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trip judged as a whole: when it is there, when three legs no closer are being stuck,
 * how long legs that find no way are searched again, and what it says at the end. And the
 * stuck spots a bot's searches leave out: for how long, how many.
 */
class TripTest {

    @Test
    @DisplayName("there within 1.6 blocks flat and 2 up or down; nowhere before it knows where it ends")
    void arrived() {
        Trip t = new Trip(new BlockPos(10, 64, 10));
        assertFalse(t.arrived(new Vec3(10.5, 64, 10.5)), "the first leg has not said where it ends yet");
        t.destination = new Route.Point(10, 64, 10);
        assertTrue(t.arrived(new Vec3(11.9, 64, 10.5)));
        assertFalse(t.arrived(new Vec3(12.2, 64, 10.5)));
        assertTrue(t.arrived(new Vec3(10.5, 62, 10.5)));
        assertFalse(t.arrived(new Vec3(10.5, 61.5, 10.5)));
    }

    @Test
    @DisplayName("three legs in a row that end no closer by a block are being stuck")
    void threeLegsNoCloser() {
        Trip t = new Trip(new BlockPos(100, 64, 0));
        t.destination = new Route.Point(100, 64, 0);
        assertFalse(t.stalled(new Vec3(0.5, 64, 0.5)), "the first leg's end is the best yet");
        assertFalse(t.stalled(new Vec3(40.5, 64, 0.5)), "40 closer");
        assertFalse(t.stalled(new Vec3(40.9, 64, 0.5)), "no closer, once");
        assertFalse(t.stalled(new Vec3(40.2, 70, 0.5)), "twice");
        assertTrue(t.stalled(new Vec3(41.2, 64, 0.5)), "three times: under a block closer each");
        Trip up = new Trip(new BlockPos(0, 100, 0));
        up.destination = new Route.Point(0, 100, 0);
        up.stalled(new Vec3(0.5, 64, 0.5));
        assertFalse(up.stalled(new Vec3(0.5, 80, 0.5)) || up.stalled(new Vec3(0.5, 90, 0.5))
                || up.stalled(new Vec3(0.5, 96, 0.5)), "climbing toward a high place is getting closer");
    }

    @Test
    @DisplayName("no way on: searched again every second for 5 s, then over; never waited for on the first leg")
    void noWayOn() {
        Trip t = new Trip(new BlockPos(0, 64, 0));
        assertFalse(t.waitFor(100), "the first leg: nothing to wait for");
        t.found();
        assertTrue(t.waitFor(200));
        assertEquals(220, t.retryAt);
        assertTrue(t.waitFor(280));
        assertFalse(t.waitFor(300), "5 s since the first leg that found nothing");
        t.found();
        assertTrue(t.waitFor(400), "a leg found a way meanwhile: 5 s again from now");
    }

    @Test
    @DisplayName("its words: where it ended, how far, and what it placed and dug on the way")
    void words() {
        Trip t = new Trip(new BlockPos(10, 64, 10));
        t.destination = new Route.Point(10, 64, 10);
        t.found();
        assertEquals("arrived at 10, 64, 10 (0.0 from it)", t.arrivedWords(new Vec3(10.5, 64, 10.5)));
        t.found();
        t.placed = 3;
        t.dug = 1;
        assertEquals("arrived at 10, 64, 10 (1.0 from it); 2 legs, 3 blocks placed, 1 dug",
                t.arrivedWords(new Vec3(11.5, 64, 10.5)));
        t.why = "the way is blocked by oak_log and I have no permission to break it";
        assertEquals("stuck at 0 64 10, 10 blocks from 10 64 10: the way is blocked by oak_log and I have no"
                + " permission to break it; 2 legs, 3 blocks placed, 1 dug", t.shortWords(new Vec3(0.5, 64, 10.5), "stuck"));
        Trip beside = new Trip(new BlockPos(5, 64, 5));
        beside.destination = new Route.Point(6, 64, 5);
        beside.beside = true;
        assertEquals("arrived beside 5, 64, 5, at 6, 64, 5 (0.0 from it)", beside.arrivedWords(new Vec3(6.5, 64, 5.5)));
        Trip landed = new Trip(new BlockPos(5, 70, 5));
        landed.destination = new Route.Point(5, 64, 5);
        assertEquals("arrived at 5, 64, 5, the ground under 5, 70, 5 (0.0 from it)", landed.arrivedWords(new Vec3(5.5, 64, 5.5)));
    }

    @Test
    @DisplayName("stuck spots: 90 s each, 32 at most, one dimension's")
    void stuckSpots() {
        StuckSpots s = new StuckSpots();
        assertTrue(s.now(Level.OVERWORLD, 0).isEmpty());
        s.mark(Level.OVERWORLD, 1, 64, 1, 100);
        assertEquals(1, s.now(Level.OVERWORLD, 100 + StuckSpots.LASTS_TICKS - 1).size());
        assertTrue(s.now(Level.NETHER, 101).isEmpty(), "the overworld's, not the nether's");
        assertTrue(s.now(Level.OVERWORLD, 100 + StuckSpots.LASTS_TICKS).isEmpty(), "90 s later, free again");
        for (int i = 0; i < 40; i++) s.mark(Level.OVERWORLD, i, 64, 0, 200);
        assertEquals(StuckSpots.MAX, s.now(Level.OVERWORLD, 200).size());
        assertFalse(s.now(Level.OVERWORLD, 200).contains(BlockPos.asLong(0, 64, 0)), "the oldest went first");
        s.mark(Level.NETHER, 0, 64, 0, 300);
        assertEquals(1, s.now(Level.NETHER, 300).size(), "in another dimension, the old ones are dropped");
    }
}
