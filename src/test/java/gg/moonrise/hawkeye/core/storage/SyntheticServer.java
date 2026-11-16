package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A busy survival server as a stream of events for the storage benchmark: 8,000 players, 30 to 120 of
 * them online, each mining and building around where they stand, storing items at home, fighting,
 * farming, trading and chatting, plus the world's own changes (crop growth, liquid flows, explosions,
 * pistons). Every Hawkeye action occurs. Payload sizes come from a real Paper 26.3 server: a killed
 * mob's entity bytes are 520 to 720 bytes, a minecart or boat about 360, an item frame or painting
 * about 455, an item with components 110 to 210 (a shulker box with contents up to 2 KB), a plain item
 * in a broken chest's snapshot about 77. Paper's item and entity bytes are gzip streams, so they are
 * random bytes behind a gzip header here: as incompressible as the real ones.
 */
public final class SyntheticServer {

    private static final String[] WORLDS = {"world", "world_nether", "world_the_end"};
    private static final int PLAYERS = 8_000;
    private static final int MIN_ONLINE = 30;
    private static final int MAX_ONLINE = 120;
    private static final int START_ONLINE = 70;
    private static final String AIR = "minecraft:air";
    private static final byte[] GZIP_HEADER = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff};

    private static final Actor GROWTH = Actor.source("growth");
    private static final Actor WATER = Actor.source("water");
    private static final Actor LAVA = Actor.source("lava");
    private static final Actor PHYSICS = Actor.source("physics");
    private static final Actor PISTON = Actor.source("piston");
    private static final Actor CREEPER = Actor.source("creeper");
    private static final Actor FIRE = Actor.source("fire");
    private static final Actor NATURE = Actor.source("nature");
    private static final Actor[] KILL_CAUSES = {Actor.source("fall"), Actor.source("lava"), Actor.source("drown"),
        Actor.source("zombie"), Actor.source("iron_golem"), Actor.source("creeper"), Actor.source("fire")};

    private final SplittableRandom random;
    private final double meanGapMillis;
    private final Actor[] players = new Actor[PLAYERS];
    private final int[][] homes = new int[PLAYERS][];
    private final boolean[] isOnline = new boolean[PLAYERS];
    private final List<Online> online = new ArrayList<>();
    private final Map<Action, Tally> tallies = new EnumMap<>(Action.class);
    private long millis;
    private int sequence;
    private long mobEntityBytes;
    private int burstLeft;
    private Actor burstActor = CREEPER;
    private int[] burstCentre = new int[4];

    /**
     * A server whose events start at a time and arrive at an average rate.
     *
     * @param seed          the random seed (the same seed gives the same events)
     * @param startMillis   the time of the first event
     * @param meanGapMillis the average time between two events
     */
    public SyntheticServer(long seed, long startMillis, double meanGapMillis) {
        this.random = new SplittableRandom(seed);
        this.millis = startMillis;
        this.meanGapMillis = meanGapMillis;
        for (int i = 0; i < PLAYERS; i++) {
            players[i] = Actor.player(new UUID(random.nextLong(), random.nextLong()), name(i));
            homes[i] = new int[] {random.nextInt(-5_000, 5_000), 63 + random.nextInt(12), random.nextInt(-5_000, 5_000)};
        }
        while (online.size() < START_ONLINE) {
            join();
        }
    }

    /**
     * The next event.
     *
     * @return the event, a little later than the previous one
     */
    public @NotNull PendingEvent next() {
        if (burstLeft > 0) {
            burstLeft--;
            millis += random.nextInt(3);
            int[] at = burstCentre;
            return block(WORLDS[at[0]], at[1] + random.nextInt(-4, 5), at[2] + random.nextInt(-3, 4),
                    at[3] + random.nextInt(-4, 5), burstActor, Action.BLOCK_BREAK, palette(at[0]).pick(random), AIR,
                    null);
        }
        millis += (long) (-Math.log(1 - random.nextDouble()) * meanGapMillis);
        return switch (Kind.pick(random)) {
            case BREAK -> playerBreak();
            case EXPLOSION -> explosion();
            case NATURAL_BREAK -> naturalBlock(Action.BLOCK_BREAK);
            case PLACE -> playerPlace();
            case FLOW -> flow();
            case PISTON_PLACE -> naturalBlock(Action.BLOCK_PLACE);
            case CHANGE -> clicked(Action.BLOCK_CHANGE);
            case NATURAL_CHANGE -> naturalBlock(Action.BLOCK_CHANGE);
            case SIGN -> sign();
            case INTERACT -> clicked(Action.INTERACT);
            case GROW -> grow();
            case DEPOSIT -> container(Action.CONTAINER_DEPOSIT);
            case WITHDRAW -> container(Action.CONTAINER_WITHDRAW);
            case DROP -> item(Action.ITEM_DROP, SyntheticContent.ITEMS, 64);
            case PICKUP -> item(Action.ITEM_PICKUP, SyntheticContent.ITEMS, 64);
            case CRAFT -> item(Action.ITEM_CRAFT, SyntheticContent.ITEMS, 64);
            case THROW -> item(Action.ITEM_THROW, SyntheticContent.THROWN, 1);
            case ITEM_BREAK -> item(Action.ITEM_BREAK, SyntheticContent.WORN_OUT, 1);
            case TRADE -> item(Action.ITEM_TRADE, SyntheticContent.TRADED, 12);
            case KILL -> kill();
            case SPAWN -> entity(Action.ENTITY_SPAWN, SyntheticContent.PLACED_ENTITIES.pick(random),
                    new Payload.EntityRef(new UUID(random.nextLong(), random.nextLong())));
            case REMOVE -> removed();
            case SHEAR -> entity(Action.ENTITY_SHEAR, random.nextInt(10) < 9 ? "minecraft:sheep"
                    : "minecraft:mooshroom", null);
            case NAME -> entity(Action.ENTITY_NAME, SyntheticContent.HANDLED_ENTITIES.pick(random),
                    new Payload.Text(SyntheticContent.words(random, 1 + random.nextInt(2))));
            case LEASH -> entity(Action.ENTITY_LEASH, SyntheticContent.HANDLED_ENTITIES.pick(random), null);
            case UNLEASH -> entity(Action.ENTITY_UNLEASH, SyntheticContent.HANDLED_ENTITIES.pick(random), null);
            case TAME -> entity(Action.ENTITY_TAME, random.nextBoolean() ? "minecraft:wolf" : "minecraft:cat", null);
            case DYE -> entity(Action.ENTITY_DYE, random.nextBoolean() ? "minecraft:sheep" : "minecraft:wolf",
                    new Payload.Text(SyntheticContent.colour(random)));
            case CHAT -> message(Action.CHAT, SyntheticContent.chat(random));
            case COMMAND -> message(Action.COMMAND, SyntheticContent.command(random,
                    players[random.nextInt(PLAYERS)].name()));
            case BOOK -> message(Action.BOOK_EDIT, SyntheticContent.book(random));
            case GAMEMODE -> message(Action.GAMEMODE_CHANGE, SyntheticContent.gameMode(random));
            case JOIN -> online.size() < MAX_ONLINE ? join() : quit();
            case QUIT -> online.size() > MIN_ONLINE ? quit() : join();
        };
    }

    /**
     * Events and payload bytes per action so far.
     *
     * @return one entry per action that occurred, in action order
     */
    public @NotNull List<ActionCount> counts() {
        return tallies.entrySet().stream().map(entry -> new ActionCount(entry.getKey(), entry.getValue().events,
                entry.getValue().payloads, entry.getValue().payloadBytes)).toList();
    }

    /**
     * The payload bytes of living mobs' entity bytes in kills so far (a kill of a mob, not an armour
     * stand or a player).
     *
     * @return the bytes
     */
    public long mobEntityBytes() {
        return mobEntityBytes;
    }

    /**
     * The time of the latest event.
     *
     * @return epoch milliseconds
     */
    public long now() {
        return millis;
    }

    private @NotNull PendingEvent playerBreak() {
        Online player = active(true);
        String subject = player.material(random);
        Payload payload = null;
        if (subject.startsWith("minecraft:chest") || subject.startsWith("minecraft:barrel")
                || subject.startsWith("minecraft:hopper")) {
            payload = random.nextInt(10) < 6 ? inventory() : null;
        } else if (subject.contains("_sign")) {
            payload = new Payload.Sign(signText(), SignSides.EMPTY);
        }
        return block(player, Action.BLOCK_BREAK, subject, AIR, payload);
    }

    private @NotNull PendingEvent playerPlace() {
        Online player = active(true);
        int roll = random.nextInt(20);
        String replaced = roll < 17 ? AIR : roll < 18 ? "minecraft:short_grass" : "minecraft:water[level=0]";
        return block(player, Action.BLOCK_PLACE, player.material(random), replaced, null);
    }

    private @NotNull PendingEvent explosion() {
        Online near = active(false);
        burstLeft = 8 + random.nextInt(33);
        burstActor = random.nextInt(10) < 6 ? CREEPER : players[near.id];
        burstCentre = new int[] {near.world, near.x + random.nextInt(-16, 17), near.y, near.z + random.nextInt(-16, 17)};
        return next();
    }

    private @NotNull PendingEvent naturalBlock(@NotNull Action action) {
        Online near = active(false);
        Actor actor = switch (action) {
            case BLOCK_BREAK -> random.nextInt(4) == 0 ? FIRE : random.nextBoolean() ? PHYSICS : PISTON;
            case BLOCK_PLACE -> PISTON;
            default -> random.nextBoolean() ? PHYSICS : NATURE;
        };
        SyntheticPalette palette = palette(near.world);
        return block(WORLDS[near.world], near.x + random.nextInt(-48, 49), near.y + random.nextInt(-8, 9),
                near.z + random.nextInt(-48, 49), actor, action, palette.pick(random),
                action == Action.BLOCK_PLACE ? AIR : palette.pick(random), null);
    }

    private @NotNull PendingEvent flow() {
        Online player = active(true);
        boolean lava = player.world == 1 || random.nextInt(5) == 0;
        Actor actor = random.nextInt(10) < 6 ? players[player.id] : lava ? LAVA : WATER;
        String fluid = (lava ? "minecraft:lava[level=" : "minecraft:water[level=") + (1 + random.nextInt(7)) + "]";
        return block(player, actor, Action.BLOCK_PLACE, fluid, AIR, null);
    }

    private @NotNull PendingEvent clicked(@NotNull Action action) {
        Online player = active(true);
        return block(player, action, SyntheticContent.CLICKED.pick(random), SyntheticContent.CLICKED.pick(random),
                null);
    }

    private @NotNull PendingEvent sign() {
        Online player = active(true);
        String state = random.nextBoolean() ? "minecraft:oak_sign[rotation=" + 4 * random.nextInt(4)
                + ",waterlogged=false]" : "minecraft:oak_wall_sign[facing=north,waterlogged=false]";
        SignSides before = random.nextInt(4) == 0 ? signText() : SignSides.EMPTY;
        return block(player, Action.SIGN_EDIT, state, state, new Payload.Sign(before, signText()));
    }

    private @NotNull PendingEvent grow() {
        Online owner = active(false);
        int[] home = homes[owner.id];
        return block(WORLDS[0], home[0] + random.nextInt(24), home[1], home[2] + random.nextInt(24), GROWTH,
                Action.BLOCK_GROW, SyntheticContent.GROWN.pick(random), null, null);
    }

    private @NotNull PendingEvent container(@NotNull Action action) {
        Online player = active(false);
        int[] home = homes[player.id];
        boolean atHome = random.nextInt(5) < 4;
        String item = SyntheticContent.ITEMS.pick(random);
        int amount = SyntheticContent.WITH_COMPONENTS.contains(item) ? 1
                : random.nextInt(5) < 2 ? 64 : 1 + random.nextInt(64);
        if (atHome) {
            return event(WORLDS[0], home[0] + random.nextInt(10), home[1], home[2] + 30 + random.nextInt(2),
                    players[player.id], action, item, null, amount, itemPayload(item));
        }
        return event(WORLDS[player.world], player.x, player.y, player.z, players[player.id], action, item, null,
                amount, itemPayload(item));
    }

    private @NotNull PendingEvent item(@NotNull Action action, @NotNull SyntheticPalette palette, int maxAmount) {
        Online player = active(false);
        String item = palette.pick(random);
        int amount = SyntheticContent.WITH_COMPONENTS.contains(item) || maxAmount == 1 ? 1
                : 1 + random.nextInt(maxAmount);
        Payload payload = action == Action.ITEM_BREAK ? new Payload.Item(gzipped(110, 180)) : itemPayload(item);
        return event(WORLDS[player.world], player.x, player.y, player.z, players[player.id], action, item, null,
                amount, payload);
    }

    private @NotNull PendingEvent kill() {
        Online near = active(false);
        String victim = SyntheticContent.KILLED.pick(random);
        Actor killer = random.nextInt(5) < 4 ? players[near.id] : KILL_CAUSES[random.nextInt(KILL_CAUSES.length)];
        Payload payload = null;
        if (!victim.equals("minecraft:player")) {
            byte[] bytes = switch (victim) {
                case "minecraft:villager" -> gzipped(640, 720);
                case "minecraft:armor_stand" -> gzipped(600, 640);
                default -> gzipped(520, 660);
            };
            payload = new Payload.Entity(bytes);
            if (!victim.equals("minecraft:armor_stand")) {
                mobEntityBytes += PayloadCodec.encode(payload).length;
            }
        }
        return event(WORLDS[near.world], near.x + random.nextInt(-6, 7), near.y, near.z + random.nextInt(-6, 7),
                killer, Action.ENTITY_KILL, victim, null, 0, payload);
    }

    private @NotNull PendingEvent removed() {
        String type = SyntheticContent.REMOVED_ENTITIES.pick(random);
        byte[] bytes = switch (type) {
            case "minecraft:oak_boat", "minecraft:minecart" -> gzipped(350, 375);
            case "minecraft:axolotl", "minecraft:cod" -> gzipped(520, 600);
            default -> gzipped(440, 470);
        };
        return entity(Action.ENTITY_REMOVE, type, new Payload.Entity(bytes));
    }

    private @NotNull PendingEvent entity(@NotNull Action action, @NotNull String type, @Nullable Payload payload) {
        Online player = active(false);
        return event(WORLDS[player.world], player.x + random.nextInt(-3, 4), player.y, player.z + random.nextInt(-3, 4),
                players[player.id], action, type, null, 0, payload);
    }

    private @NotNull PendingEvent message(@NotNull Action action, @NotNull String text) {
        Online player = active(false);
        return event(WORLDS[player.world], player.x, player.y, player.z, players[player.id], action, "", null, 0,
                new Payload.Text(text));
    }

    private @NotNull PendingEvent join() {
        int id = random.nextInt(PLAYERS);
        while (isOnline[id]) {
            id = random.nextInt(PLAYERS);
        }
        isOnline[id] = true;
        int[] home = homes[id];
        Online player = new Online(id, home[0], home[1] + 1, home[2]);
        online.add(player);
        return event(WORLDS[0], player.x, player.y, player.z, players[id], Action.JOIN, "", null, 0, null);
    }

    private @NotNull PendingEvent quit() {
        Online player = online.remove(random.nextInt(online.size()));
        isOnline[player.id] = false;
        Payload payload = random.nextInt(100) < 3 ? new Payload.Text(SyntheticContent.kick(random)) : null;
        return event(WORLDS[player.world], player.x, player.y, player.z, players[player.id], Action.QUIT, "", null,
                0, payload);
    }

    /** A random online player; one who builds or digs takes a step first. */
    private @NotNull Online active(boolean moves) {
        Online player = online.get(random.nextInt(online.size()));
        if (moves) {
            player.step(random, homes[player.id]);
        }
        return player;
    }

    private @NotNull PendingEvent block(@NotNull Online player, @NotNull Action action, @NotNull String subject,
            @Nullable String secondary, @Nullable Payload payload) {
        return block(player, players[player.id], action, subject, secondary, payload);
    }

    private @NotNull PendingEvent block(@NotNull Online player, @NotNull Actor actor, @NotNull Action action,
            @NotNull String subject, @Nullable String secondary, @Nullable Payload payload) {
        return block(WORLDS[player.world], player.x, player.y, player.z, actor, action, subject, secondary, payload);
    }

    private @NotNull PendingEvent block(@NotNull String world, int x, int y, int z, @NotNull Actor actor,
            @NotNull Action action, @NotNull String subject, @Nullable String secondary, @Nullable Payload payload) {
        return event(world, x, y, z, actor, action, subject, secondary, 0, payload);
    }

    private @NotNull PendingEvent event(@NotNull String world, int x, int y, int z, @NotNull Actor actor,
            @NotNull Action action, @NotNull String subject, @Nullable String secondary, int amount,
            @Nullable Payload payload) {
        byte[] data = payload == null ? null : PayloadCodec.encode(payload);
        Tally tally = tallies.computeIfAbsent(action, key -> new Tally());
        tally.events++;
        if (data != null) {
            tally.payloads++;
            tally.payloadBytes += data.length;
        }
        sequence = (sequence + 1) & EventIds.MAX_SEQUENCE;
        int clampedY = Math.clamp(y, -64, 319);
        return new PendingEvent(EventIds.stamp(millis, sequence), world, BlockPositions.pack(x, clampedY, z), actor,
                action, subject, secondary, amount, data);
    }

    private @Nullable Payload itemPayload(@NotNull String item) {
        if (item.equals("minecraft:shulker_box")) {
            return new Payload.Item(gzipped(300, 2_200));
        }
        if (item.equals("minecraft:written_book")) {
            return new Payload.Item(gzipped(150, 600));
        }
        if (SyntheticContent.WITH_COMPONENTS.contains(item) || random.nextInt(100) < 3) {
            return new Payload.Item(gzipped(110, 210));
        }
        return null;
    }

    /** A broken container's contents: up to a full chest, a quarter of the stacks with components. */
    private @NotNull Payload inventory() {
        int slots = 1 + random.nextInt(27);
        List<Payload.Slot> contents = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            contents.add(new Payload.Slot(i, random.nextInt(4) == 0 ? gzipped(110, 210) : gzipped(74, 80)));
        }
        return new Payload.Inventory(contents);
    }

    private @NotNull SignSides signText() {
        List<String> lines = new ArrayList<>(4);
        int count = 1 + random.nextInt(4);
        for (int i = 0; i < count; i++) {
            lines.add(SyntheticContent.words(random, 1 + random.nextInt(3)));
        }
        return new SignSides(lines, List.of());
    }

    /** Bytes as incompressible as a gzip stream of Paper's NBT, with its header. */
    private byte @NotNull [] gzipped(int min, int max) {
        byte[] bytes = new byte[min + random.nextInt(max - min + 1)];
        for (int i = GZIP_HEADER.length; i < bytes.length; i += 8) {
            long value = random.nextLong();
            for (int j = 0; j < 8 && i + j < bytes.length; j++) {
                bytes[i + j] = (byte) (value >>> (8 * j));
            }
        }
        System.arraycopy(GZIP_HEADER, 0, bytes, 0, GZIP_HEADER.length);
        return bytes;
    }

    private @NotNull String name(int index) {
        String[] parts = {"Steve", "Alex", "Craft", "Mine", "Block", "Dragon", "Shadow", "Pixel", "Ender", "Nova",
            "Frost", "Blaze", "Wolf", "Sky", "Iron", "Gold"};
        return parts[random.nextInt(parts.length)] + parts[random.nextInt(parts.length)] + (index % 1000);
    }

    private static @NotNull SyntheticPalette palette(int world) {
        return switch (world) {
            case 1 -> SyntheticContent.NETHER;
            case 2 -> SyntheticContent.END;
            default -> SyntheticContent.OVERWORLD;
        };
    }

    /**
     * Events of one action.
     *
     * @param action       the action
     * @param events       how many
     * @param payloads     how many carried a payload
     * @param payloadBytes the encoded payload bytes of all of them
     */
    public record ActionCount(@NotNull Action action, long events, long payloads, long payloadBytes) {
    }

    /** What the server does next, by weight out of 10,000 (an explosion is a burst of 8 to 40 breaks). */
    private enum Kind {
        BREAK(2_700), EXPLOSION(8), NATURAL_BREAK(70), PLACE(2_500), FLOW(200), PISTON_PLACE(40), CHANGE(350),
        NATURAL_CHANGE(100), SIGN(15), INTERACT(500), GROW(700), DEPOSIT(500), WITHDRAW(500), DROP(220),
        PICKUP(300), CRAFT(120), THROW(100), ITEM_BREAK(8), TRADE(30), KILL(300), SPAWN(25), REMOVE(15), SHEAR(6),
        NAME(2), LEASH(4), UNLEASH(4), TAME(3), DYE(3), CHAT(250), COMMAND(180), BOOK(2), GAMEMODE(3), JOIN(20),
        QUIT(20);

        private static final Kind[] VALUES = values();
        private static final int[] CUMULATIVE = new int[VALUES.length];

        static {
            int total = 0;
            for (int i = 0; i < VALUES.length; i++) {
                total += VALUES[i].weight;
                CUMULATIVE[i] = total;
            }
        }

        private final int weight;

        Kind(int weight) {
            this.weight = weight;
        }

        private static @NotNull Kind pick(@NotNull SplittableRandom random) {
            int ticket = random.nextInt(CUMULATIVE[CUMULATIVE.length - 1]);
            for (int i = 0; i < CUMULATIVE.length; i++) {
                if (ticket < CUMULATIVE[i]) {
                    return VALUES[i];
                }
            }
            throw new IllegalStateException("weights do not cover " + ticket);
        }
    }

    /** Counts of one action. */
    private static final class Tally {
        private long events;
        private long payloads;
        private long payloadBytes;
    }

    /** An online player: where they are and what they are building with. */
    private static final class Online {

        private final int id;
        private int world;
        private int x;
        private int y;
        private int z;
        private String material;

        private Online(int id, int x, int y, int z) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** Mostly a block or two from the last spot; now and then a walk, a teleport or a portal. */
        private void step(@NotNull SplittableRandom random, int @NotNull [] home) {
            int roll = random.nextInt(1_000);
            if (roll < 2) {
                world = random.nextInt(100) < 75 ? 0 : random.nextInt(100) < 70 ? 1 : 2;
                int range = world == 1 ? 800 : world == 2 ? 1_500 : 6_000;
                boolean goHome = world == 0 && random.nextBoolean();
                x = goHome ? home[0] : random.nextInt(-range, range);
                z = goHome ? home[2] : random.nextInt(-range, range);
                y = world == 1 ? 30 + random.nextInt(70) : world == 2 ? 50 + random.nextInt(20) : 10 + random.nextInt(70);
                material = null;
            } else if (roll < 30) {
                x += random.nextInt(-40, 41);
                z += random.nextInt(-40, 41);
                y = Math.clamp(y + random.nextInt(-6, 7), world == 0 ? -60 : 1, world == 1 ? 120 : 200);
            } else {
                x += random.nextInt(-2, 3);
                y = Math.clamp(y + random.nextInt(-1, 2), world == 0 ? -60 : 1, world == 1 ? 120 : 200);
                z += random.nextInt(-2, 3);
            }
        }

        /** The block in hand or under the pick: kept for a while, as players mine and build in runs. */
        private @NotNull String material(@NotNull SplittableRandom random) {
            if (material == null || random.nextInt(100) < 15) {
                material = palette(world).pick(random);
            }
            return material;
        }
    }
}
