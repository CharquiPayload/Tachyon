package tachyon.path;

import java.util.ArrayList;
import java.util.List;

/**
 * Crossing open water in a straight line along the surface, WITHOUT a search: Masurium's
 * swimming segments.
 *
 * <p>On the sea every tile costs the same, so a search spreads like a stain and gets
 * nowhere: on Masurium's client a bot stayed 400 blocks from its target, each segment
 * "I gave up after looking at 7169 tiles". It could swim; it could not plan the crossing.
 * Here the water is a plane and nothing is searched: a point every {@value #STEP} blocks
 * toward the destination, at the height of each column's surface (the legs hold jump in
 * water, so the body floats), up to {@value #LENGTH} blocks. As soon as a column stops
 * being water (a coast, ice, a boat) the stretch ends there, and from the shore the search
 * rules again. Water is never dug or bridged from here.
 */
public final class Swim {

    /** How long a stretch is, and how far apart its points are. */
    public static final int LENGTH = 48, STEP = 4;
    /** From the shore, how far toward the destination water is looked for. */
    public static final int TO_WATER = 12;

    private Swim() {
    }

    /**
     * The straight stretch from where the body is ({@code x}, {@code z}; its tile
     * {@code here}) toward {@code to}: its first point is {@code here}. From the shore only
     * when water lies within {@link #TO_WATER} blocks toward it (the body walks in; falling
     * into water is free). Null when there is none: already there, no water ahead.
     */
    public static List<Route.Point> stretch(World m, Route.Point here, double x, double z, Route.Point to) {
        double dx = to.x() + 0.5 - x, dz = to.z() + 0.5 - z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < STEP) return null;
        dx /= dist;
        dz /= dist;
        List<Route.Point> points = new ArrayList<>();
        points.add(here);
        int from = STEP;
        if (!m.water(here.x(), here.y(), here.z())) {
            int shore = -1;
            for (int d = 1; d <= TO_WATER; d++) {
                if (surface(m, (int) Math.floor(x + dx * d), here.y(), (int) Math.floor(z + dz * d)) != Integer.MIN_VALUE) {
                    shore = d;
                    break;
                }
            }
            if (shore < 0) return null;
            from = shore;
        }
        int length = (int) Math.min(LENGTH, dist);
        for (int d = from; d <= length; d += STEP) {
            int px = (int) Math.floor(x + dx * d), pz = (int) Math.floor(z + dz * d);
            int y = surface(m, px, here.y(), pz);
            if (y == Integer.MIN_VALUE) break;           // the water ended: a coast
            points.add(new Route.Point(px, y, pz));
        }
        return points.size() < 2 ? null : points;
    }

    /**
     * The highest water tile of the column, looked for from {@code y0} and a couple of
     * blocks up or down (there are no waves, but there are coasts); MIN_VALUE when there is
     * none there. The head room over it is looked at too: water under a roof is no surface
     * to swim along.
     */
    static int surface(World m, int x, int y0, int z) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int y : new int[]{y0 + dy, y0 - dy}) {
                if (m.water(x, y, z)) {
                    while (m.water(x, y + 1, z)) y++;
                    return m.solid(x, y + 1, z) ? Integer.MIN_VALUE : y;
                }
            }
        }
        return Integer.MIN_VALUE;
    }
}
