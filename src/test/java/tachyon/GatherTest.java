package tachyon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What gather refuses by its id, with no game: the ores, whose search all around would be the x-ray. */
class GatherTest {

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
}
