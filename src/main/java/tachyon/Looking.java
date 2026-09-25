package tachyon;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the brain can ask about the bot and what is around it: how it is, and who and
 * what is near. Read on the server's thread, where the world is.
 */
final class Looking implements Ability {

    @Override
    public void tools(Tools tools) {
        tools.add(new Tool("status", "Your health, food, what you are doing and what you carry.", List.of(), call -> {
            BotPlayer b = call.bot().body;
            return "health " + Math.round(b.getHealth()) + "/20, food " + b.getFoodData().getFoodLevel()
                    + "/20, doing: " + call.bot().doing + "; carrying: " + Brain.inventory(b);
        }).core());
        tools.add(new Tool("look_around", "Who and what is near you: players, mobs, things lying on the ground.", List.of(),
                call -> around(call.bot().body)).core());
    }

    /** Players within 64 (8 at most), mobs within 24 by kind, and how many things lie within 16. */
    private static String around(BotPlayer b) {
        StringBuilder s = new StringBuilder();
        List<String> players = new ArrayList<>();
        for (Player pl : b.level().players()) {
            if (pl == b || pl.distanceTo(b) > 64) continue;
            players.add(pl.getGameProfile().getName() + " " + Math.round(pl.distanceTo(b)) + " blocks "
                    + Brain.direction(b.getX(), b.getZ(), pl.getX(), pl.getZ()));
            if (players.size() >= 8) break;
        }
        s.append("players: ").append(players.isEmpty() ? "none within 64" : String.join(", ", players));
        Map<String, int[]> mobs = new LinkedHashMap<>();
        for (LivingEntity e : b.level().getEntitiesOfClass(LivingEntity.class, b.getBoundingBox().inflate(24),
                e -> !(e instanceof Player) && e.isAlive())) {
            String name = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            int[] c = mobs.computeIfAbsent(name, k -> new int[]{0, Integer.MAX_VALUE});
            c[0]++;
            c[1] = Math.min(c[1], Math.round(e.distanceTo(b)));
        }
        List<String> m = new ArrayList<>();
        mobs.forEach((k, v) -> m.add(k + " x" + v[0] + " (nearest " + v[1] + ")"));
        s.append("; mobs within 24: ").append(m.isEmpty() ? "none" : String.join(", ", m));
        int items = b.level().getEntitiesOfClass(ItemEntity.class, b.getBoundingBox().inflate(16)).size();
        s.append("; things lying on the ground within 16: ").append(items);
        return s.toString();
    }
}
