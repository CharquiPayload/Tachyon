package tachyon;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What gather decides with no game: the ores it refuses by id, whose search all around
 * would be the x-ray; the order it weighs what a sweep found in; and whose turn it is to
 * look when many gatherers want to at once.
 */
class GatherTest {

    @TempDir
    static Path dir;

    @Test
    @DisplayName("ores, ancient debris, budding amethyst and raw ore blocks are never gathered; the rest may be")
    void ores() {
        for (String ore : new String[]{"minecraft:iron_ore", "minecraft:deepslate_diamond_ore", "minecraft:nether_quartz_ore",
                "minecraft:ancient_debris", "minecraft:budding_amethyst", "minecraft:amethyst_cluster", "minecraft:raw_iron_block",
                "create:zinc_ore"}) {
            assertTrue(Gather.isOre(ore), ore);
        }
        for (String not : new String[]{"minecraft:dirt", "minecraft:oak_log", "minecraft:iron_block", "minecraft:raw_iron",
                "minecraft:stone", "minecraft:gravel", "minecraft:orange_wool"}) {
            assertFalse(Gather.isOre(not), not);
        }
    }

    @Test
    @DisplayName("what a sweep found is weighed nearest first, from where the body is")
    void nearestFirst() {
        LongArrayList found = new LongArrayList();
        found.add(BlockPos.asLong(10, 64, 0));
        found.add(BlockPos.asLong(1, 64, 0));
        found.add(BlockPos.asLong(-3, 60, 2));
        found.add(BlockPos.asLong(0, 64, 0));
        long[] keys = Gather.byDistance(found, new Vec3(0.5, 64.5, 0.5));
        List<Integer> order = new ArrayList<>();
        for (long k : keys) order.add((int) (k & ((1L << 20) - 1)));
        assertEquals(List.of(3, 1, 2, 0), order);
    }

    @Test
    @DisplayName("four look a tick, those that waited longest first: the last bots ticked get their turn too")
    void everyGathererGetsItsTurn() {
        List<Bots.Bot> bots = new ArrayList<>();
        for (int i = 0; i < 10; i++) bots.add(new Bots.Bot(null, "Look" + i, BotData.load(dir, "Look" + i)));
        // Every bot asks on every tick, in the server's order, until it has looked once.
        Set<Bots.Bot> waiting = new LinkedHashSet<>(bots);
        List<Bots.Bot> looked = new ArrayList<>();
        long tick = 1_000_000;
        for (int t = 0; t < 3; t++, tick++) {
            int thisTick = 0;
            for (Bots.Bot p : List.copyOf(waiting)) {
                if (Gather.mayLook(p, tick)) {
                    waiting.remove(p);
                    looked.add(p);
                    thisTick++;
                }
            }
            assertTrue(thisTick <= Gather.LOOKS_PER_TICK, "at most " + Gather.LOOKS_PER_TICK + " a tick: " + thisTick);
        }
        assertEquals(bots, looked, "all ten, in the order they asked, within three ticks");
        // Those that stop asking (their job over, or set aside) lose their places: a bot that
        // asks later does not wait behind them for ever.
        for (int i = 0; i < 4; i++) assertTrue(Gather.mayLook(bots.get(i), tick));
        for (int i = 4; i < 8; i++) assertFalse(Gather.mayLook(bots.get(i), tick), "the fifth and on this tick wait");
        tick += 3;
        assertTrue(Gather.mayLook(bots.get(8), tick), "the four that waited stopped asking: the ninth need not wait for them");
    }
}
