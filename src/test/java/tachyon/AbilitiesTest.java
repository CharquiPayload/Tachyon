package tachyon;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the abilities plug into, without a game: their subcommands gathered under
 * {@code /tachyon}, and a bot's slots for their own state. (The mod's own abilities'
 * commands need the game's registries: the server's start is what checks them.)
 */
class AbilitiesTest {

    @TempDir
    Path dir;

    /** An ability with one subcommand, and nothing else. */
    private record Adds(String literal) implements Ability {
        @Override
        public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
            tachyon.then(LiteralArgumentBuilder.<CommandSourceStack>literal(literal).executes(c -> 1));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tachyon() {
        LiteralArgumentBuilder<CommandSourceStack> t = LiteralArgumentBuilder.literal("tachyon");
        t.then(LiteralArgumentBuilder.<CommandSourceStack>literal("spawn").executes(c -> 1));
        return t;
    }

    private static List<String> names(LiteralArgumentBuilder<CommandSourceStack> t) {
        return t.getArguments().stream().map(CommandNode::getName).toList();
    }

    @Test
    @DisplayName("every ability's subcommands come under /tachyon, in the abilities' order")
    void subcommandsInOrder() {
        LiteralArgumentBuilder<CommandSourceStack> t = tachyon();
        Abilities.commands(List.of(new Adds("farm"), new Adds("fish")), t, null, Set.of("owner"));
        assertEquals(List.of("spawn", "farm", "fish"), names(t));
    }

    @Test
    @DisplayName("a subcommand that is there already, the mod's or another ability's, is a mistake said at once")
    void aClashIsSaid() {
        assertThrows(IllegalStateException.class,
                () -> Abilities.commands(List.of(new Adds("farm"), new Adds("farm")), tachyon(), null, Set.of()));
        assertThrows(IllegalStateException.class,
                () -> Abilities.commands(List.of(new Adds("spawn")), tachyon(), null, Set.of()));
        assertThrows(IllegalStateException.class,
                () -> Abilities.commands(List.of(new Adds("owner")), tachyon(), null, Set.of("owner")));
    }

    /** A counter of an ability's own, per bot. */
    private static final class Count {
        int n;
    }

    @Test
    @DisplayName("an ability's slot on a bot is made once, and is the same after")
    void slots() {
        Bots.Bot p = new Bots.Bot(null, BotData.load(dir, "Ada"));
        int[] made = {0};
        Count c = p.slot(Count.class, () -> {
            made[0]++;
            return new Count();
        });
        c.n++;
        assertSame(c, p.slot(Count.class, Count::new));
        assertEquals(1, p.slot(Count.class, Count::new).n);
        assertEquals(1, made[0]);
        Bots.Bot other = new Bots.Bot(null, BotData.load(dir, "Bea"));
        assertEquals(0, other.slot(Count.class, Count::new).n, "each bot its own");
    }
}
