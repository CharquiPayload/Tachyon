package tachyon.path;

/**
 * The only thing the path finder needs to know about the world.
 * Deliberately tiny: a couple of questions. That way the search does not touch Minecraft
 * and can be tested against a world drawn with text, in milliseconds and without opening
 * the game. When pathfinding lived inside an obfuscated jar, it was exactly the one thing
 * that could not be checked.
 */
public interface World {

    /** Does this block stop movement? Outside the known world: yes. */
    boolean solid(int x, int y, int z);

    /** Is there water? It matters because water cancels fall damage from any height. */
    boolean water(int x, int y, int z);

    /**
     * Is there LAVA?
     *
     * <p>Asked SEPARATELY from water, and the split is not cosmetic: the client used to
     * treat both fluids as the same thing, and since water has two privileges (one can be
     * inside it, and it cancels fall damage from any height) the search considered a lava
     * lake a place to stand in AND a free fall. It would have jumped in from twenty
     * blocks rather than take the stairs.
     *
     * <p>Worlds that do not tell them apart inherit the "no" and behave as before.
     */
    default boolean lava(int x, int y, int z) {
        return false;
    }

    /**
     * Is this tile blocked by a DOOR (or a gate) that can be opened by hand?
     *
     * <p>Closed, it does not let anyone through, but it is not a wall: it is opened and
     * walked through. Without this, a closed door was a wall and the bot could not get
     * into a house, not even its own. Iron doors do not count: those need redstone and do
     * not open by hand.
     */
    default boolean door(int x, int y, int z) {
        return false;
    }

    /**
     * COULD this tile be dug to get through? Only asked when the search runs with {@code
     * canBreak} (the break_to_advance toggle). In the game the break permissions
     * whitelist answers it; worlds that do not tell (the old tests) inherit the "no" and
     * nothing changes.
     */
    default boolean breakable(int x, int y, int z) {
        return false;
    }

    /**
     * Is anything known of this column? A world read from loaded chunks reads the rest as
     * rock (so a search does not wander into what it cannot see); this tells that rock from
     * real rock, for a destination too far off to be judged yet. Worlds that know every
     * column (the tests' drawings) inherit the "yes".
     */
    default boolean known(int x, int z) {
        return true;
    }

    /**
     * Is this tile left out of the search for now? A tile the bot's body failed to get into
     * lately (its stuck spots): the same search from the same place would find the same
     * route into it again. Worlds that keep none (the tests' drawings) inherit the "no".
     * {@link #canStand} of a world that vetoes answers no for these too; the steps that
     * build their own floor (a bridge, a tower) or dig their way ask this directly, since
     * they do not ask whether one can stand there as it is.
     */
    default boolean vetoed(int x, int y, int z) {
        return false;
    }

    /**
     * Can one stand here?
     * The player is two blocks tall, so two free blocks with something solid below are
     * needed. Being inside water also counts: one floats.
     */
    default boolean canStand(int x, int y, int z) {
        if (solid(x, y, z) || solid(x, y + 1, z)) return false;
        // Neither the feet nor the head inside lava. And a lava floor is not a floor:
        // since lava is not solid, the "solid(y-1)" below already rules it out.
        if (lava(x, y, z) || lava(x, y + 1, z)) return false;
        return solid(x, y - 1, z) || water(x, y, z);
    }

    /**
     * Does standing here hurt without being a wall? Berry bushes, fire, powder snow,
     * wither roses. Standing there is NOT forbidden (if the bot is already inside, the
     * only way out is a path that STARTS there); entering is charged dearly, so a bush is
     * never chosen when it can be walked around. Forbidding it was the first attempt, and
     * it left a bot stuck inside one: its own tile was not a "place to stand" and there
     * was no route from anywhere.
     */
    default boolean dangerous(int x, int y, int z) {
        return false;
    }
}
