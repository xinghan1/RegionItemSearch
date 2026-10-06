package com.xinghan.itemsearch;

import com.xinghan.itemsearch.player.PlayerScanner;
import java.util.Objects;

/** Maps a player snapshot to the editor grid without accessing Bukkit. */
public final class InventoryEditorLayout {
    private InventoryEditorLayout() {}

    public static int sourceSize(PlayerScanner.Storage storage) {
        return Objects.requireNonNull(storage) == PlayerScanner.Storage.INVENTORY ? 41 : 27;
    }

    public static int size(PlayerScanner.Storage storage) {
        return Objects.requireNonNull(storage) == PlayerScanner.Storage.INVENTORY ? 54 : 36;
    }

    public static int toGui(PlayerScanner.Storage storage, int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= sourceSize(storage)) throw new IllegalArgumentException("Invalid source slot");
        if (storage == PlayerScanner.Storage.ENDER_CHEST) return sourceIndex;
        if (sourceIndex < 9) return 27 + sourceIndex;
        if (sourceIndex < 36) return sourceIndex - 9;
        return sourceIndex;
    }

    public static int fromGui(PlayerScanner.Storage storage, int guiSlot) {
        if (guiSlot < 0 || guiSlot >= size(storage)) return -1;
        if (storage == PlayerScanner.Storage.ENDER_CHEST) return guiSlot < 27 ? guiSlot : -1;
        if (guiSlot < 27) return guiSlot + 9;
        if (guiSlot < 36) return guiSlot - 27;
        return guiSlot <= 40 ? guiSlot : -1;
    }

    /** Pre-1.9 servers retain the common snapshot shape but the offhand cell is a read-only filler. */
    public static int fromGui(PlayerScanner.Storage storage, int guiSlot, boolean hasOffhand) {
        int source = fromGui(storage, guiSlot);
        return storage == PlayerScanner.Storage.INVENTORY && source == 40 && !hasOffhand ? -1 : source;
    }

    public static int helpSlot(PlayerScanner.Storage storage) { return size(storage) - 9; }
    public static int saveSlot(PlayerScanner.Storage storage) { return size(storage) - 5; }
    public static int cancelSlot(PlayerScanner.Storage storage) { return size(storage) - 1; }
}
