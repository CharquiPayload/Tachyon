# Roadmap

What Tachyon does today, what is being built now and what comes after. It is
kept up to date as work lands. Most of what is next brings over what
[Masurium](https://github.com/CharquiPayload/masurium)'s client bots already
do, rebuilt to run on the server, and adds things only a server can do.

## Done

- **0.1.0: bots that live on the server.** They walk, follow, hunt and clear
  areas, and they talk to an AI model: any OpenAI-style API, Ollama or
  Anthropic's. Orders can go to many bots at once, by pattern or selector.
- **Orders told back.** When an order is over, done or given up, the bot says
  so. `spawn <name> 1` brings in one bot named `<name>`.
- **0.2.0: the base.** What a bot can do lives in abilities, each in files of
  its own. The brain's tools are in a registry. Each bot keeps its data, owner
  included, in the world save. Settings exist per bot and per server.
- **0.2.0: death and restarts.**
  - A bot that dies respawns. With the setting off, it leaves the game instead.
  - Bots come back by themselves after a server restart.
  - Bots don't count toward the night skip, and phantoms leave them alone.
  - A lite brain setting gives small local models a shorter tool list.
- **0.2.0: `/tachyon config`.** A chest menu that any vanilla client can
  show. It has basic settings, advanced ones, and server defaults for
  operators.
- **0.3.0: surviving and fighting.**
  - Gear: it picks the best weapon, puts on better armor by itself, and eats
    the right food, never food its owner banned.
  - Combat: it shoots a bow with a player's draw and real aim, and hunts the
    way a player does: it goes looking when nothing is in sight, and picks up
    the drops.
  - Items: it keeps a trash list and tosses what it does not need.
  - Death: it records each death and goes back for the items it dropped. It
    tells its owner what happened, in its own words or in a plain line, as the
    owner chooses.
  - Reflexes: it fights back, shoots creepers or runs from them, answers
    archers, backs off when badly hurt, comes up for air, stays afloat, digs
    itself out when buried and eats when hungry. It sees only what a player
    in its place could see.
- **0.4.0: polish.**
  - `gather` ("get me some dirt", "chop 20 logs", seeds from grass): it breaks
    only that kind of block, in the open, and picks up what drops. When none
    is near, it goes out looking.
  - Builds are safe. The server keeps the blocks players placed, and a bot
    never gathers them or what they touch, nor digs through them to get
    somewhere. A build from before the mod is known by what it is made of
    (planks, doors, glass, a log cabin). Its brain's `clear` refuses a box that
    holds a build; a person's `/tachyon clear` still works.
  - A `verbose` setting: technical lines for the owner only when asked for.
    Deaths and orders given by a command reach the owner through the notices.
  - A spawn point set on a slab or a dirt path works: the bot tries one block
    up.
  - The menu reads better: on and off, full sentences, labels in refusals,
    and which default Q goes back to.
  - A bot with gravel fallen at its feet digs itself out.
- **0.4.0: moving better.**
  - Trips of hundreds of blocks, a leg at a time, judged as a whole: it stops
    only when three legs in a row get it no closer, and says where, why and
    with what numbers.
  - "Go to the chest" ends beside it; "come here" works on a dirt path.
  - Fence gates, opened and closed behind it like doors.
  - Bridges and towers built with the cheap blocks it carries, as a player
    places them; digging through blocks on its break list, when allowed.
  - Climbing out of a hole; swimming straight across open water.
  - A tile it got stuck on is left out of its searches for a while, and it
    walks a short detour when there is no way from where it stands.
  - Never into lava when digging through, never a tower it cannot get down
    from, and "come here" from a player in the air goes to the ground under
    them.

## In progress

- **Hordes** come next (see Next).

## Next

- **Hordes.** Many bots with one brain: an army that follows a general, or a
  mindless horde. A headquarters with chests of gear, and wars between hordes.
- **Moving further.** Portals between dimensions, horses and leads.
- **Items.** Crafting with every loaded recipe, mods included. Furnaces.
  Chests the bot remembers, with their owners' permissions.
- **Mining and building.** Strip mining that follows veins, staircases,
  torches, filling areas, and blueprints built layer by layer.
- **Farms and animals.** Sowing with irrigation, harvesting, fishing,
  shearing, taming and breeding.
- **Company and exploring.** Escorts, guards that follow their leader, beds,
  exploring, and places it remembers.
- **Brain and memory.** A diary, what it knows about each person, standing
  orders, reminders, personality and language.
- **In-game integration.** State icons in the tab list, a sidebar with every
  bot, and status commands.
- **Outside brains.** A generic way in for an outside agent to drive a bot.
  The bridges to specific agents live outside this repository.

## Later

- **Fabric.** First, try Tachyon on Fabric through Kilt. A native
  multi-loader build comes only for what Kilt cannot carry.
- **A release on Modrinth.**
- **Talking without naming it.** A small classifier decides whether a chat
  line is meant for the bot.
- **Performance.**
  - Stagger the first searches of many hunters that start at once.
  - Crowds that build: bots sent together over one wall tower at the same
    spot and get in each other's way.
  - Speed up the slow end of each layer when clearing.
