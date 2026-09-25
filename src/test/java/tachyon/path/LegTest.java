package tachyon.path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trip's legs, against worlds drawn with text: where a tile asked for is really gone to,
 * climbing out of a hole, the column before the tile, swimming straight on, the detour, and
 * the stuck spots a search leaves out. What the legs then do with it in the game (the tower
 * and bridge steps, the digging) needs the game, and is tried there.
 */
class LegTest {

    private static final Route.Options WALK = new Route.Options(3, 20_000, false, true);
    private static final Route.Options BUILD = new Route.Options(3, 20_000, true, true);

    private static Leg.Ask ask(Route.Point here, Route.Options op, boolean blocks, boolean stuck) {
        return new Leg.Ask(here, here.x() + 0.5, here.z() + 0.5, false, op, blocks, stuck);
    }

    /** A flat floor of stone, 9 by 5, with three layers of air over it. */
    private static TextWorld field() {
        return TextWorld.of(0,
                new String[]{"#########", "#########", "#########", "#########", "#########"},
                new String[]{".........", ".........", "S........", ".........", "........."},
                new String[]{".........", ".........", ".........", ".........", "........."},
                new String[]{".........", ".........", ".........", ".........", "........."});
    }

    @Test
    @DisplayName("a tile in the air is landed: the ground under it")
    void aTileInTheAirIsLanded() {
        TextWorld m = field();
        assertEquals(new Route.Point(6, 1, 2), Landing.land(m, new Route.Point(6, 3, 2)));
    }

    @Test
    @DisplayName("a floor's own cell asked (a path read off where one stands): nothing under or beside, the tile over it")
    void theTileOverIt() {
        TextWorld m = field();
        Route.Point path = new Route.Point(6, 0, 2);
        assertNull(Landing.land(m, path), "a solid block is not landed on top of");
        assertTrue(Landing.sides(m, path, null).isEmpty(), "its sides are floor too");
        Leg.Found f = Leg.first(m, ask(m.exitPoint(), WALK, false, false), path);
        assertTrue(f.hasRoute(), f.route().reason());
        assertEquals(new Route.Point(6, 1, 2), f.destination());
    }

    @Test
    @DisplayName("go to the bed: the bed is no place to stand, a tile beside it is")
    void goToTheBedEndsBesideIt() {
        TextWorld m = TextWorld.of(0,
                new String[]{"#########", "#########", "#########", "#########", "#########"},
                new String[]{".........", ".........", "S.....#..", ".........", "........."},
                new String[]{".........", ".........", "......#..", ".........", "........."},
                new String[]{".........", ".........", ".........", ".........", "........."},
                new String[]{".........", ".........", ".........", ".........", "........."});
        // The "bed" is the block at x=6 (2 high here): its own cell cannot be stood in, so
        // its sides it is.
        Leg.Found f = Leg.first(m, ask(m.exitPoint(), WALK, false, false), new Route.Point(6, 1, 2));
        assertTrue(f.hasRoute(), f.route().reason());
        assertEquals(Leg.Kind.BESIDE, f.kind());
        Route.Point end = f.route().steps().get(f.route().steps().size() - 1);
        assertEquals(end, f.destination(), "where the trip ends is the side reached");
        assertEquals(1, Math.abs(end.x() - 6) + Math.abs(end.z() - 2), "beside the bed: " + end);
    }

    @Test
    @DisplayName("a tile too far off to be seen: the first leg goes toward its column, and does not say where it ends")
    void tooFarToSee() {
        // A field 24 wide, all it knows: past it, rock, as unloaded chunks are read.
        String floor = "#".repeat(24), air = ".".repeat(24);
        TextWorld seen = TextWorld.of(0, new String[]{floor, floor, floor},
                new String[]{air, "S" + air.substring(1), air}, new String[]{air, air, air}, new String[]{air, air, air});
        World m = new World() {
            public boolean solid(int x, int y, int z) {
                return seen.solid(x, y, z);
            }

            public boolean water(int x, int y, int z) {
                return seen.water(x, y, z);
            }

            public boolean known(int x, int z) {
                return x >= 0 && x < 24 && z >= 0 && z < 3;
            }
        };
        Route.Point far = new Route.Point(300, 1, 1);
        assertFalse(Route.search(m, seen.exitPoint(), Route.Meta.onlyXZ(300, 1), WALK).hasRoute(),
                "toward the column alone, it runs out of world: no route");
        Leg.Found f = Leg.first(m, ask(seen.exitPoint(), WALK, false, false), far);
        assertTrue(f.hasRoute(), f.route().reason());
        assertEquals(23, f.route().steps().get(f.route().steps().size() - 1).x(), "to the edge of what it knows, toward it");
        assertNull(f.destination(), "where it ends is judged from closer");
    }

    @Test
    @DisplayName("nothing to stand on or beside: it says so, with no route made up")
    void noGroundAnywhere() {
        TextWorld m = field();
        Leg.Found f = Leg.first(m, ask(m.exitPoint(), WALK, false, false), new Route.Point(40, 1, 40));
        assertFalse(f.hasRoute());
        assertTrue(f.route().reason().contains("no ground"), f.route().reason());
    }

    /** A pit 1 wide and 3 deep in a floor at y=4, the bot at its bottom (y=1). */
    private static TextWorld pit(boolean roof) {
        String[] solid = {"#####", "#####", "#####"};
        String[] shaft = {"#####", "##S##", "#####"};
        String[] open = {"#####", "##.##", "#####"};
        String[] air = {".....", ".....", "....."};
        // A roof over the shaft, where the head would be once up on the floor's level.
        String[] top = roof ? new String[]{".....", "..#..", "....."} : air;
        return TextWorld.of(0, solid, shaft, open, open, air, top, air, air);
    }

    @Test
    @DisplayName("shut in a hole, building off: it climbs out on a tower, the one exception")
    void inAHoleItClimbsOut() {
        TextWorld m = pit(false);
        Route.Point me = m.exitPoint();
        assertTrue(Rescue.inAHole(m, me));
        assertEquals(3, Rescue.exitHeight(m, me), "3 blocks up it can walk out");
        Leg.Found f = Leg.first(m, ask(me, WALK, true, false), new Route.Point(0, 4, 0));
        assertEquals(Leg.Kind.RESCUE, f.kind(), f.route().reason());
        assertEquals(Rescue.staircase(me, 3), f.route().steps());
        assertNull(f.destination(), "where the trip ends is found from the top");
    }

    @Test
    @DisplayName("in a hole with nothing to build with, or a roof over it: trapped, said so")
    void trappedSaysWhy() {
        TextWorld m = pit(false);
        Leg.Found none = Leg.first(m, ask(m.exitPoint(), WALK, false, false), new Route.Point(0, 4, 0));
        assertFalse(none.hasRoute());
        assertTrue(none.route().reason().contains("carry no blocks"), none.route().reason());
        TextWorld roofed = pit(true);
        assertEquals(-1, Rescue.exitHeight(roofed, roofed.exitPoint()));
        Leg.Found roof = Leg.first(roofed, ask(roofed.exitPoint(), WALK, true, false), new Route.Point(0, 4, 0));
        assertFalse(roof.hasRoute());
        assertTrue(roof.route().reason().contains("no way out within 8"), roof.route().reason());
    }

    @Test
    @DisplayName("able to build, it needs no rescue: the search towers out itself")
    void ableToBuildTheSearchTowersOut() {
        TextWorld m = pit(false);
        Leg.Found f = Leg.first(m, ask(m.exitPoint(), BUILD, true, false), new Route.Point(0, 4, 0));
        assertEquals(Leg.Kind.WALK, f.kind(), f.route().reason());
        assertTrue(f.hasRoute());
        assertEquals(new Route.Point(0, 4, 0), f.route().steps().get(f.route().steps().size() - 1));
    }

    @Test
    @DisplayName("a trench across the way: with blocks it bridges it, without them it has no way")
    void bridgeOverAGap() {
        // A trench 2 wide and 3 deep across the way: it may drop in (3 blocks), but not
        // climb out of it on the other side.
        TextWorld m = TextWorld.of(0,
                new String[]{"#######", "#######", "#######"},
                new String[]{"###..##", "###..##", "###..##"},
                new String[]{"###..##", "###..##", "###..##"},
                new String[]{"###..##", "###..##", "###..##"},
                new String[]{"S......", ".......", "......."},
                new String[]{".......", ".......", "......."},
                new String[]{".......", ".......", "......."});
        Route.Point to = new Route.Point(6, 4, 0);
        Leg.Found walk = Leg.first(m, ask(m.exitPoint(), WALK, true, false), to);
        assertFalse(walk.hasRoute(), "on foot there is none: " + walk.route().steps());
        Leg.Found build = Leg.first(m, ask(m.exitPoint(), BUILD, true, false), to);
        assertTrue(build.hasRoute(), build.route().reason());
        for (Route.Point q : build.route().steps()) assertEquals(4, q.y(), "a bridge, at its level: " + build.route().steps());
    }

    @Test
    @DisplayName("the legs after the first aim at the column first, not a tower toward the tile")
    void theColumnFirst() {
        // The destination is 3 over the floor, in the air over x=6. With building allowed the
        // first leg would tower to it; a leg after it goes to the column and stands there.
        TextWorld m = field();
        Route.Point to = new Route.Point(6, 3, 2);
        Leg.Found f = Leg.next(m, ask(m.exitPoint(), BUILD, true, false), to);
        assertTrue(f.hasRoute(), f.route().reason());
        Route.Point end = f.route().steps().get(f.route().steps().size() - 1);
        assertEquals(6, end.x());
        assertEquals(2, end.z());
        assertEquals(1, end.y(), "on the ground under it, no tower: " + f.route().steps());
    }

    @Test
    @DisplayName("on open water far off it swims straight on, a point every 4 blocks, to the coast")
    void swimsStraightOn() {
        StringBuilder water = new StringBuilder(), air = new StringBuilder(), rock = new StringBuilder();
        for (int x = 0; x < 80; x++) {
            water.append(x < 70 ? '~' : '#');
            air.append('.');
            rock.append('#');
        }
        String[] bottom = {rock.toString(), rock.toString(), rock.toString()};
        String[] sea = {water.toString(), water.toString(), water.toString()};
        String[] sky = {air.toString(), air.toString(), air.toString()};
        TextWorld m = TextWorld.of(0, bottom, sea, sea, sky, sky);
        Route.Point here = new Route.Point(2, 2, 1);
        Leg.Ask a = new Leg.Ask(here, 2.5, 1.5, true, WALK, false, false);
        Leg.Found f = Leg.next(m, a, new Route.Point(78, 3, 1));
        assertEquals(Leg.Kind.SWIM, f.kind(), f.route().reason());
        List<Route.Point> steps = f.route().steps();
        assertEquals(here, steps.get(0));
        for (int i = 1; i < steps.size(); i++) {
            assertEquals(2, steps.get(i).y(), "along the surface");
            assertEquals(4, steps.get(i).x() - steps.get(i - 1).x(), "4 blocks on: " + steps);
        }
        assertTrue(steps.get(steps.size() - 1).x() <= 2 + Swim.LENGTH, "48 blocks at most");
        Leg.Found first = Leg.first(m, a, new Route.Point(78, 3, 1));
        assertEquals(Leg.Kind.SWIM, first.kind(), "the first leg too: " + first.route().reason());
        assertNull(first.destination(), "a swim does not say where the trip ends");
        List<Route.Point> toCoast = Swim.stretch(m, new Route.Point(50, 2, 1), 50.5, 1.5, new Route.Point(78, 3, 1));
        assertNotNull(toCoast);
        assertTrue(toCoast.get(toCoast.size() - 1).x() < 70, "cut at the coast: " + toCoast);
    }

    @Test
    @DisplayName("a detour goes to the farthest tile it reaches, 2 to 5 blocks off")
    void theDetour() {
        TextWorld m = field();
        Route.Point here = new Route.Point(4, 1, 2);
        Route.Result d = Detour.find(m, here, WALK);
        assertNotNull(d);
        Route.Point end = d.steps().get(d.steps().size() - 1);
        int dx = end.x() - here.x(), dz = end.z() - here.z();
        assertTrue(dx * dx + dz * dz >= 4, "at least 2 off: " + end);
        assertEquals(4, Math.max(Math.abs(dx), Math.abs(dz)), "the farthest in a field 9 wide: " + end);
    }

    /** A floor of stone 9 by 5 with fifteen layers of air over it: room to build a tower in. */
    private static TextWorld tall() {
        String[] floor = {"#########", "#########", "#########", "#########", "#########"};
        String[] air = {".........", ".........", ".........", ".........", "........."};
        String[][] layers = new String[16][];
        layers[0] = floor;
        for (int i = 1; i < 16; i++) layers[i] = air;
        layers[1] = new String[]{".........", ".........", "S........", ".........", "........."};
        return TextWorld.of(0, layers);
    }

    @Test
    @DisplayName("building allowed, a tile high in the air it could not get off again is the ground under it")
    void noTowerWithNoWayDown() {
        TextWorld m = tall();
        Route.Point high = new Route.Point(6, 13, 2);
        assertFalse(Landing.leavable(m, high, 3), "12 blocks over the floor, nothing beside it");
        Leg.Found f = Leg.first(m, ask(m.exitPoint(), BUILD, true, false), high);
        assertTrue(f.hasRoute(), f.route().reason());
        assertEquals(new Route.Point(6, 1, 2), f.destination(), "the ground under it, not a pillar up to it");
        for (Route.Point q : f.route().steps()) assertEquals(1, q.y(), "no tower: " + f.route().steps());
    }

    @Test
    @DisplayName("building allowed, a tile in the air it could step off (a ledge beside it, or a short drop) is built up to")
    void aTowerItCanGetOff() {
        TextWorld m = tall();
        Route.Point low = new Route.Point(6, 3, 2);
        assertTrue(Landing.leavable(m, low, 3), "2 over the floor: a drop it may take");
        Leg.Found f = Leg.first(m, ask(m.exitPoint(), BUILD, true, false), low);
        assertTrue(f.hasRoute(), f.route().reason());
        assertEquals(low, f.destination(), "climb up there");
    }

    @Test
    @DisplayName("on top of a pillar with air all round it is not in a hole")
    void aPillarIsNoHole() {
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####", "#####"},
                new String[]{".....", "..#..", "....."},
                new String[]{".....", "..#..", "....."},
                new String[]{".....", "..#..", "....."},
                new String[]{".....", "..S..", "....."},
                new String[]{".....", ".....", "....."});
        assertFalse(Rescue.inAHole(m, m.exitPoint()));
        assertTrue(Rescue.inAHole(pit(false), pit(false).exitPoint()), "a pit's walls still make a hole");
    }

    @Test
    @DisplayName("a stuck spot is not built onto: the bridge that failed there is not planned again")
    void aStuckSpotIsNotBridged() {
        // One lane over a trench 1 wide and 3 deep: the only way over is a bridge block at (3,4,0).
        TextWorld m = TextWorld.of(0,
                new String[]{"#######"},
                new String[]{"###.###"},
                new String[]{"###.###"},
                new String[]{"###.###"},
                new String[]{"S......"},
                new String[]{"......."},
                new String[]{"......."});
        Route.Point to = new Route.Point(6, 4, 0);
        assertTrue(Route.search(m, m.exitPoint(), to, BUILD).hasRoute(), "a bridge over it");
        World vetoing = new World() {
            public boolean solid(int x, int y, int z) {
                return m.solid(x, y, z);
            }

            public boolean water(int x, int y, int z) {
                return m.water(x, y, z);
            }

            public boolean vetoed(int x, int y, int z) {
                return x == 3 && y == 4 && z == 0;
            }

            public boolean canStand(int x, int y, int z) {
                return !vetoed(x, y, z) && World.super.canStand(x, y, z);
            }
        };
        Route.Result r = Route.search(vetoing, m.exitPoint(), to, BUILD);
        assertFalse(r.hasRoute() && r.steps().contains(new Route.Point(3, 4, 0)), "not over the tile it got stuck on: "
                + r.steps());
    }

    @Test
    @DisplayName("a stuck spot is left out of the search: the same search finds another way")
    void aStuckSpotIsLeftOut() {
        // Two ways round a pillar; the short one goes through (2,1,1). Vetoed, the other.
        TextWorld m = TextWorld.of(0,
                new String[]{"#####", "#####", "#####"},
                new String[]{".....", "S.#.G", "....."},
                new String[]{".....", "..#..", "....."},
                new String[]{".....", ".....", "....."});
        Route.Result before = Route.search(m, m.exitPoint(), m.goal());
        assertTrue(before.hasRoute());
        Route.Point through = before.steps().get(2);
        Set<Route.Point> vetoed = Set.of(through);
        World without = new World() {
            public boolean solid(int x, int y, int z) {
                return m.solid(x, y, z);
            }

            public boolean water(int x, int y, int z) {
                return m.water(x, y, z);
            }

            public boolean canStand(int x, int y, int z) {
                return !vetoed.contains(new Route.Point(x, y, z)) && World.super.canStand(x, y, z);
            }
        };
        Route.Result after = Route.search(without, m.exitPoint(), m.goal());
        assertTrue(after.hasRoute(), after.reason());
        assertFalse(after.steps().contains(through), "it went round the stuck spot: " + after.steps());
    }
}
