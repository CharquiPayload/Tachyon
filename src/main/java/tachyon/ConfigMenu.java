package tachyon;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * The config menu, {@code /tachyon config [<who>]}: a six-row chest that a vanilla client
 * draws as it draws any chest ({@code GENERIC_9x6}), so that players need nothing installed
 * for it, with the pages of {@link ConfigPages} drawn as items. What is on which page, and
 * what a click does, is decided there, in plain code; here it becomes items and sounds.
 *
 * <p><b>Nothing leaves it, enters it or is copied through it.</b> Its items are pictures.
 * Every click (a pick up, a shift click, a number key or F to swap with the hotbar or the
 * offhand, a middle click, Q, a drag, a double click) is read as what it means to the page,
 * and never handed to the chest's own handling ({@code AbstractContainerMenu.doClick}),
 * which is where items move: so nothing moves, in the chest, in the viewer's inventory in
 * the lower half, or on the cursor, which stays empty. A client that moved something in its
 * own picture of the chest as it clicked is told back what is so, as the server tells any
 * client whose click it did not follow (the packet's handler does it after {@link #clicked});
 * and the offhand, which is no slot of this menu, is sent again after a swap.
 *
 * <p>It closes by itself when the bot it shows leaves the game, or the viewer may no longer
 * configure it (the server asks {@link #stillValid} every tick, and before every click). What
 * it shows is drawn again once a second, so that a value changed elsewhere, or what a bot is
 * doing, is seen.
 */
final class ConfigMenu extends ChestMenu {

    /** The chest's title: a menu keeps the one it opened with, so it names no page. */
    private static final Component TITLE = Component.literal("Tachyon settings");
    /** How often, in the viewer's ticks, what it shows is drawn again. */
    private static final int REDRAW_TICKS = 20;

    private final ServerPlayer viewer;
    private final ConfigPages pages;
    private int sinceDrawn;

    private ConfigMenu(int id, Inventory inventory, ServerPlayer viewer, ConfigPages pages) {
        // A container of its own, which nothing else knows of and nothing saves: only what
        // is drawn is ever in it.
        super(MenuType.GENERIC_9x6, id, inventory, new SimpleContainer(ConfigPages.SIZE), 6);
        this.viewer = viewer;
        this.pages = pages;
        paint(pages.draw());
    }

    // --- opening -----------------------------------------------------------------------------

    /**
     * {@code /tachyon config}: the bots the viewer may configure, and for operators the
     * server's defaults, to pick from (with one only, that one). {@code /tachyon config <who>}:
     * one bot's, which {@code <who>} names alone. Players only, anyone of them: what they see is
     * what is theirs.
     */
    static void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon) {
        tachyon.then(Commands.literal("config")
                .executes(ConfigMenu::openPick)
                .then(Bots.who().executes(ConfigMenu::openOne)));
    }

    private static final String NOT_A_PLAYER = "the menu opens for a player in the game;"
            + " from here, /tachyon settings, /tachyon set and /tachyon defaults do the same";

    private static int openPick(CommandContext<CommandSourceStack> c) {
        ServerPlayer viewer = c.getSource().getPlayer();
        if (viewer == null) return Bots.fail(c.getSource(), NOT_A_PLAYER);
        ConfigPages pages = pages(viewer);
        if (!pages.start()) return Bots.fail(c.getSource(), "no bot of yours in the game");
        open(viewer, pages);
        return 1;
    }

    private static int openOne(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer viewer = c.getSource().getPlayer();
        if (viewer == null) return Bots.fail(c.getSource(), NOT_A_PLAYER);
        List<Bots.Bot> found = Bots.find(c);
        if (found.isEmpty()) return 0;          // said why already
        // The viewer's own rights, not the source's: "execute as" keeps the console's.
        List<Bots.Bot> them = found.stream().filter(p -> Bots.mayOrder(viewer, p)).toList();
        if (them.isEmpty()) return Bots.fail(c.getSource(), "none of those is yours to configure");
        if (them.size() > 1) {
            List<String> names = them.stream().map(Bots.Bot::name).limit(6).toList();
            return Bots.fail(c.getSource(), "the menu shows one bot, and that names " + them.size() + ": "
                    + String.join(", ", names) + (them.size() > names.size() ? "..." : ""));
        }
        ConfigPages pages = pages(viewer);
        pages.open(choice(them.get(0)));
        open(viewer, pages);
        return 1;
    }

    /** The pages for a viewer: their rights and their bots, asked anew each time. */
    private static ConfigPages pages(ServerPlayer viewer) {
        return new ConfigPages(Abilities.settings(), () -> Bots.operator(viewer), () -> choices(viewer));
    }

    /** The bots the viewer may configure now: the ones they may order (every one, for an operator). */
    private static List<ConfigPages.Choice> choices(ServerPlayer viewer) {
        List<ConfigPages.Choice> out = new ArrayList<>();
        for (Bots.Bot p : Bots.all()) {
            if (Bots.mayOrder(viewer, p)) out.add(choice(p));
        }
        return out;
    }

    private static ConfigPages.Choice choice(Bots.Bot p) {
        return new ConfigPages.Choice(p.name(), p.doing, p.ownerName, p.data);
    }

    private static void open(ServerPlayer viewer, ConfigPages pages) {
        viewer.openMenu(new SimpleMenuProvider((id, inventory, player) -> new ConfigMenu(id, inventory, viewer, pages), TITLE));
    }

    // --- clicks: never the chest's own ------------------------------------------------------

    /**
     * A click of the viewer's, from the packet's handler: read as what it means to the page,
     * and nothing else. Never the chest's own handling, where items move.
     */
    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        ConfigPages.Click click = read(type, button);
        if (click != null && slot >= 0 && slot < ConfigPages.SIZE) {
            ConfigPages.Outcome done = pages.click(slot, click);
            answer(done);
            if (done.kind() != ConfigPages.Kind.NOTHING) paint(pages.buttons());
        }
        // F swaps with the offhand, which is no slot of this menu: a client that swapped in
        // its picture of it is sent its inventory again, or it would show there what is not.
        if (type == ClickType.SWAP) viewer.inventoryMenu.sendAllDataToRemote();
    }

    /**
     * What a click means here: a left or right click, with shift or not, and Q (with ctrl or
     * not). The rest means nothing: the number keys and F (a swap), the middle click (a
     * copy, for a creative player), a drag (quick craft), a double click's second half (pick
     * up all; its first half was a click), a click outside the chest.
     */
    static ConfigPages.Click read(ClickType type, int button) {
        return switch (type) {
            case PICKUP -> button == 0 ? ConfigPages.Click.LEFT : button == 1 ? ConfigPages.Click.RIGHT : null;
            case QUICK_MOVE -> button == 0 ? ConfigPages.Click.SHIFT_LEFT : button == 1 ? ConfigPages.Click.SHIFT_RIGHT : null;
            case THROW -> ConfigPages.Click.DROP;
            case SWAP, CLONE, QUICK_CRAFT, PICKUP_ALL -> null;
        };
    }

    /** A sound for what a click did, and for a refusal the reason, in the viewer's chat. */
    private void answer(ConfigPages.Outcome done) {
        switch (done.kind()) {
            case MOVED, CHANGED -> viewer.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 0.4f, 1.0f);
            case REFUSED -> {
                viewer.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.MASTER, 0.6f, 0.6f);
                viewer.sendSystemMessage(Component.literal("[tachyon] " + done.why()));
            }
            case NOTHING -> {
            }
        }
    }

    /** Never anything to move: the chest's items are pictures, and the viewer's are theirs. */
    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    // Asked only by the chest's own handling, which never runs here; said anyway.
    @Override
    public boolean canTakeItemForPickAll(ItemStack carried, Slot slot) {
        return false;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return false;
    }

    // --- staying open ------------------------------------------------------------------------

    /**
     * Asked by the server every tick of the viewer's, and before each of their clicks: false
     * closes it. The viewer must be alive; the rest is the pages' rule
     * ({@link ConfigPages#stillValid}): the bot it shows still in the game (the same bot: one
     * that left and came back is another) and theirs to configure; the defaults, theirs to
     * change.
     */
    @Override
    public boolean stillValid(Player player) {
        return viewer.isAlive() && pages.stillValid();
    }

    /** Every tick of the viewer's, from the server: drawn again once a second, then what changed sent. */
    @Override
    public void broadcastChanges() {
        if (++sinceDrawn >= REDRAW_TICKS) paint(pages.draw());
        super.broadcastChanges();
    }

    // --- drawing -------------------------------------------------------------------------------

    /** The buttons into the chest; the server sends the viewer only the slots that changed. */
    private void paint(ConfigPages.Button[] buttons) {
        sinceDrawn = 0;
        for (int i = 0; i < ConfigPages.SIZE; i++) getContainer().setItem(i, item(buttons[i]));
    }

    /** A button as an item: its icon's, named, with its lines under it. */
    static ItemStack item(ConfigPages.Button b) {
        if (b.icon() == ConfigPages.Icon.NONE) return ItemStack.EMPTY;
        ItemStack st = new ItemStack(icon(b.icon()));
        if (b.icon() == ConfigPages.Icon.FILLER) {
            st.set(DataComponents.HIDE_TOOLTIP, Unit.INSTANCE);
            return st;
        }
        st.set(DataComponents.CUSTOM_NAME, Component.literal(b.name()).withStyle(plain(name(b.icon()))));
        if (!b.lore().isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (ConfigPages.Line l : b.lore()) lines.add(Component.literal(l.text()).withStyle(plain(tone(l.tone()))));
            st.set(DataComponents.LORE, new ItemLore(lines));
        }
        if (b.icon() == ConfigPages.Icon.ON) st.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        if (b.bot() != null) {
            // The profile a bot has (an offline one: its name, and the id made from it). With
            // both and no skin, a client asks nobody for one: it draws the skin its id picks,
            // the one the bot is drawn with in the world.
            st.set(DataComponents.PROFILE, new ResolvableProfile(new GameProfile(UUIDUtil.createOfflinePlayerUUID(b.bot()), b.bot())));
        }
        if (b.count() > 1) {
            // A chest keeps a stack within its item's size: up to 99 for a number's value.
            st.set(DataComponents.MAX_STACK_SIZE, 99);
            st.setCount(Math.min(99, b.count()));
        }
        return st;
    }

    private static Item icon(ConfigPages.Icon icon) {
        return switch (icon) {
            case NONE -> Items.AIR;
            case FILLER -> Items.GRAY_STAINED_GLASS_PANE;
            case BOT -> Items.PLAYER_HEAD;
            case DEFAULTS -> Items.COMPARATOR;
            case GROUP -> Items.BOOK;
            case ON -> Items.LIME_DYE;
            case OFF -> Items.GRAY_DYE;
            case NUMBER -> Items.CLOCK;
            case LOCKED -> Items.BARRIER;
            case ADVANCED -> Items.REDSTONE;
            case BACK -> Items.ARROW;
            case PREVIOUS, NEXT -> Items.PAPER;
        };
    }

    private static ChatFormatting name(ConfigPages.Icon icon) {
        return switch (icon) {
            case ON -> ChatFormatting.GREEN;
            case NUMBER -> ChatFormatting.YELLOW;
            case LOCKED -> ChatFormatting.GRAY;
            case GROUP -> ChatFormatting.GOLD;
            case BOT -> ChatFormatting.AQUA;
            case DEFAULTS -> ChatFormatting.LIGHT_PURPLE;
            default -> ChatFormatting.WHITE;
        };
    }

    private static ChatFormatting tone(ConfigPages.Tone tone) {
        return switch (tone) {
            case TEXT -> ChatFormatting.GRAY;
            case VALUE -> ChatFormatting.WHITE;
            case HINT -> ChatFormatting.YELLOW;
            case WARNING -> ChatFormatting.RED;
        };
    }

    /** A colour, not in italics: a custom name and lore lines are in italics unless told otherwise. */
    private static Style plain(ChatFormatting colour) {
        return Style.EMPTY.withColor(colour).withItalic(false);
    }
}
