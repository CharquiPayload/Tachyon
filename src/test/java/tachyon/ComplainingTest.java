package tachyon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The count of a player's hits: three in a row, each within 20 s of the last. Plain ticks. */
class ComplainingTest {

    private static final UUID STEVE = UUID.nameUUIDFromBytes("Steve".getBytes());
    private static final UUID ALEX = UUID.nameUUIDFromBytes("Alex".getBytes());

    @Test
    @DisplayName("three hits in a row from one player are worth telling, with how long they took and the health lost")
    void threeInARow() {
        Complaining.Hits h = new Complaining.Hits();
        assertNull(h.hit(STEVE, 100, 2));
        assertNull(h.hit(STEVE, 150, 2.5f));
        Complaining.Hits.Complaint c = h.hit(STEVE, 220, 3);
        assertNotNull(c);
        assertEquals(3, c.hits());
        assertEquals(120, c.ticks());
        assertEquals(7.5f, c.lost());
        assertEquals("Steve hit it 3 times in 6 s (7.5 health lost); it has not hit back", Complaining.words("Steve", c));
        // The count starts again: three more for the next.
        assertNull(h.hit(STEVE, 240, 1));
        assertNull(h.hit(STEVE, 260, 1));
        c = h.hit(STEVE, 280, 1);
        assertNotNull(c);
        assertEquals(40, c.ticks(), "from the first hit of the new run");
        assertEquals("Steve hit it 3 times in 2 s (3 health lost); it has not hit back", Complaining.words("Steve", c));
    }

    @Test
    @DisplayName("hits more than 20 s apart are not in a row; another player's hit starts a run of its own")
    void notInARow() {
        Complaining.Hits h = new Complaining.Hits();
        assertNull(h.hit(STEVE, 0, 1));
        assertNull(h.hit(STEVE, 400, 1), "20 s after: still in a row");
        assertNull(h.hit(STEVE, 801, 1), "more than 20 s after the last: a new run, its first");
        assertNull(h.hit(ALEX, 810, 1), "Alex: a run of Alex's");
        assertNull(h.hit(STEVE, 820, 1), "Steve again: his run starts again");
        assertNull(h.hit(STEVE, 830, 1));
        assertNotNull(h.hit(STEVE, 840, 1));
        assertEquals(3, Complaining.Hits.HITS);
        assertEquals(400, Complaining.Hits.WINDOW_TICKS);
    }

    @Test
    @DisplayName("hits that take next to nothing are still counted, and said as at least a second")
    void tinyHits() {
        Complaining.Hits h = new Complaining.Hits();
        h.hit(STEVE, 0, 0.1f);
        h.hit(STEVE, 1, 0.1f);
        Complaining.Hits.Complaint c = h.hit(STEVE, 2, 0.1f);
        assertEquals("Steve hit it 3 times in 1 s (0.3 health lost); it has not hit back", Complaining.words("Steve", c));
    }
}
