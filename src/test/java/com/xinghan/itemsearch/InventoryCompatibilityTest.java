package com.xinghan.itemsearch;

import com.xinghan.itemsearch.player.PlayerScanner;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

/** Independent reflection/layout checks; actual NMS adapters are additionally exercised on servers. */
class InventoryCompatibilityTest {
    @Test void legacyOffhandCellCannotBeEditedWhileArmorAndStorageRemainMapped() {
        assertEquals(-1, InventoryEditorLayout.fromGui(PlayerScanner.Storage.INVENTORY, 40, false));
        assertEquals(40, InventoryEditorLayout.fromGui(PlayerScanner.Storage.INVENTORY, 40, true));
        for (int source = 0; source < 40; source++) {
            int gui = InventoryEditorLayout.toGui(PlayerScanner.Storage.INVENTORY, source);
            assertEquals(source, InventoryEditorLayout.fromGui(PlayerScanner.Storage.INVENTORY, gui, false));
        }
        for (int slot = 0; slot < 27; slot++) assertEquals(slot,
                InventoryEditorLayout.fromGui(PlayerScanner.Storage.ENDER_CHEST, slot, false));
        assertEquals(-1, InventoryEditorLayout.fromGui(PlayerScanner.Storage.INVENTORY,
                InventoryEditorLayout.saveSlot(PlayerScanner.Storage.INVENTORY), false));
    }

    @Test void nativeStorageIndexIsDiscoveredByMeaningRatherThanObfuscatedFieldName() throws Exception {
        List<Object> slots = new ArrayList<Object>();
        for (int raw = 0; raw < 36; raw++) slots.add(new ObfuscatedSlot(expected(raw), 54 + raw));
        InventoryTransferView.NativeSupport.Index index = InventoryTransferView.NativeSupport.index(slots);
        for (int raw = 0; raw < slots.size(); raw++) assertEquals(expected(raw), index.get(slots.get(raw)));
        Collections.swap(slots, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> InventoryTransferView.NativeSupport.index(slots));
    }

    @Test void matchingModernSlotMethodAndAmbiguousOldIndicesAreHandledSafely() throws Exception {
        List<Object> modern = new ArrayList<Object>();
        List<Object> ambiguous = new ArrayList<Object>();
        for (int raw = 0; raw < 36; raw++) {
            modern.add(new ModernSlot(expected(raw)));
            ambiguous.add(new AmbiguousSlot(expected(raw)));
        }
        InventoryTransferView.NativeSupport.Index index = InventoryTransferView.NativeSupport.index(modern);
        assertEquals(9, index.get(modern.get(0)));
        assertEquals(0, index.get(modern.get(27)));
        assertThrows(IllegalArgumentException.class, () -> InventoryTransferView.NativeSupport.index(ambiguous));
    }

    @Test void nativeBackingMustReferToTheExactAdministratorInventoryAndBeUnique() throws Exception {
        Object real = new Object();
        BackingSlot valid = new BackingSlot(real);
        assertSame(real, InventoryTransferView.NativeSupport.backing(valid, real).get(valid));
        assertNull(InventoryTransferView.NativeSupport.backing(valid, new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> InventoryTransferView.NativeSupport.backing(new DuplicateBackingSlot(real), real));
    }

    @Test void anonymousSlotSubclassesAndUnrelatedSameLengthListsAreNotConfused() throws Exception {
        List<Object> slots = new ArrayList<Object>();
        for (int i = 0; i < 45; i++) slots.add(new BackingSlot(new Object()) { });
        Menu menu = new Menu(slots);
        assertSame(slots, InventoryTransferView.NativeSupport.slots(menu, 45));
        assertThrows(IllegalArgumentException.class, () -> InventoryTransferView.NativeSupport.slots(menu, 46));
        assertThrows(IllegalArgumentException.class,
                () -> InventoryTransferView.NativeSupport.slots(new DuplicateMenu(slots), 45));
    }

    @Test void detachedStoragePreservesDirectArrayAndListValuesWithoutStackTruncation() throws Exception {
        DummyItem oversized = new DummyItem(99);
        Object array = InventoryTransferView.NativeSupport.storage(new ArrayContainer(), DummyItem.class);
        InventoryTransferView.NativeSupport.put(array, 9, oversized);
        assertSame(oversized, InventoryTransferView.NativeSupport.at(array, 9));
        Object list = InventoryTransferView.NativeSupport.storage(new ListContainer(), DummyItem.class);
        InventoryTransferView.NativeSupport.put(list, 35, oversized);
        assertSame(oversized, InventoryTransferView.NativeSupport.at(list, 35));
        assertThrows(IllegalArgumentException.class,
                () -> InventoryTransferView.NativeSupport.storage(new DuplicateStorage(), DummyItem.class));
    }

    @Test void obfuscatedNativeSlotGetterIsValidatedWithoutAcceptingDestructiveReads() throws Exception {
        DummyItem[] storage = new DummyItem[36];
        storage[9] = new DummyItem(99);
        storage[35] = new DummyItem(3);
        DummyItem[] original = storage.clone();
        ObfuscatedReadSlot slot = new ObfuscatedReadSlot(storage, 9);
        Method read = InventoryTransferView.NativeSupport.slotReader(slot, DummyItem.class, storage, 9, new DummyItem(7));
        assertEquals("g", read.getName());
        assertSame(original[9], read.invoke(slot));
        assertArrayEquals(original, storage);
        assertTrue(slot.destructiveCalls > 0, "The misleading getItem operation was checked and rejected");
    }

    @Test void destructiveOnlyNativeSlotFailsClosedAndRestoresEveryDetachedSlot() {
        DummyItem[] storage = new DummyItem[36];
        storage[9] = new DummyItem(99);
        storage[35] = new DummyItem(3);
        DummyItem[] original = storage.clone();
        assertThrows(NoSuchMethodException.class, () -> InventoryTransferView.NativeSupport.slotReader(
                new DestructiveReadSlot(storage, 9), DummyItem.class, storage, 9, new DummyItem(7)));
        assertArrayEquals(original, storage);
    }

    @Test void obfuscatedSlotIndexMethodWinsOverAmbiguousIntegerFields() throws Exception {
        List<Object> slots = new ArrayList<Object>();
        for (int raw = 0; raw < 36; raw++) slots.add(new ObfuscatedIndexSlot(expected(raw)));
        InventoryTransferView.NativeSupport.Index index = InventoryTransferView.NativeSupport.index(slots);
        assertEquals(9, index.get(slots.get(0)));
        assertEquals(0, index.get(slots.get(27)));
    }

    @Test void replacementViewInitializesOnlyAnUnsetNativeTitle() throws Exception {
        NativeTitleMenu menu = new NativeTitleMenu();
        Method setter = InventoryTransferView.NativeSupport.missingTitleSetter(menu);
        assertNotNull(setter);
        setter.invoke(menu, "§7自定义标题");
        assertEquals("§7自定义标题", menu.getTitle());
        assertNull(InventoryTransferView.NativeSupport.missingTitleSetter(menu));
        assertNull(InventoryTransferView.NativeSupport.missingTitleSetter(new Menu(Collections.emptyList())));
    }

    @Test void missingNativeTitleCanBeDetectedWhenOnlyTheGetterExposesItsState() throws Exception {
        HiddenTitleMenu menu = new HiddenTitleMenu();
        Method setter = InventoryTransferView.NativeSupport.missingTitleSetter(menu);
        assertNotNull(setter);
        setter.invoke(menu, "preserved");
        assertNull(InventoryTransferView.NativeSupport.missingTitleSetter(menu));
        assertEquals("preserved", menu.getTitle());
    }

    private static int expected(int raw) { return raw < 27 ? raw + 9 : raw - 27; }
    private static class ObfuscatedSlot {
        private final int a, raw;
        ObfuscatedSlot(int source, int raw) { this.a = source; this.raw = raw; }
    }
    public static class ModernSlot {
        private final int source;
        ModernSlot(int source) { this.source = source; }
        public int getContainerSlot() { return source; }
    }
    private static class AmbiguousSlot {
        private final int a, b;
        AmbiguousSlot(int index) { a = index; b = index; }
    }
    private static class BackingSlot {
        private final Object backing;
        BackingSlot(Object backing) { this.backing = backing; }
    }
    private static class DuplicateBackingSlot extends BackingSlot {
        private final Object alias;
        DuplicateBackingSlot(Object backing) { super(backing); alias = backing; }
    }
    private static class Menu {
        private final List<Object> slots;
        private final List<String> noise = Collections.nCopies(45, "unrelated");
        Menu(List<Object> slots) { this.slots = slots; }
    }
    private static class DuplicateMenu extends Menu {
        private final List<Object> duplicate;
        DuplicateMenu(List<Object> slots) { super(slots); duplicate = new ArrayList<Object>(slots); }
    }
    private static class DummyItem {
        final int amount;
        DummyItem(int amount) { this.amount = amount; }
    }
    private static class ArrayContainer { private final DummyItem[] data = new DummyItem[36]; }
    private static class ListContainer {
        private final List<DummyItem> data = new ArrayList<DummyItem>(Arrays.asList(new DummyItem[36]));
    }
    private static class DuplicateStorage extends ListContainer {
        private final DummyItem[] duplicate = new DummyItem[36];
    }
    public static class ObfuscatedReadSlot {
        private final DummyItem[] storage;
        private final int index;
        int destructiveCalls;
        ObfuscatedReadSlot(DummyItem[] storage, int index) { this.storage = storage; this.index = index; }
        public DummyItem getItem() { destructiveCalls++; DummyItem result = storage[index]; storage[index] = null; return result; }
        public DummyItem g() { return storage[index]; }
    }
    public static class DestructiveReadSlot {
        private final DummyItem[] storage;
        private final int index;
        DestructiveReadSlot(DummyItem[] storage, int index) { this.storage = storage; this.index = index; }
        public DummyItem getItem() { DummyItem result = storage[index]; Arrays.fill(storage, null); return result; }
    }
    public static class ObfuscatedIndexSlot {
        private final int a, b;
        ObfuscatedIndexSlot(int index) { a = index; b = index; }
        public int i() { return a; }
    }
    public static class NativeTitleMenu {
        private String title;
        public String getTitle() { if (title == null) throw new IllegalStateException("Title not set"); return title; }
        public void setTitle(String title) { this.title = title; }
    }
    public static class HiddenTitleMenu {
        private String renamed;
        public String getTitle() { if (renamed == null) throw new IllegalStateException("Title not set"); return renamed; }
        public void setTitle(String title) { this.renamed = title; }
    }
}
