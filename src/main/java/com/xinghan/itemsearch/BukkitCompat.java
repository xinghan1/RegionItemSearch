package com.xinghan.itemsearch;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.util.*;

/** All calls added after Bukkit 1.8 live behind this adapter. */
public final class BukkitCompat {
    private static volatile BukkitAudiences audiences;
    private static volatile NativeItemCodec codec;
    private BukkitCompat() { }
    public static void initialize(Plugin plugin) { audiences = BukkitAudiences.create(plugin); }
    public static void close() { BukkitAudiences current = audiences; audiences = null; if (current != null) current.close(); }
    public static String legacy(Component component) { return LegacyComponentSerializer.legacySection().serialize(component); }
    public static void send(CommandSender sender, Component component) {
        if (component == null) return;
        Component display = oldClipboard(component);
        BukkitAudiences current = audiences;
        if (current == null) sender.sendMessage(legacy(display)); else current.sender(sender).sendMessage(display);
    }
    private static Component oldClipboard(Component component) {
        if (hasOffhand() && minorVersion() >= 15) return component;
        List<Component> children = new ArrayList<Component>();
        for (Component child : component.children()) children.add(oldClipboard(child));
        Component result = component.children(children);
        ClickEvent click = result.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.COPY_TO_CLIPBOARD)
            result = result.clickEvent(ClickEvent.suggestCommand(click.value())).hoverEvent(HoverEvent.showText(Language.component("compat.uuid-copy-fallback")));
        return result;
    }
    public static ItemStack held(Player player) {
        try { return (ItemStack) player.getInventory().getClass().getMethod("getItemInMainHand").invoke(player.getInventory()); }
        catch (NoSuchMethodException old) { return player.getItemInHand(); }
        catch (ReflectiveOperationException error) { throw failure("read main hand",error); }
    }
    public static boolean air(Material type) { return type == null || type.name().equals("AIR") || type.name().equals("CAVE_AIR") || type.name().equals("VOID_AIR") || type.name().equals("LEGACY_AIR"); }
    public static boolean air(ItemStack item) { return item == null || air(item.getType()) || item.getAmount() <= 0; }
    public static Material material(String modern, String... alternatives) {
        Material material = Material.matchMaterial(modern);
        if (material != null) return material;
        for (String alternative : alternatives) { material = Material.matchMaterial(alternative); if (material != null) return material; }
        throw new IllegalArgumentException("Material unavailable: " + modern);
    }
    public static ItemStack[] storageContents(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        return Arrays.copyOf(contents,36);
    }
    public static void setStorageContents(Player player, ItemStack[] contents) {
        if (contents.length != 36) throw new IllegalArgumentException("Expected 36 storage slots");
        // Indexed storage writes preserve armor and offhand on every inventory layout.
        for (int i=0;i<36;i++) player.getInventory().setItem(i, contents[i] == null ? null : contents[i].clone());
    }
    public static boolean hasOffhand() {
        try { PlayerInventory.class.getMethod("getItemInOffHand"); return true; }
        catch (NoSuchMethodException absent) { return false; }
    }
    public static ItemStack offhand(Player player) {
        if (!hasOffhand()) return null;
        try { return (ItemStack) player.getInventory().getClass().getMethod("getItemInOffHand").invoke(player.getInventory()); }
        catch (ReflectiveOperationException error) { throw failure("read offhand",error); }
    }
    public static void setOffhand(Player player, ItemStack item) {
        if (!hasOffhand()) { if (!air(item)) throw new IllegalArgumentException("This server has no offhand slot"); return; }
        try { player.getInventory().getClass().getMethod("setItemInOffHand",ItemStack.class).invoke(player.getInventory(),item); }
        catch (ReflectiveOperationException error) { throw failure("write offhand",error); }
    }
    public static byte[] serializeItem(ItemStack item) {
        if (air(item)) throw new IllegalArgumentException("Cannot encode an empty stack");
        try { return (byte[]) ItemStack.class.getMethod("serializeAsBytes").invoke(item); }
        catch (NoSuchMethodException old) { return nativeCodec().serialize(item); }
        catch (ReflectiveOperationException error) { throw failure("serialize item",error); }
    }
    public static ItemStack deserializeItem(byte[] bytes) {
        try { return (ItemStack) ItemStack.class.getMethod("deserializeBytes",byte[].class).invoke(null,bytes); }
        catch (NoSuchMethodException old) { return nativeCodec().deserialize(bytes); }
        catch (ReflectiveOperationException error) { throw failure("deserialize item",error); }
    }
    private static NativeItemCodec nativeCodec() {
        NativeItemCodec current=codec;
        if (current==null) synchronized(BukkitCompat.class) { current=codec; if(current==null) codec=current=new NativeItemCodec(); }
        return current;
    }
    public static int dataVersion() {
        try { return ((Number) Bukkit.getUnsafe().getClass().getMethod("getDataVersion").invoke(Bukkit.getUnsafe())).intValue(); }
        catch (NoSuchMethodException absent) { return legacyDataVersion(Bukkit.getBukkitVersion()); }
        catch (ReflectiveOperationException error) { throw failure("read data version",error); }
    }
    static int legacyDataVersion(String version) {
        String v=version.split("-",2)[0];
        if(v.equals("1.9")) return 169; if(v.equals("1.9.1")) return 175; if(v.equals("1.9.2")) return 176;
        if(v.equals("1.9.3")||v.equals("1.9.4")) return 184;
        if(v.equals("1.10")) return 510; if(v.equals("1.10.1")) return 511; if(v.equals("1.10.2")) return 512; if(v.equals("1.11")) return 819;
        if(v.equals("1.11.1")||v.equals("1.11.2")) return 922;
        if(v.equals("1.12")) return 1139; if(v.equals("1.12.1")) return 1241; if(v.equals("1.12.2")) return 1343;
        if(v.startsWith("1.8")) return 0;
        throw new IllegalStateException("This server does not expose its NBT data version: " + version);
    }
    private static int minorVersion() {
        String v = Bukkit.getBukkitVersion().split("-",2)[0];
        if (!v.startsWith("1.")) return Integer.MAX_VALUE;
        try { return Integer.parseInt(v.split("\\.")[1]); } catch(RuntimeException malformed) { return Integer.MAX_VALUE; }
    }
    public static World mainWorld() {
        try {
            Object name=Bukkit.getUnsafe().getClass().getMethod("getMainLevelName").invoke(Bukkit.getUnsafe());
            if(name instanceof String) { World world=Bukkit.getWorld((String)name); if(world!=null) return world; }
        } catch(NoSuchMethodException classic) { }
        catch(ReflectiveOperationException error) { throw failure("resolve main player-data world",error); }
        List<World> worlds=Bukkit.getWorlds();
        if(worlds.isEmpty()) throw new IllegalStateException("No world is registered");
        return worlds.get(0);
    }
    /** 26.x stores player files at level-root/players/data rather than world-folder/playerdata. */
    public static java.nio.file.Path playerDataDirectory(World world) {
        String version=Bukkit.getBukkitVersion().split("-",2)[0];
        boolean modern=false;
        if(!version.startsWith("1.")) try { modern=Integer.parseInt(version.split(java.util.regex.Pattern.quote("."),2)[0])>=26; }
        catch(NumberFormatException unknown) { }
        return playerDataDirectory(world.getWorldFolder().toPath(),modern);
    }
    static java.nio.file.Path playerDataDirectory(java.nio.file.Path worldFolder,boolean modern) {
        java.nio.file.Path folder=worldFolder.toAbsolutePath().normalize();
        if(!modern) return folder.resolve("playerdata");
        for(java.nio.file.Path ancestor=folder.getParent();ancestor!=null;ancestor=ancestor.getParent()) {
            if(ancestor.getFileName()!=null && ancestor.getFileName().toString().equals("dimensions")
                    && ancestor.getParent()!=null && ancestor.relativize(folder).getNameCount()>=2)
                return ancestor.getParent().resolve("players").resolve("data");
        }
        return folder.resolve("players").resolve("data");
    }
    public static Inventory createInventory(InventoryHolder holder,int size,Component title) {
        String text=legacy(title);
        if(minorVersion()<14 && text.length()>32) {
            text=text.substring(0,32);
            if(text.endsWith("§") || Character.isHighSurrogate(text.charAt(text.length()-1))) text=text.substring(0,text.length()-1);
        }
        return Bukkit.createInventory(holder,size,text);
    }
    public static void displayName(ItemMeta meta,Component name) { meta.setDisplayName(legacy(name)); }
    public static void lore(ItemMeta meta,List<Component> lines) {
        List<String> result=new ArrayList<String>(); for(Component line:lines) result.add(legacy(line)); meta.setLore(result);
    }
    public static Inventory viewTop(Object view) { return (Inventory) viewCall(view,"getTopInventory",new Class<?>[0]); }
    public static Inventory viewBottom(Object view) { return (Inventory) viewCall(view,"getBottomInventory",new Class<?>[0]); }
    public static ItemStack viewItem(Object view,int slot) { return (ItemStack) viewCall(view,"getItem",new Class<?>[]{int.class},slot); }
    public static void viewSetItem(Object view,int slot,ItemStack item) { viewCall(view,"setItem",new Class<?>[]{int.class,ItemStack.class},slot,item); }
    public static HumanEntity viewPlayer(Object view) { return (HumanEntity) viewCall(view,"getPlayer",new Class<?>[0]); }
    public static String viewTitle(Object view) { return String.valueOf(viewCall(view,"getTitle",new Class<?>[0])); }
    public static ItemStack viewCursor(Object view) { return (ItemStack) viewCall(view,"getCursor",new Class<?>[0]); }
    public static void viewSetCursor(Object view,ItemStack item) { viewCall(view,"setCursor",new Class<?>[]{ItemStack.class},item); }
    private static Object viewCall(Object view,String name,Class<?>[] types,Object... args) {
        try { Method method=view.getClass().getMethod(name,types); if(!Modifier.isPublic(method.getDeclaringClass().getModifiers())) method.setAccessible(true); return method.invoke(view,args); }
        catch(ReflectiveOperationException error) { throw failure("inventory view " + name,error); }
    }
    static IllegalStateException failure(String operation,ReflectiveOperationException error) {
        Throwable cause=error instanceof InvocationTargetException ? ((InvocationTargetException)error).getCause() : error;
        return new IllegalStateException("Cannot " + operation + ": " + cause,cause);
    }
}




