package com.xinghan.itemsearch.probe;

import com.xinghan.itemsearch.InventoryTransferView;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Runs against the server's genuine NMS classes; the test player is never added to any world/player list. */
public final class InventoryTransferViewProbe {
    private InventoryTransferViewProbe() { }

    public static void run(JavaPlugin plugin) throws Exception {
        check(Bukkit.isPrimaryThread(), "native transfer probe is on the server thread");
        ClassLoader loader = Bukkit.getServer().getClass().getClassLoader();
        Class<?> minecraftServer = Class.forName("net.minecraft.server.MinecraftServer", false, loader);
        Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel", false, loader);
        Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer", false, loader);
        Class<?> profileType = Class.forName("com.mojang.authlib.GameProfile", false, loader);
        Class<?> informationType = Class.forName("net.minecraft.server.level.ClientInformation", false, loader);
        Class<?> playerType = Class.forName("net.minecraft.world.entity.player.Player", false, loader);
        Class<?> inventoryType = Class.forName("net.minecraft.world.entity.player.Inventory", false, loader);
        Class<?> containerType = Class.forName("net.minecraft.world.Container", false, loader);
        Class<?> chestMenu = Class.forName("net.minecraft.world.inventory.ChestMenu", false, loader);
        Class<?> craftContainer = Class.forName("org.bukkit.craftbukkit.inventory.CraftContainer", false, loader);
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object level = Bukkit.getWorlds().getFirst().getClass().getMethod("getHandle").invoke(Bukkit.getWorlds().getFirst());
        Object profile = profileType.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), "TransferProbe");
        Object information = informationType.getMethod("createDefault").invoke(null);
        Object handle = serverPlayer.getConstructor(minecraftServer, serverLevel, profileType, informationType)
                .newInstance(server, level, profile, information);
        Player actor = (Player) serverPlayer.getMethod("getBukkitEntity").invoke(handle);
        Object real = playerType.getMethod("getInventory").invoke(handle);
        Field currentMenu = playerType.getField("containerMenu");
        Object previous = currentMenu.get(handle);
        try {
            for (boolean custom : List.of(false, true)) {
                actor.getInventory().clear();
                actor.getInventory().setItem(0, new ItemStack(Material.STONE, 16));
                actor.getInventory().setItem(9, new ItemStack(Material.EMERALD, 3));
                Inventory top = Bukkit.createInventory(null, 54, "Transfer native probe");
                Object topContainer = top.getClass().getMethod("getInventory").invoke(top);
                Object menu = custom ? craftContainer.getConstructor(Inventory.class, playerType, int.class).newInstance(top, handle, 102)
                        : chestMenu.getMethod("sixRows", int.class, inventoryType, containerType).invoke(null, 101, real, topContainer);
                Class<?> component = Class.forName("net.minecraft.network.chat.Component", false, loader);
                menu.getClass().getMethod("setTitle", component).invoke(menu, component.getMethod("literal", String.class).invoke(null, "Transfer native probe"));
                currentMenu.set(handle, menu);
                verify(plugin, actor, handle, menu, top, real, custom);
            }
        } finally {
            Object menu = currentMenu.get(handle);
            Class<?> itemType = Class.forName("net.minecraft.world.item.ItemStack", false, loader);
            menu.getClass().getMethod("setCarried", itemType).invoke(menu, itemType.getField("EMPTY").get(null));
            currentMenu.set(handle, previous);
        }
    }

    private static void verify(JavaPlugin plugin, Player actor, Object handle, Object menu, Inventory top, Object real, boolean custom)
            throws Exception {
        ClassLoader loader = menu.getClass().getClassLoader();
        Class<?> slotType = Class.forName("net.minecraft.world.inventory.Slot", false, loader);
        Class<?> playerType = Class.forName("net.minecraft.world.entity.player.Player", false, loader);
        Class<?> clickType = Class.forName("net.minecraft.world.inventory.ClickType", false, loader);
        Class<?> itemType = Class.forName("net.minecraft.world.item.ItemStack", false, loader);
        @SuppressWarnings("unchecked") Class<? extends Enum> clickEnum = (Class<? extends Enum>) clickType;
        @SuppressWarnings({"unchecked", "rawtypes"}) Object pickup = Enum.valueOf(clickEnum, "PICKUP");
        @SuppressWarnings({"unchecked", "rawtypes"}) Object drag = Enum.valueOf(clickEnum, "QUICK_CRAFT");
        Method clicked = menu.getClass().getMethod("clicked", int.class, int.class, clickType, playerType);
        Method carried = menu.getClass().getMethod("getCarried");
        Method count = itemType.getMethod("getCount");
        Method slotItem = slotType.getMethod("getItem");
        Field backing = slotType.getField("container");
        List<?> slots = (List<?>) menu.getClass().getField("slots").get(menu);
        ItemStack[] original = cloneItems(actor.getInventory().getStorageContents());
        // Warm the real Slot.getItem path before final-field reassignment to exercise compiled access too.
        for (int attempt = 0; attempt < 20_000; attempt++) slotItem.invoke(slots.get(81));
        InventoryTransferView detached = InventoryTransferView.attach(actor, 54, original);
        Object virtual = backing.get(slots.get(54));
        check(virtual != real && virtual.getClass().getName().equals("net.minecraft.world.SimpleContainer"), "final Slot.container reassigned to SimpleContainer");
        for (int raw = 54; raw < 90; raw++) check(backing.get(slots.get(raw)) == virtual, "all 36 bottom slot backings replaced");
        check(Arrays.equals(original, detached.contents()), "initial detached contents preserve administrator items");
        check(((int) slotType.getMethod("getContainerSlot").invoke(slots.get(54))) == 9
                        && ((int) slotType.getMethod("getContainerSlot").invoke(slots.get(81))) == 0,
                "native source indexes remain unchanged");
        check(actor.getOpenInventory().getItem(81).equals(new ItemStack(Material.STONE, 16)), "raw Bukkit view reads detached native slot");
        actor.getOpenInventory().setItem(81, new ItemStack(Material.STONE, 12));
        check(detached.getItem(0).getAmount() == 12 && actor.getInventory().getItem(0).getAmount() == 16,
                "raw Bukkit view writes detached slot and leaves real Inventory intact");
        detached.setItem(0, original[0]);

        top.setItem(0, new ItemStack(Material.DIAMOND, 8));
        clicked.invoke(menu, 0, 0, pickup, handle);
        check(top.getItem(0) == null && (int) count.invoke(carried.invoke(menu)) == 8, "native left click picks up staged target item");
        clicked.invoke(menu, 82, 0, pickup, handle); // Bottom index 1.
        check(detached.getItem(1).getAmount() == 8 && (int) count.invoke(carried.invoke(menu)) == 0, "native left click deposits into detached bottom");
        ItemStack[] copy = detached.contents();
        copy[1].setAmount(1);
        check(detached.getItem(1).getAmount() == 8, "returned contents are independent clones");
        clicked.invoke(menu, 82, 1, pickup, handle);
        check(detached.getItem(1).getAmount() == 4 && (int) count.invoke(carried.invoke(menu)) == 4, "native right click splits detached stack");
        clicked.invoke(menu, 0, 1, pickup, handle);
        check(top.getItem(0).getAmount() == 1 && (int) count.invoke(carried.invoke(menu)) == 3, "native right click places one target item");
        clicked.invoke(menu, 0, 0, pickup, handle);
        check(top.getItem(0).getAmount() == 4 && (int) count.invoke(carried.invoke(menu)) == 0, "native left click merges staged target stack");

        clicked.invoke(menu, 0, 0, pickup, handle);
        clicked.invoke(menu, -999, 0, drag, handle); // Left drag starts.
        clicked.invoke(menu, 83, 1, drag, handle);
        clicked.invoke(menu, 84, 1, drag, handle);
        clicked.invoke(menu, -999, 2, drag, handle); // Distribution applies through the Bukkit raw view.
        check(detached.getItem(2).getAmount() == 2 && detached.getItem(3).getAmount() == 2
                        && (int) count.invoke(carried.invoke(menu)) == 0,
                "native QUICK_CRAFT distributes through detached Bukkit view");
        check(Arrays.equals(original, actor.getInventory().getStorageContents()), "native left/right/drag never modifies genuine player Inventory");
        detached.close();
        check(backing.get(slots.get(54)) == virtual, "close leaves the stale menu safely detached");
        plugin.getLogger().info("PROBE PASS native transfer adapter: " + (custom ? "CraftContainer + delegate ChestMenu" : "ChestMenu")
                + ", final backing, raw view, native left/right/drag, real administrator inventory unchanged");
    }

    private static ItemStack[] cloneItems(ItemStack[] contents) {
        return Arrays.stream(contents).map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }
}
