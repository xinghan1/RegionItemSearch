package com.xinghan.itemsearch;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.lang.reflect.*;
import java.util.*;

/** Validates and detaches native bottom slots, including Bukkit raw-slot writes used by drag. */
public interface InventoryTransferView extends AutoCloseable {
    @FunctionalInterface interface Factory {
        InventoryTransferView attach(Player viewer, int topSize, ItemStack[] admin) throws Exception;
    }
    ItemStack[] contents();
    ItemStack getItem(int index);
    void setItem(int index, ItemStack item);
    @Override default void close() { } // Never reconnect a stale menu to the administrator's inventory.

    static InventoryTransferView attach(Player viewer, int topSize, ItemStack[] admin) throws ReflectiveOperationException {
        requireOwner(viewer);
        if (BukkitCompat.viewTop(viewer.getOpenInventory()).getSize() != topSize) throw new IllegalArgumentException("The expected menu is not open");
        Object player = viewer.getClass().getMethod("getHandle").invoke(viewer);
        // CraftInventory retains this public bridge even when Spigot remaps NMS member names.
        Object inventory = NativeSupport.call(viewer.getInventory(), "getInventory");
        if (inventory == null) inventory = NativeSupport.value(player, "inventory");
        if (inventory == null) inventory = NativeSupport.call(player, "getInventory");
        Object menu = NativeSupport.value(player, "containerMenu", "activeContainer");
        if (menu == null) menu = NativeSupport.activeMenu(player, viewer.getOpenInventory());
        if (inventory == null || menu == null) throw new IllegalArgumentException("Unsupported active inventory backing");
        return attachMenu(viewer, menu, inventory, topSize, admin);
    }

    /** Kept for native-menu runtime probes. Java 8 interface methods cannot be private. */
    static InventoryTransferView attachMenu(Player viewer, Object menu, Object inventory, int topSize, ItemStack[] admin)
            throws ReflectiveOperationException {
        requireOwner(viewer);
        Objects.requireNonNull(admin, "admin");
        if (admin.length != 36 || topSize < 9 || topSize > 54 || topSize % 9 != 0) throw new IllegalArgumentException("Unsupported staged inventory size");
        String craft = Bukkit.getServer().getClass().getPackage().getName();
        String legacy = "net.minecraft.server." + craft.substring(craft.lastIndexOf('.') + 1) + ".";
        ClassLoader loader = menu.getClass().getClassLoader();
        Class<?> container = NativeSupport.type(loader, "net.minecraft.world.SimpleContainer", "net.minecraft.world.InventorySubcontainer", legacy + "InventorySubcontainer");
        Class<?> craftItem = NativeSupport.type(loader, craft + ".inventory.CraftItemStack");
        Method nms = craftItem.getMethod("asNMSCopy", ItemStack.class);
        Class<?> item = nms.getReturnType();
        List<?> slots = NativeSupport.slots(menu, topSize + 36);
        List<Object> bottom = new ArrayList<Object>();
        List<Field> backing = new ArrayList<Field>();
        for (int i = topSize; i < topSize + 36; i++) {
            Object slot = slots.get(i);
            Field field = NativeSupport.backing(slot, inventory);
            if (field == null) throw new IllegalArgumentException("Unsupported slot backing at " + i);
            bottom.add(slot); backing.add(field);
        }
        NativeSupport.Index index = NativeSupport.index(bottom);
        Object virtual;
        try { virtual = container.getConstructor(int.class).newInstance(36); }
        catch (NoSuchMethodException old) { virtual = container.getConstructor(String.class, boolean.class, int.class).newInstance("RegionItemSearch", false, 36); }
        Object storage = NativeSupport.storage(virtual, item);
        Inventory detached = NativeSupport.inventoryBridge(loader, craft, virtual);
        if (detached.getSize() != 36) throw new IllegalArgumentException("Unsupported detached Bukkit inventory size");
        detached.setMaxStackSize(viewer.getInventory().getMaxStackSize());
        for (int i = 0; i < 36; i++) NativeSupport.put(storage, i, nms.invoke(null, admin[i] == null ? null : admin[i].clone()));
        Object nativeMarker = nms.invoke(null, new ItemStack(Material.STONE, 7));

        Field viewField = NativeSupport.field(menu.getClass(), "view");
        Object originalView = viewField == null ? null : viewField.get(menu), replacement = null;
        if (originalView != null && menu.getClass().getName().endsWith(".CraftContainer")) {
            Class<?> view = NativeSupport.type(loader, craft + ".inventory.CraftInventoryView");
            Inventory top = BukkitCompat.viewTop(originalView);
            NativeSupport.initializeTitle(menu, loader, craft, BukkitCompat.viewTitle(originalView));
            for (Constructor<?> constructor : view.getConstructors()) {
                Class<?>[] args = constructor.getParameterTypes();
                if (args.length == 3 && args[0].isInstance(viewer) && args[1].isInstance(top) && args[2].isInstance(menu)) {
                    replacement = constructor.newInstance(viewer, top, menu); break;
                }
            }
            if (replacement == null) throw new IllegalArgumentException("Unsupported detached Bukkit view constructor");
        }
        int changed = 0;
        boolean viewChanged = false;
        ItemStack[] baseline = BukkitCompat.storageContents(viewer);
        try {
            for (int i = 0; i < 36; i++) {
                backing.get(i).set(bottom.get(i), virtual); changed++;
                int containerIndex = index.get(bottom.get(i));
                Method readSlot = NativeSupport.slotReader(bottom.get(i), item, storage, containerIndex, nativeMarker);
                Object actual = readSlot.invoke(bottom.get(i));
                if (backing.get(i).get(bottom.get(i)) != virtual || actual != NativeSupport.at(storage, containerIndex))
                    throw new IllegalStateException("Native bottom slots were not detached");
            }
            if (replacement != null) {
                viewField.set(menu, replacement); viewChanged = true;
                if (NativeSupport.call(menu, "getBukkitView") != replacement) throw new IllegalStateException("Raw-slot view replacement failed");
            }
            NativeView result = new NativeView(viewer, storage, detached, nms);
            ItemStack marker = new ItemStack(Material.STONE, !BukkitCompat.air(baseline[9]) && baseline[9].getType() == Material.STONE && baseline[9].getAmount() == 1 ? 2 : 1);
            Object saved = NativeSupport.at(storage, 9);
            try {
                BukkitCompat.viewSetItem(viewer.getOpenInventory(), topSize, marker);
                if (!marker.equals(result.getItem(9)) || !NativeSupport.sameItems(baseline, BukkitCompat.storageContents(viewer)))
                    throw new IllegalStateException("Bukkit drag/raw-slot writes still reach the real inventory");
            } finally { NativeSupport.put(storage, 9, saved); }
            viewer.updateInventory();
            return result;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (viewChanged) try { viewField.set(menu, originalView); } catch (ReflectiveOperationException rollback) { failure.addSuppressed(rollback); }
            for (int i = changed - 1; i >= 0; i--) try { backing.get(i).set(bottom.get(i), inventory); }
            catch (ReflectiveOperationException rollback) { failure.addSuppressed(rollback); }
            if (!NativeSupport.sameItems(baseline, BukkitCompat.storageContents(viewer))) BukkitCompat.setStorageContents(viewer, baseline);
            throw failure;
        }
    }

    static boolean owns(Player viewer) {
        // Folia's primary-thread predicate accepts every region TickThread, including other owners.
        if (!NativeSupport.FOLIA) return Bukkit.isPrimaryThread();
        try { return Boolean.TRUE.equals(Bukkit.class.getMethod("isOwnedByCurrentRegion", Entity.class).invoke(null, viewer)); }
        catch (ReflectiveOperationException unavailable) { return false; }
    }
    static void requireOwner(Player viewer) {
        if (!owns(viewer)) throw new IllegalStateException("Inventory access requires the owning player thread");
    }

    final class NativeView implements InventoryTransferView {
        private final Player owner;
        private final Object storage;
        private final Inventory detached;
        private final Method nms;
        NativeView(Player owner, Object storage, Inventory detached, Method nms) {
            this.owner = owner; this.storage = storage; this.detached = detached; this.nms = nms;
        }
        @Override public ItemStack[] contents() {
            requireOwner(owner); ItemStack[] result = new ItemStack[36];
            for (int i = 0; i < 36; i++) result[i] = getItem(i);
            return result;
        }
        @Override public ItemStack getItem(int index) {
            requireOwner(owner); NativeSupport.check(index);
            // CraftInventory uses the server's actual native getter, including obfuscated servers.
            // Its returned stack may be a mirror, so never expose it outside the detached inventory.
            ItemStack value = detached.getItem(index);
            return BukkitCompat.air(value) ? null : value.clone();
        }
        @Override public void setItem(int index, ItemStack value) {
            requireOwner(owner); NativeSupport.check(index);
            try {
                detached.setItem(index, value == null ? null : value.clone());
                // Several server versions truncate oversized existing stacks inside setItem.
                // Preserve the staged quantity after notifying the detached container's listeners.
                NativeSupport.put(storage, index, nms.invoke(null, value == null ? null : value.clone()));
            }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot update detached inventory", failure); }
        }
    }

    final class NativeSupport {
        static final boolean FOLIA = folia();
        private NativeSupport() { }
        static boolean folia() {
            try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); return true; }
            catch (ClassNotFoundException absent) { return false; }
        }
        static Class<?> type(ClassLoader loader, String... names) throws ClassNotFoundException {
            for (String name : names) try { return Class.forName(name, false, loader); } catch (ClassNotFoundException ignored) { }
            throw new ClassNotFoundException("Unsupported native inventory class " + Arrays.toString(names));
        }
        static List<Field> fields(Class<?> type) {
            List<Field> fields = new ArrayList<Field>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) { field.setAccessible(true); fields.add(field); }
            }
            return fields;
        }
        static Field field(Class<?> type, String... names) {
            for (String name : names) for (Field field : fields(type)) if (field.getName().equals(name)) return field;
            return null;
        }
        static Object value(Object object, String... names) throws IllegalAccessException {
            Field field = field(object.getClass(), names); return field == null ? null : field.get(object);
        }
        static Object call(Object object, String name) throws ReflectiveOperationException {
            try { return object.getClass().getMethod(name).invoke(object); } catch (NoSuchMethodException ignored) { return null; }
        }
        static Inventory inventoryBridge(ClassLoader loader, String craft, Object container) throws ReflectiveOperationException {
            Class<?> bridge = type(loader, craft + ".inventory.CraftInventory");
            for (Constructor<?> constructor : bridge.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 1 && parameters[0].isInstance(container))
                    return (Inventory) constructor.newInstance(container);
            }
            throw new NoSuchMethodException("CraftInventory native container bridge");
        }
        /** Custom CraftContainer views may never initialize the native title read by CraftInventoryView. */
        static void initializeTitle(Object menu, ClassLoader loader, String craft, String title)
                throws ReflectiveOperationException {
            Method setter = missingTitleSetter(menu);
            if (setter == null) return;
            Class<?> chat = type(loader, craft + ".util.CraftChatMessage");
            Object components;
            try { components = chat.getMethod("fromString", String.class, boolean.class).invoke(null, title, true); }
            catch (NoSuchMethodException old) { components = chat.getMethod("fromString", String.class).invoke(null, title); }
            if (components == null || !components.getClass().isArray() || Array.getLength(components) == 0)
                throw new IllegalArgumentException("Native menu title conversion returned no component");
            setter.invoke(menu, Array.get(components, 0));
        }
        static Method missingTitleSetter(Object menu) throws ReflectiveOperationException {
            Method getter;
            try { getter = menu.getClass().getMethod("getTitle"); }
            catch (NoSuchMethodException legacy) { return null; }
            Field title = field(menu.getClass(), "title");
            if (title != null) {
                if (title.get(menu) != null) return null;
            } else {
                try { if (getter.invoke(menu) != null) return null; }
                catch (InvocationTargetException error) {
                    if (!(error.getCause() instanceof IllegalStateException)
                            || !"Title not set".equals(error.getCause().getMessage())) throw error;
                }
            }
            Method setter = menu.getClass().getMethod("setTitle", getter.getReturnType());
            setter.setAccessible(true);
            return setter;
        }
        /** Validate only against the detached container; a wrong getter can never touch live inventory. */
        static Method slotReader(Object slot, Class<?> item, Object storage, int index, Object marker)
                throws ReflectiveOperationException {
            List<Method> candidates = new ArrayList<Method>();
            for (String name : new String[] {"getItem", "g"}) try {
                Method method = slot.getClass().getMethod(name);
                if (slotReadSignature(method, item)) candidates.add(method);
            } catch (NoSuchMethodException ignored) { }
            for (Method method : slot.getClass().getMethods())
                if (slotReadSignature(method, item) && !candidates.contains(method)) candidates.add(method);
            Object[] saved = new Object[36];
            for (int i = 0; i < saved.length; i++) saved[i] = at(storage, i);
            for (Method candidate : candidates) {
                candidate.setAccessible(true);
                put(storage, index, marker);
                try {
                    Object first = candidate.invoke(slot), second = candidate.invoke(slot);
                    boolean unchanged = first == marker && second == marker;
                    for (int i = 0; i < saved.length; i++)
                        unchanged &= at(storage, i) == (i == index ? marker : saved[i]);
                    if (unchanged) return candidate;
                } catch (InvocationTargetException rejected) {
                    // Another zero-argument native operation is not an inventory read.
                } finally {
                    for (int i = 0; i < saved.length; i++) put(storage, i, saved[i]);
                }
            }
            throw new NoSuchMethodException("Validated non-destructive native slot getter");
        }
        static boolean slotReadSignature(Method method, Class<?> item) {
            return !Modifier.isStatic(method.getModifiers()) && method.getParameterTypes().length == 0
                    && item.isAssignableFrom(method.getReturnType());
        }
        static Object activeMenu(Object player, Object view) throws ReflectiveOperationException {
            Object direct = call(view, "getHandle");
            if (direct != null && call(direct, "getBukkitView") == view) return direct;
            Object result = null;
            for (Field field : fields(player.getClass())) {
                Object candidate = field.get(player);
                if (candidate == null) continue;
                // Do not rely on obfuscated menu types or fields. The CraftBukkit view bridge is stable.
                Method bridge;
                try { bridge = candidate.getClass().getMethod("getBukkitView"); }
                catch (NoSuchMethodException ignored) { continue; }
                if (bridge.getReturnType() == Void.TYPE || bridge.getParameterTypes().length != 0) continue;
                if (bridge.invoke(candidate) != view) continue;
                if (result != null && result != candidate) throw new IllegalArgumentException("Ambiguous active inventory menu");
                result = candidate;
            }
            return result;
        }
        static List<?> slots(Object menu, int size) throws IllegalAccessException {
            List<?> result = null;
            for (Field field : fields(menu.getClass())) {
                Object value = field.get(menu);
                if (!(value instanceof List<?>) || ((List<?>) value).size() != size) continue;
                List<?> list = (List<?>) value;
                if (list.isEmpty() || list.get(0) == null || !slotType(list.get(0).getClass())) continue;
                if (result != null && result != list) throw new IllegalArgumentException("Ambiguous native slot list");
                result = list;
            }
            if (result == null) throw new IllegalArgumentException("Unsupported native slot list");
            return result;
        }
        static boolean slotType(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass())
                if (current.getSimpleName().contains("Slot")) return true;
            return false;
        }
        static Field backing(Object slot, Object inventory) throws IllegalAccessException {
            Field result = null;
            for (Field field : fields(slot.getClass())) if (field.get(slot) == inventory) {
                if (result != null) throw new IllegalArgumentException("Ambiguous native slot backing"); result = field;
            }
            return result;
        }
        static Object storage(Object container, Class<?> item) throws IllegalAccessException {
            Object result = null;
            for (Field field : fields(container.getClass())) {
                Object value = field.get(container);
                boolean valid = value instanceof List<?> && ((List<?>) value).size() == 36 || value != null && value.getClass().isArray()
                        && value.getClass().getComponentType() == item && Array.getLength(value) == 36;
                if (valid) { if (result != null && result != value) throw new IllegalArgumentException("Ambiguous detached item storage"); result = value; }
            }
            if (result == null) throw new IllegalArgumentException("Unsupported detached item storage"); return result;
        }
        static Object at(Object storage, int index) { return storage instanceof List<?> ? ((List<?>) storage).get(index) : Array.get(storage, index); }
        @SuppressWarnings("unchecked") static void put(Object storage, int index, Object value) {
            if (storage instanceof List<?>) ((List<Object>) storage).set(index, value); else Array.set(storage, index, value);
        }
        static Index index(List<Object> slots) throws ReflectiveOperationException {
            for (String name : new String[] {"getContainerSlot", "getSlotIndex", "i"}) try {
                Method method = slots.get(0).getClass().getMethod(name);
                if (method.getReturnType() != int.class || Modifier.isStatic(method.getModifiers())) continue;
                method.setAccessible(true);
                Index candidate = new Index(method, null);
                if (valid(candidate, slots)) return candidate;
            } catch (NoSuchMethodException ignored) { }
            Index result = null;
            for (Field field : fields(slots.get(0).getClass())) if (field.getType() == int.class) {
                Index candidate = new Index(null, field);
                if (valid(candidate, slots)) { if (result != null) throw new IllegalArgumentException("Ambiguous native slot index"); result = candidate; }
            }
            if (result == null) throw new IllegalArgumentException("Unsupported native slot order"); return result;
        }
        static boolean valid(Index index, List<Object> slots) throws ReflectiveOperationException {
            for (int i = 0; i < slots.size(); i++) if (index.get(slots.get(i)) != (i < 27 ? i + 9 : i - 27)) return false;
            return true;
        }
        static void check(int index) { if (index < 0 || index >= 36) throw new IndexOutOfBoundsException("Inventory slot " + index); }
        static boolean sameItems(ItemStack[] a, ItemStack[] b) {
            if (a.length != b.length) return false;
            for (int i = 0; i < a.length; i++) if (BukkitCompat.air(a[i]) ? !BukkitCompat.air(b[i]) : !a[i].equals(b[i])) return false;
            return true;
        }
        static final class Index {
            final Method method; final Field field;
            Index(Method method, Field field) { this.method = method; this.field = field; }
            int get(Object slot) throws ReflectiveOperationException { return ((Number) (method == null ? field.get(slot) : method.invoke(slot))).intValue(); }
        }
    }
}
