package tachyon;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a hunt, a kill or an attack goes after: one or more kinds of mob (the nearest of
 * any), or one player by name.
 *
 * <p>Never prey, by kind: a player, a tamed mob (someone's pet, a horse someone tamed) and
 * a named one (a name tag says someone cares for it). A player is prey only by name, only
 * for a kill or an attack (never a hunt), and only while the bot's {@code hunt_players}
 * is on ({@link Fighting}); never one in creative or spectator, whom nothing hurts, nor the
 * bot itself. A mob or player that stops being prey mid-chase (tamed, named, the setting
 * turned off) is let go of.
 *
 * @param kinds  the kinds of mob (empty when it is a player)
 * @param player the player's name, or null
 * @param words  what it is after, in words: "cow", "cow, pig", "Steve"
 */
record Prey(Set<EntityType<?>> kinds, String player, String words) {

    /** Whether {@code e} is this prey for the bot, now. On the server's thread. */
    boolean matches(Bots.Bot p, LivingEntity e) {
        if (!e.isAlive() || e == p.body) return false;
        if (e instanceof Player pl) {
            return player != null && pl.getGameProfile().getName().equalsIgnoreCase(player)
                    && !pl.isCreative() && !pl.isSpectator() && Settings.bool(p, Fighting.HUNT_PLAYERS);
        }
        return kinds.contains(e.getType()) && !spared(e);
    }

    /** A mob nobody takes by its kind: a tamed one, or one with a name. */
    static boolean spared(Entity e) {
        return e.hasCustomName() || e instanceof OwnableEntity o && o.getOwnerUUID() != null;
    }

    /** What some words came to: the prey, or why they are none (in words for whoever wrote them). */
    record Read(Prey prey, String why) {
    }

    /**
     * The prey some words name: kinds of mob as Minecraft names them ({@code cow},
     * {@code minecraft:cow}, {@code cow,pig,chicken}, a plural as a model may write it,
     * {@code cows}), or a player in the game, by name.
     *
     * @param players whether a player may be named (a kill or an attack); the caller asks
     *                the bot's {@code hunt_players} before it gives one
     */
    static Read read(String words, MinecraftServer server, boolean players) {
        if (words == null || words.isBlank()) return new Read(null, "no mob was named: which one? (cow, zombie...)");
        Set<EntityType<?>> kinds = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (String part : words.split(",")) {
            String w = part.trim();
            if (w.isEmpty()) continue;
            EntityType<?> kind = kind(w);
            if (kind == EntityType.PLAYER) {
                return new Read(null, "players are prey only by their name, and only when the operators allow it (hunt_players)");
            }
            if (kind != null) {
                kinds.add(kind);
                names.add(BuiltInRegistries.ENTITY_TYPE.getKey(kind).getPath());
                continue;
            }
            ServerPlayer pl = server.getPlayerList().getPlayerByName(w);
            if (pl != null) {
                if (!players) return new Read(null, w + " is a player: players are never hunted");
                if (words.contains(",")) return new Read(null, "one player at a time, and no mobs with them");
                return new Read(new Prey(Set.of(), pl.getGameProfile().getName(), pl.getGameProfile().getName()), null);
            }
            return new Read(null, "there is no mob called " + w + " (and no player of that name in the game);"
                    + " a mob with a name tag is never prey");
        }
        if (kinds.isEmpty()) return new Read(null, "no mob was named: which one? (cow, zombie...)");
        return new Read(new Prey(Set.copyOf(kinds), null, String.join(", ", names)), null);
    }

    /** The kind of mob a word names, as Minecraft names it (a plural too), or null. */
    private static EntityType<?> kind(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace(' ', '_');
        EntityType<?> t = type(w);
        if (t == null && w.endsWith("s")) t = type(w.substring(0, w.length() - 1));
        return t;
    }

    private static EntityType<?> type(String w) {
        ResourceLocation id = ResourceLocation.tryParse(w.contains(":") ? w : "minecraft:" + w);
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    /** A prey of one kind, as a command names it. */
    static Prey of(EntityType<?> kind) {
        return new Prey(Set.of(kind), null, BuiltInRegistries.ENTITY_TYPE.getKey(kind).getPath());
    }
}
