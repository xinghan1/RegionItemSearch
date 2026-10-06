import com.xinghan.itemsearch.item.ItemData;
import com.xinghan.itemsearch.region.RegionReader;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;

/** Test-only diagnostic for the isolated smoke world's saved inventories. */
public class InspectSaved {
    public static void main(String[] args) throws Exception {
        var json = new Gson();
        var reader = new RegionReader(16777216, 128);
        try (var files = Files.newDirectoryStream(Path.of(args[0]), "*.mca")) {
            for (var file : files) reader.scan(file, () -> false, chunk -> {
                Object entities = chunk.data().get("block_entities");
                if (!(entities instanceof List<?> list)) return;
                for (Object entry : list) {
                    if (!(entry instanceof Map<?, ?> entity)) continue;
                    if (!Set.of("minecraft:chest", "minecraft:barrel").contains(entity.get("id"))) continue;
                    System.out.println("ENTITY " + json.toJson(entity));
                    if (entity.get("Items") instanceof List<?> items) for (Object item : items) {
                        if (!(item instanceof Map<?, ?> raw)) continue;
                        @SuppressWarnings("unchecked") var parsed = ItemData.from((Map<String,Object>) raw,
                                Set.of("unique_uuid", "created_time", "owner_uuid"));
                        System.out.println("ITEM " + parsed.material() + " / " + parsed.name() + " / " + parsed.mmoType() + "/" + parsed.mmoId());
                        System.out.println("FINGERPRINT " + parsed.fingerprint());
                    }
                }
            }, (index, message) -> System.out.println("ERROR " + index + " " + message));
        }
    }
}
