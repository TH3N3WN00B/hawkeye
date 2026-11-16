package gg.moonrise.hawkeye.core.storage;

import java.util.Set;
import java.util.SplittableRandom;
import org.jetbrains.annotations.NotNull;

/**
 * What the synthetic server's players touch: block states per dimension, items, entity types, and
 * the words of chat, commands, signs and books. Weights follow a busy survival server: mining and
 * building blocks dominate, then farms, storage, redstone and decoration.
 */
public final class SyntheticContent {

    private static final String DIRECTIONS = "east|north|south|west";
    private static final String BOOLEAN = "false|true";

    /** Overworld blocks, by how often players break or place them. */
    public static final SyntheticPalette OVERWORLD = SyntheticPalette.builder()
            .add("stone", 300).add("deepslate", 110, "axis=y").add("dirt", 150).add("cobblestone", 120)
            .add("cobbled_deepslate", 60).add("andesite", 40).add("diorite", 35).add("granite", 35).add("tuff", 30)
            .add("gravel", 30).add("sand", 30).add("sandstone", 15).add("grass_block", 60, "snowy=false")
            .add("short_grass", 30).add("tall_grass", 8, "half=lower|upper").add("dandelion", 4).add("poppy", 4)
            .add("oak_planks", 50).add("spruce_planks", 35).add("birch_planks", 15).add("dark_oak_planks", 10)
            .add("glass", 30).add("white_concrete", 15).add("gray_concrete", 10).add("stone_bricks", 25)
            .add("bricks", 10).add("smooth_stone", 8).add("terracotta", 6).add("mud", 3).add("clay", 3)
            .add("moss_block", 3).add("coal_ore", 10).add("iron_ore", 10).add("deepslate_iron_ore", 8)
            .add("copper_ore", 6).add("redstone_ore", 5, "lit=false|true").add("diamond_ore", 1)
            .add("deepslate_diamond_ore", 2).add("obsidian", 5).add("crafting_table", 4)
            .add("furnace", 4, "facing=" + DIRECTIONS, "lit=" + BOOLEAN).add("iron_block", 2).add("gold_block", 1)
            .add("oak_log", 40, "axis=x|y|z").add("spruce_log", 30, "axis=x|y|z").add("birch_log", 15, "axis=x|y|z")
            .add("dark_oak_log", 8, "axis=x|y|z")
            .add("oak_leaves", 40, "distance=1|2|3|4|5|6|7", "persistent=" + BOOLEAN, "waterlogged=false")
            .add("spruce_leaves", 25, "distance=1|2|3|4|5|6|7", "persistent=" + BOOLEAN, "waterlogged=false")
            .add("oak_stairs", 25, "facing=" + DIRECTIONS, "half=bottom|top",
                    "shape=inner_left|inner_right|outer_left|outer_right|straight", "waterlogged=false")
            .add("stone_brick_stairs", 15, "facing=" + DIRECTIONS, "half=bottom|top", "shape=straight",
                    "waterlogged=false")
            .add("oak_slab", 20, "type=bottom|double|top", "waterlogged=false")
            .add("smooth_stone_slab", 10, "type=bottom|double|top", "waterlogged=false")
            .add("torch", 25).add("wall_torch", 25, "facing=" + DIRECTIONS)
            .add("lantern", 8, "hanging=" + BOOLEAN, "waterlogged=false")
            .add("oak_door", 10, "facing=" + DIRECTIONS, "half=lower|upper", "hinge=left|right", "open=" + BOOLEAN,
                    "powered=false")
            .add("oak_trapdoor", 8, "facing=" + DIRECTIONS, "half=bottom|top", "open=" + BOOLEAN, "powered=false",
                    "waterlogged=false")
            .add("oak_fence", 12, "east=" + BOOLEAN, "north=" + BOOLEAN, "south=" + BOOLEAN, "waterlogged=false",
                    "west=" + BOOLEAN)
            .add("glass_pane", 10, "east=" + BOOLEAN, "north=" + BOOLEAN, "south=" + BOOLEAN, "waterlogged=false",
                    "west=" + BOOLEAN)
            .add("chest", 12, "facing=" + DIRECTIONS, "type=left|right|single", "waterlogged=false")
            .add("barrel", 6, "facing=down|east|north|south|up|west", "open=" + BOOLEAN)
            .add("hopper", 5, "enabled=true", "facing=down|east|north|south|west")
            .add("redstone_wire", 8, "east=none|side|up", "north=none|side|up", "power=0|1|2|3|4|5|6|7|8|9|10|11|12|13|14|15",
                    "south=none|side|up", "west=none|side|up")
            .add("repeater", 3, "delay=1|2|3|4", "facing=" + DIRECTIONS, "locked=false", "powered=" + BOOLEAN)
            .add("lever", 3, "face=ceiling|floor|wall", "facing=" + DIRECTIONS, "powered=" + BOOLEAN)
            .add("stone_button", 4, "face=ceiling|floor|wall", "facing=" + DIRECTIONS, "powered=" + BOOLEAN)
            .add("piston", 2, "extended=" + BOOLEAN, "facing=down|east|north|south|up|west")
            .add("rail", 5, "shape=ascending_east|east_west|north_east|north_south|north_west|south_east|south_west",
                    "waterlogged=false")
            .add("farmland", 15, "moisture=0|1|2|3|4|5|6|7").add("wheat", 30, "age=0|1|2|3|4|5|6|7")
            .add("carrots", 15, "age=0|1|2|3|4|5|6|7").add("potatoes", 15, "age=0|1|2|3|4|5|6|7")
            .add("sugar_cane", 10, "age=0").add("melon", 5).add("pumpkin", 4)
            .add("water", 40, "level=0|1|2|3|4|5|6|7").add("lava", 10, "level=0|1|2|3|4|5|6|7")
            .add("snow", 5, "layers=1|2|3|4|5|6|7|8").add("ladder", 6, "facing=" + DIRECTIONS, "waterlogged=false")
            .add("scaffolding", 4, "bottom=" + BOOLEAN, "distance=0|1|2|3|4|5|6|7", "waterlogged=false")
            .add("red_bed", 2, "facing=" + DIRECTIONS, "occupied=false", "part=foot|head")
            .add("oak_sign", 3, "rotation=0|4|8|12", "waterlogged=false")
            .add("oak_wall_sign", 3, "facing=" + DIRECTIONS, "waterlogged=false")
            .build();

    /** Nether blocks. */
    public static final SyntheticPalette NETHER = SyntheticPalette.builder()
            .add("netherrack", 300).add("blackstone", 40).add("basalt", 40, "axis=x|y|z").add("soul_sand", 25)
            .add("soul_soil", 15).add("magma_block", 10).add("glowstone", 10).add("nether_quartz_ore", 15)
            .add("nether_gold_ore", 10).add("ancient_debris", 1).add("nether_bricks", 15).add("obsidian", 8)
            .add("crimson_nylium", 10).add("warped_nylium", 10).add("lava", 20, "level=0|1|2|3|4|5|6|7")
            .add("torch", 10).add("cobblestone", 20)
            .build();

    /** End blocks. */
    public static final SyntheticPalette END = SyntheticPalette.builder()
            .add("end_stone", 300).add("purpur_block", 40).add("purpur_pillar", 10, "axis=x|y|z")
            .add("end_stone_bricks", 15).add("chorus_plant", 30, "down=" + BOOLEAN, "east=" + BOOLEAN,
                    "north=" + BOOLEAN, "south=" + BOOLEAN, "up=" + BOOLEAN, "west=" + BOOLEAN)
            .add("obsidian", 10).add("end_rod", 5, "facing=down|east|north|south|up|west").add("cobblestone", 10)
            .build();

    /** Blocks players click without breaking them. */
    public static final SyntheticPalette CLICKED = SyntheticPalette.builder()
            .add("oak_door", 40, "facing=" + DIRECTIONS, "half=lower|upper", "hinge=left|right", "open=" + BOOLEAN,
                    "powered=false")
            .add("oak_trapdoor", 25, "facing=" + DIRECTIONS, "half=bottom|top", "open=" + BOOLEAN, "powered=false",
                    "waterlogged=false")
            .add("lever", 15, "face=ceiling|floor|wall", "facing=" + DIRECTIONS, "powered=" + BOOLEAN)
            .add("stone_button", 20, "face=ceiling|floor|wall", "facing=" + DIRECTIONS, "powered=" + BOOLEAN)
            .add("oak_fence_gate", 15, "facing=" + DIRECTIONS, "in_wall=false", "open=" + BOOLEAN, "powered=false")
            .add("repeater", 5, "delay=1|2|3|4", "facing=" + DIRECTIONS, "locked=false", "powered=" + BOOLEAN)
            .add("note_block", 3, "instrument=harp|basedrum|bell", "note=0|5|10|15|20", "powered=false")
            .build();

    /** Ripe crops and newly grown blocks (the compact crop growth). */
    public static final SyntheticPalette GROWN = SyntheticPalette.builder()
            .add("wheat", 40, "age=7").add("carrots", 20, "age=7").add("potatoes", 20, "age=7")
            .add("beetroots", 8, "age=3").add("sugar_cane", 25, "age=0").add("melon", 10).add("pumpkin", 10)
            .add("cactus", 6, "age=0").add("bamboo", 8, "age=0|1", "leaves=large|none|small", "stage=0")
            .add("sweet_berry_bush", 6, "age=3").add("nether_wart", 6, "age=3").add("cocoa", 3,
                    "age=2", "facing=" + DIRECTIONS)
            .add("kelp", 8, "age=0|5|10|15|20|25").add("vine", 4, "east=" + BOOLEAN, "north=" + BOOLEAN,
                    "south=" + BOOLEAN, "up=false", "west=" + BOOLEAN)
            .build();

    /** Items moved through containers, dropped, picked up or crafted. */
    public static final SyntheticPalette ITEMS = SyntheticPalette.builder()
            .add("cobblestone", 100).add("cobbled_deepslate", 60).add("dirt", 70).add("stone", 50).add("oak_log", 40)
            .add("oak_planks", 40).add("spruce_log", 20).add("torch", 30).add("coal", 30).add("iron_ingot", 25)
            .add("raw_iron", 25).add("gold_ingot", 8).add("diamond", 6).add("redstone", 15).add("lapis_lazuli", 6)
            .add("emerald", 6).add("wheat", 25).add("wheat_seeds", 25).add("bread", 15).add("carrot", 15)
            .add("potato", 15).add("baked_potato", 8).add("cooked_beef", 15).add("rotten_flesh", 30).add("bone", 25)
            .add("string", 20).add("gunpowder", 20).add("arrow", 25).add("ender_pearl", 8).add("glass", 15)
            .add("sand", 20).add("gravel", 15).add("flint", 8).add("sugar_cane", 15).add("paper", 8)
            .add("leather", 6).add("white_wool", 8).add("oak_sapling", 10).add("netherrack", 20).add("quartz", 6)
            .add("glowstone_dust", 4).add("blaze_rod", 3).add("obsidian", 4).add("bucket", 4).add("water_bucket", 3)
            .add("golden_carrot", 6).add("melon_slice", 8).add("pumpkin", 6).add("kelp", 6).add("bamboo", 10)
            .add("copper_ingot", 8).add("raw_copper", 8).add("amethyst_shard", 3).add("experience_bottle", 2)
            .add("firework_rocket", 6).add("iron_pickaxe", 3).add("diamond_pickaxe", 2).add("netherite_pickaxe", 1)
            .add("diamond_sword", 2).add("netherite_sword", 1).add("bow", 2).add("shield", 2)
            .add("diamond_chestplate", 1).add("elytra", 1).add("enchanted_book", 2).add("potion", 2)
            .add("shulker_box", 1).add("written_book", 1).add("name_tag", 1)
            .build();

    /** Items that always carry components, so a payload (enchantments, damage, potion, contents). */
    public static final Set<String> WITH_COMPONENTS = Set.of("minecraft:iron_pickaxe", "minecraft:diamond_pickaxe",
            "minecraft:netherite_pickaxe", "minecraft:diamond_sword", "minecraft:netherite_sword", "minecraft:bow",
            "minecraft:shield", "minecraft:diamond_chestplate", "minecraft:elytra", "minecraft:enchanted_book",
            "minecraft:potion", "minecraft:shulker_box", "minecraft:written_book", "minecraft:firework_rocket",
            "minecraft:splash_potion", "minecraft:trident");

    /** Thrown or shot items. */
    public static final SyntheticPalette THROWN = SyntheticPalette.builder()
            .add("arrow", 50).add("snowball", 15).add("egg", 10).add("ender_pearl", 15).add("splash_potion", 4)
            .add("trident", 2).add("experience_bottle", 4)
            .build();

    /** Tools and armour that wear out. */
    public static final SyntheticPalette WORN_OUT = SyntheticPalette.builder()
            .add("iron_pickaxe", 30).add("diamond_pickaxe", 15).add("stone_pickaxe", 20).add("iron_shovel", 10)
            .add("iron_axe", 10).add("bow", 8).add("fishing_rod", 8).add("shears", 5).add("flint_and_steel", 4)
            .add("iron_boots", 3).add("elytra", 1)
            .build();

    /** What villagers sell. */
    public static final SyntheticPalette TRADED = SyntheticPalette.builder()
            .add("emerald", 60).add("enchanted_book", 15).add("bread", 8).add("bookshelf", 4).add("glass", 4)
            .add("diamond_pickaxe", 3).add("iron_chestplate", 3).add("redstone", 3).add("arrow", 4)
            .build();

    /** Entities killed. */
    public static final SyntheticPalette KILLED = SyntheticPalette.builder()
            .add("zombie", 120).add("skeleton", 100).add("creeper", 60).add("spider", 60).add("enderman", 40)
            .add("cow", 50).add("pig", 40).add("chicken", 50).add("sheep", 40).add("drowned", 20).add("husk", 10)
            .add("stray", 10).add("witch", 8).add("slime", 15).add("magma_cube", 10).add("zombified_piglin", 60)
            .add("piglin", 15).add("blaze", 15).add("wither_skeleton", 10).add("ghast", 5).add("iron_golem", 5)
            .add("villager", 5).add("phantom", 10).add("squid", 10).add("cod", 10).add("salmon", 8).add("bat", 5)
            .add("horse", 3).add("wolf", 3).add("fox", 3).add("bee", 3).add("guardian", 10).add("shulker", 3)
            .add("armor_stand", 2).add("player", 15)
            .build();

    /** Entities placed or spawned by players. */
    public static final SyntheticPalette PLACED_ENTITIES = SyntheticPalette.builder()
            .add("armor_stand", 20).add("oak_boat", 15).add("minecart", 15).add("item_frame", 25).add("painting", 10)
            .add("end_crystal", 3).add("glow_item_frame", 5).add("cow", 3).add("iron_golem", 2).add("axolotl", 2)
            .build();

    /** Non-living entities destroyed or taken. */
    public static final SyntheticPalette REMOVED_ENTITIES = SyntheticPalette.builder()
            .add("oak_boat", 20).add("minecart", 20).add("item_frame", 30).add("painting", 15)
            .add("glow_item_frame", 5).add("end_crystal", 5).add("axolotl", 3).add("cod", 2)
            .build();

    /** Entities players shear, name, leash, tame or dye. */
    public static final SyntheticPalette HANDLED_ENTITIES = SyntheticPalette.builder()
            .add("sheep", 40).add("wolf", 20).add("cat", 10).add("horse", 15).add("cow", 5).add("villager", 5)
            .add("mooshroom", 2).add("parrot", 3)
            .build();

    private static final String[] WORDS = ("the a to and i you it is in that of for on my this we have be can "
            + "with just what so are at get do not but no yes lol ok go here there now come wait who where how "
            + "base home farm iron gold diamond netherite build house tower wall bridge portal nether end spawn "
            + "shop sell buy price stack emerald trade villager mob grinder raid creeper zombie skeleton tnt "
            + "anyone want need help please thanks ty gg nice cool wow omg brb afk back later night day server "
            + "lag tps restart update plugin claim trust rollback grief griefed who broke my chest stole items "
            + "tp me pls give coords x y z north south east west mine cave chunk elytra rockets beacon enchant "
            + "mending unbreaking sharpness looting fortune efficiency xp level books shulker box ender pearl "
            + "boat minecart rail station redstone piston hopper sorter storage system tunnel highway").split(" ");

    private static final String[] COMMANDS = {"/home", "/home base", "/sethome base", "/tpa %s", "/tpaccept",
        "/msg %s %w", "/r %w", "/spawn", "/back", "/warp shop", "/warp farms", "/ah", "/ah sell 64",
        "/pay %s 250", "/balance", "/kit daily", "/rtp", "/sell hand", "/claim", "/trust %s", "/hawkeye i",
        "/hawkeye lookup u:%s t:1h r:20", "/vote", "/discord", "/list"};

    private static final String[] COLOURS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
        "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    private static final String[] KICKS = {"kicked: Timed out", "kicked: You logged in from another location",
        "kicked: Spamming is not allowed", "kicked: Server restarting", "kicked: Flying is not enabled on this server",
        "kicked: You are banned from this server. Reason: Griefing. Appeal at discord.gg/example"};

    private static final String[] GAMEMODES = {"creative (command)", "survival (command)", "spectator (command)",
        "survival (plugin)"};

    private SyntheticContent() {
    }

    /**
     * A chat message.
     *
     * @param random the random source
     * @return a few words
     */
    public static @NotNull String chat(@NotNull SplittableRandom random) {
        return words(random, 2 + random.nextInt(16));
    }

    /**
     * A command line.
     *
     * @param random the random source
     * @param player a player name for commands that name one
     * @return the command
     */
    public static @NotNull String command(@NotNull SplittableRandom random, @NotNull String player) {
        return COMMANDS[random.nextInt(COMMANDS.length)].replace("%s", player)
                .replace("%w", words(random, 2 + random.nextInt(8)));
    }

    /**
     * The text of a written book, as Hawkeye records it.
     *
     * @param random the random source
     * @return the pages
     */
    public static @NotNull String book(@NotNull SplittableRandom random) {
        return "title: " + words(random, 2) + " | " + words(random, 30 + random.nextInt(270));
    }

    /**
     * A kick reason, as a quit's text.
     *
     * @param random the random source
     * @return the text
     */
    public static @NotNull String kick(@NotNull SplittableRandom random) {
        return KICKS[random.nextInt(KICKS.length)];
    }

    /**
     * A game mode change, as Hawkeye records it.
     *
     * @param random the random source
     * @return the text
     */
    public static @NotNull String gameMode(@NotNull SplittableRandom random) {
        return GAMEMODES[random.nextInt(GAMEMODES.length)];
    }

    /**
     * A dye colour.
     *
     * @param random the random source
     * @return the colour
     */
    public static @NotNull String colour(@NotNull SplittableRandom random) {
        return COLOURS[random.nextInt(COLOURS.length)];
    }

    /**
     * Words drawn with a skew towards the common ones, as in real chat.
     *
     * @param random the random source
     * @param count  how many
     * @return the words separated by spaces
     */
    public static @NotNull String words(@NotNull SplittableRandom random, int count) {
        StringBuilder text = new StringBuilder(count * 6);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                text.append(' ');
            }
            // The square of a uniform number favours the front of the list.
            double skew = random.nextDouble();
            text.append(WORDS[(int) (skew * skew * WORDS.length)]);
        }
        return text.toString();
    }
}
