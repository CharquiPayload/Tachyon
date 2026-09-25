package tachyon;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the body lacks, looked at without anyone asking: between two words to it, a bot's
 * brain does not think, and hunger does not wait for someone to feel like talking. A
 * Masurium bot carried 64 steaks and was dying because nobody told it to eat; another sat
 * at hunger 5 with twelve porkchops in its backpack.
 *
 * <p><b>Eating</b>, every second, Masurium's three reasons: hunger below
 * {@link #STARVING 10}, whatever its health; health missing and hunger below
 * {@link #REGEN 18} (below 18 health does not come back by itself); and under half its
 * health with hunger below 20 (at 20 it heals four times as fast, and badly hurt that is
 * worth the food it wastes). What it eats is {@link Eating#eatBest}'s choice, never food on
 * its banned list or food that harms, brought up from the backpack if need be; a bite that
 * cannot start now is tried again in 5 s. Not in the middle of an emergency (running from
 * a creeper, backing off, coming up for air: whatever holds its body) nor of a fight,
 * unless it is starving: a steak in hand next to a creeper is the silliest way to die.
 *
 * <p><b>What it tells its owner</b> ({@link Notices}), once when it happens (looked at
 * every 2 s): hungry (hunger 8 or less) with nothing it may eat (and what it carries that
 * it does not eat on its own, and why), told again only once it has eaten and gone hungry
 * again; its bow out of arrows; a piece of armor it wears, or the tool in its hand, about
 * to break ({@link #SPENT 15%} of its uses left, when something can still be done). Each is
 * a paid call to its model, with {@code notices} brain: never the same news every ten
 * minutes. A full backpack is {@link Tossing}'s to tell.
 *
 * <p>Masurium's other needs wait for what they need: cooking raw food (no furnace yet),
 * and what to do when it has had nothing to do for 20 minutes (standing orders).
 */
final class Needs implements Ability {

    private static final Logger LOG = LogUtils.getLogger();

    /** Below this hunger it eats whatever its health: it does not starve. */
    static final int STARVING = 10;
    /** Below this, the game gives no health back by itself. */
    static final int REGEN = 18;
    /** Full: nothing more goes in. */
    static final int FULL = 20;
    /** At this hunger or less, with nothing to eat, its owner hears of it. */
    static final int HUNGRY = 8;
    /** A tool or a piece of armor with this share of its uses left is about to break. */
    static final double SPENT = 0.15;
    /** It looks at its hunger every this many ticks, at the rest every {@link #WATCH}; a bite not started is tried again after {@link #RETRY}. */
    static final int EVERY = 20, WATCH = 40, RETRY = 100;

    /** What it saw at its last look, to tell only what is new: while it is in the game (its slot). */
    private static final class Seen {
        long nextBite;
        boolean hadArrows;
        /** Its owner was told it is hungry with nothing to eat: not again until it is not. */
        boolean toldHungry;
        /** The worn piece, or the tool in hand, it told of, by where it is. */
        final Map<EquipmentSlot, ItemStack> worn = new EnumMap<>(EquipmentSlot.class);
    }

    /** The body parts looked at for wear: what it wears, and its hand. */
    private static final EquipmentSlot[] WORN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND};

    /**
     * Why it would eat now, in a few words, or null: "starving" (hunger under 10), "to heal"
     * (health missing, hunger under 18), "badly hurt" (under half its health, hunger under
     * 20). Plain numbers, for a test.
     */
    static String hungry(int food, float health, float max) {
        if (food < STARVING) return "starving";
        if (health < max && food < REGEN) return "to heal";
        if (health < max / 2 && food < FULL) return "badly hurt";
        return null;
    }

    /** Every second its hunger, every 2 s the rest: each bot on a tick of its own. */
    @Override
    public void tick(Bots.Bot p, long now) {
        int phase = Math.floorMod(now + p.name().hashCode(), WATCH);
        if (phase % EVERY != 0) return;
        BotPlayer b = p.body;
        int food = b.getFoodData().getFoodLevel();
        String why = hungry(food, b.getHealth(), b.getMaxHealth());
        if (why != null) eat(p, now, food, why);
        if (food > HUNGRY) p.slot(Seen.class, Seen::new).toldHungry = false;          // fed: the next time is news
        if (phase == 0) watch(p);
    }

    /** A bite, if nothing more pressing is going on; or its owner told it has nothing to eat. */
    private void eat(Bots.Bot p, long now, int food, String why) {
        if (Eating.eating(p)) return;
        boolean starving = food < STARVING;
        if (!starving && (Bots.holding(p) != null || Defending.fighting(p))) return;
        Seen seen = p.slot(Seen.class, Seen::new);
        if (now < seen.nextBite) return;
        seen.nextBite = now + RETRY;
        BotPlayer b = p.body;
        if (Eating.bestFood(p) < 0) {
            if (food <= HUNGRY && !seen.toldHungry) seen.toldHungry = Notices.say(p, "hunger", nothingToEat(p));
            return;
        }
        String no = Eating.eatBest(p, this);
        if (no == null) {
            Notices.technical(LOG, p, p.name() + " eats " + Gear.id(b.getMainHandItem()) + " by itself (" + why
                    + ": food " + food + "/20, health " + Retreating.health(b) + "/20)");
        }
    }

    /**
     * "is hungry (food 6/20, health 14/20) and carries nothing it eats on its own; it carries
     * golden_apple, on its banned list: it eats it if you ask it to by name".
     */
    private static String nothingToEat(Bots.Bot p) {
        BotPlayer b = p.body;
        Inventory inv = b.getInventory();
        List<String> banned = new ArrayList<>(), harmful = new ArrayList<>();
        for (int i = 0; i < Gear.SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getFoodProperties(b) == null) continue;
            String id = Gear.id(s);
            List<String> to = Eating.BANNED.has(p.data, BuiltInRegistries.ITEM.getKey(s.getItem()).toString()) ? banned : harmful;
            if (!to.contains(id)) to.add(id);
        }
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT,
                "is hungry (food %d/20, health %s/20) and carries nothing it eats on its own",
                b.getFoodData().getFoodLevel(), Retreating.health(b)));
        if (!banned.isEmpty()) {
            s.append("; it carries ").append(String.join(", ", banned))
                    .append(", on its banned list: it eats that if you ask it to by name");
        }
        if (!harmful.isEmpty()) {
            s.append(banned.isEmpty() ? "; it carries " : "; and ").append(String.join(", ", harmful))
                    .append(", which it does not eat on its own");
        }
        return s.toString();
    }

    /** Its bow out of arrows, its armor or tool about to break: told once, when it happens. */
    private static void watch(Bots.Bot p) {
        BotPlayer b = p.body;
        Seen seen = p.slot(Seen.class, Seen::new);
        boolean bow = false;
        Inventory inv = b.getInventory();
        for (int i = 0; i < Gear.SLOTS && !bow; i++) bow = inv.getItem(i).getItem() instanceof BowItem;
        boolean arrows = bow && Bow.slot(b) >= 0;
        if (bow && !arrows && seen.hadArrows) Notices.say(p, "arrows", "has run out of arrows for its bow");
        seen.hadArrows = arrows;
        for (EquipmentSlot part : WORN) {
            ItemStack s = b.getItemBySlot(part);
            if (!spent(s)) {
                seen.worn.remove(part);
                continue;
            }
            if (seen.worn.get(part) == s) continue;          // told already
            seen.worn.put(part, s);
            int left = s.getMaxDamage() - s.getDamageValue();
            Notices.say(p, "worn:" + Gear.id(s), "its " + Gear.id(s) + " (" + (part == EquipmentSlot.MAINHAND ? "in hand" : "worn")
                    + ") is about to break: " + left + (left == 1 ? " use" : " uses") + " left");
        }
    }

    /** Whether it is about to break: {@link #SPENT} of its uses left, or less. */
    static boolean spent(ItemStack s) {
        return !s.isEmpty() && s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= s.getMaxDamage() * SPENT;
    }
}
