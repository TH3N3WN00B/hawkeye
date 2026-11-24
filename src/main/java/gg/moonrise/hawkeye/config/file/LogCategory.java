package gg.moonrise.hawkeye.config.file;

/** A switch in the {@code logging} section: which listener output is recorded. */
public enum LogCategory {

    /** Players breaking blocks. */
    BLOCK_BREAK,
    /** Players placing blocks. */
    BLOCK_PLACE,
    /** Blocks destroyed by TNT, creepers, beds, anchors and other explosions. */
    EXPLOSIONS,
    /** Fire spreading and burning blocks, and blocks being lit. */
    FIRE,
    /** Water and lava flowing. */
    FLUIDS,
    /** Pistons moving blocks. */
    PISTONS,
    /** Natural changes: leaf decay, growth, snow and ice forming, melting, spreading. */
    NATURAL,
    /** Buckets filled and emptied. */
    BUCKETS,
    /** Sign text. */
    SIGNS,
    /** Doors, levers, buttons, repeaters and other block uses. */
    INTERACTIONS,
    /** Mobs changing blocks: endermen, falling blocks, trampled farmland, eaten grass, cracked turtle eggs. */
    ENTITY_CHANGES,
    /** Items put into or taken from containers by players. */
    CONTAINERS,
    /** Hoppers, droppers and other automatic container transfers. */
    HOPPERS,
    /** Items dropped. */
    ITEM_DROPS,
    /** Items picked up. */
    ITEM_PICKUPS,
    /** Entities and players killed. */
    KILLS,
    /** Chat messages. */
    CHAT,
    /** Commands. */
    COMMANDS,
    /** Joins and quits (and kicks). */
    SESSIONS,
    /** Crops and plants growing (ripe crops and newly grown blocks; see {@code crop-growth-stages}). */
    CROP_GROWTH,
    /** Blocks and liquids placed or picked up by dispensers. */
    DISPENSERS,
    /** Entities placed, spawned, destroyed or taken by players and other causes. */
    ENTITIES,
    /** Players shearing, naming, leashing, taming and dyeing entities. */
    ENTITY_INTERACTIONS,
    /** Items crafted. */
    CRAFTING,
    /** Items thrown or shot, and tools that broke. */
    ITEM_USE,
    /** Villager and wandering trader trades. */
    TRADES,
    /** Books written and signed. */
    BOOKS,
    /** Game mode changes. */
    GAMEMODES,
    /** Commands run from the server console or the remote console (RCON). */
    CONSOLE_COMMANDS,
    /** A copy of a player's whole inventory when they join. */
    JOIN_SNAPSHOTS,
    /** A copy of a player's whole inventory when they leave. */
    QUIT_SNAPSHOTS,
    /** A copy of a player's whole inventory when they teleport or change worlds. */
    TELEPORT_SNAPSHOTS,
    /** A copy of the whole inventory a player died with. */
    DEATH_SNAPSHOTS
}
