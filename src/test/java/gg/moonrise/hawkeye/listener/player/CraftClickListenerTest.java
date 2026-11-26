package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock;
import org.mockbukkit.mockbukkit.inventory.WorkbenchInventoryMock;

/**
 * The fallback for servers before Paper 1.21.11. MockBukkit has Paper's crafted-item event, so the plugin
 * registers {@code CraftListener} instead; the fallback is built here and called directly.
 */
public class CraftClickListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private PlayerMock player;
    private CraftClickListener listener;
    private WorkbenchInventoryMock grid;
    private CraftingView view;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        World world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
        player.teleport(new Location(world, 8, 72, -4));
        listener = new CraftClickListener(TestPlugins.bean(EventRecorder.class));
        grid = new WorkbenchInventoryMock(null);
        grid.setResult(planks());
        view = new CraftingView(player, grid);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aClickTakesOneCraftsResultWhereThePlayerStands() {
        logs(3);

        click(ClickType.LEFT, InventoryAction.PICKUP_ALL);

        StoredEvent event = Recorded.only(Action.ITEM_CRAFT);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:oak_planks", event.subject());
        assertEquals(4, event.amount());
        assertEquals(8, event.x());
        assertEquals(72, event.y());
        assertEquals(-4, event.z());
    }

    @Test
    public void aClickWhoseResultDoesNotFitOnTheCursorCraftsNothing() {
        logs(3);
        view.setCursor(new ItemStack(Material.OAK_PLANKS, 62));

        click(ClickType.LEFT, InventoryAction.PICKUP_ALL);

        assertTrue(Recorded.events(Action.ITEM_CRAFT).isEmpty());
    }

    @Test
    public void aShiftClickCraftsWhileEveryIngredientLasts() {
        grid.setMatrix(new ItemStack[] {new ItemStack(Material.OAK_LOG, 5), null, null, null,
            new ItemStack(Material.STICK, 2), null, null, null, null});

        click(ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);

        assertEquals(8, Recorded.only(Action.ITEM_CRAFT).amount());
    }

    @Test
    public void aShiftClickStopsWhenTheInventoryIsFull() {
        logs(10);
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        player.getInventory().setItem(3, new ItemStack(Material.OAK_PLANKS, 55));

        click(ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);

        assertEquals(8, Recorded.only(Action.ITEM_CRAFT).amount());
    }

    @Test
    public void aNumberKeyCraftsOnlyIntoAnEmptyHotbarSlot() {
        logs(3);
        player.getInventory().setItem(0, new ItemStack(Material.STONE));

        listener.onCraft(new CraftItemEvent(recipe(), view, InventoryType.SlotType.RESULT, 0, ClickType.NUMBER_KEY,
                InventoryAction.HOTBAR_SWAP, 0));
        listener.onCraft(new CraftItemEvent(recipe(), view, InventoryType.SlotType.RESULT, 0, ClickType.NUMBER_KEY,
                InventoryAction.HOTBAR_SWAP, 1));

        assertEquals(4, Recorded.only(Action.ITEM_CRAFT).amount());
    }

    @Test
    public void aClickTheServerIgnoresOrAnEmptyResultCraftsNothing() {
        logs(3);
        click(ClickType.LEFT, InventoryAction.NOTHING);
        click(ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);
        grid.setResult(null);
        click(ClickType.LEFT, InventoryAction.PICKUP_ALL);

        assertTrue(Recorded.events(Action.ITEM_CRAFT).isEmpty());
    }

    @Test
    public void aDisabledCategoryRecordsNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  crafting: false\n");
        TestPlugins.reload();
        logs(3);

        click(ClickType.LEFT, InventoryAction.PICKUP_ALL);

        assertTrue(Recorded.events(Action.ITEM_CRAFT).isEmpty());
    }

    private void logs(int count) {
        grid.setMatrix(new ItemStack[] {new ItemStack(Material.OAK_LOG, count), null, null, null, null, null, null,
            null, null});
    }

    private void click(@NotNull ClickType click, @NotNull InventoryAction action) {
        listener.onCraft(new CraftItemEvent(recipe(), view, InventoryType.SlotType.RESULT, 0, click, action));
    }

    private static @NotNull ShapelessRecipe recipe() {
        return new ShapelessRecipe(new NamespacedKey("hawkeye", "test_planks"), planks()).addIngredient(Material.OAK_LOG);
    }

    private static @NotNull ItemStack planks() {
        return new ItemStack(Material.OAK_PLANKS, 4);
    }

    /** MockBukkit's view does not convert raw slots, which the click event asks for; the result slot is 0 both ways. */
    private static final class CraftingView extends SimpleInventoryViewMock {

        private CraftingView(@NotNull PlayerMock player, @NotNull WorkbenchInventoryMock grid) {
            super(player, grid, player.getInventory(), InventoryType.WORKBENCH);
        }

        @Override
        public int convertSlot(int rawSlot) {
            return rawSlot;
        }
    }
}
