package tachyon;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A level's places of blocks players placed, as kept: bounded, the oldest forgotten first, a
 * place placed again the newest, and read back from what the level saves in the same order.
 * No level here: the places alone.
 */
class PlacedBlocksTest {

    private static List<Long> order(PlacedBlocks.Places p) {
        List<Long> out = new ArrayList<>();
        for (var it = p.iterator(); it.hasNext(); ) out.add(it.nextLong());
        return out;
    }

    @Test
    @DisplayName("a place is kept until its block is broken; placed again, it is the newest")
    void keptAndForgotten() {
        PlacedBlocks.Places p = new PlacedBlocks.Places();
        BlockPos a = new BlockPos(1, 64, 2), b = new BlockPos(-3, -60, 400), c = new BlockPos(30_000, 319, -30_000);
        p.add(a);
        p.add(b);
        p.add(c);
        assertTrue(p.has(a) && p.has(b) && p.has(c));
        assertTrue(p.isDirty(), "a change is saved with the level");
        p.add(a);
        assertEquals(List.of(b.asLong(), c.asLong(), a.asLong()), order(p), "placed again: the newest");
        p.remove(b);
        assertFalse(p.has(b));
        assertEquals(2, p.size());
    }

    @Test
    @DisplayName("past the most it keeps, the oldest places are forgotten")
    void bounded() {
        PlacedBlocks.Places p = new PlacedBlocks.Places();
        for (int i = 0; i < PlacedBlocks.MAX + 10; i++) p.add(new BlockPos(i, 64, 0));
        assertEquals(PlacedBlocks.MAX, p.size());
        for (int i = 0; i < 10; i++) assertFalse(p.has(new BlockPos(i, 64, 0)), "the oldest: " + i);
        assertTrue(p.has(new BlockPos(10, 64, 0)));
        assertTrue(p.has(new BlockPos(PlacedBlocks.MAX + 9, 64, 0)));
    }

    @Test
    @DisplayName("saved with the level and read back, oldest first, as they were")
    void savedAndRead() {
        PlacedBlocks.Places p = new PlacedBlocks.Places();
        for (int i = 0; i < 5; i++) p.add(new BlockPos(i * 7, 70 - i, -i));
        CompoundTag tag = p.save(new CompoundTag(), null);
        PlacedBlocks.Places back = PlacedBlocks.Places.load(tag, null);
        assertEquals(order(p), order(back));
        assertEquals(0, PlacedBlocks.Places.load(new CompoundTag(), null).size(), "nothing saved: none");
    }
}
