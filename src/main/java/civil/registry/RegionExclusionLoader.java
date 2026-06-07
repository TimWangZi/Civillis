package civil.registry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class RegionExclusionLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("civil-exclusion");
    private static final String DATA_PATH = "civil_region_exclusions";

    private RegionExclusionLoader() {}

    public static void reload(ResourceManager manager) {
        RegionExclusionHelper.clear();
        int count = 0;

        Map<Identifier, Resource> resources = manager.listResources(
                DATA_PATH, id -> id.getPath().endsWith(".json"));

        for (var entry : resources.entrySet()) {
            try (InputStream is = entry.getValue().open();
                 InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {

                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();

                String dim = obj.get("dimension").getAsString();
                int minChunkX = obj.get("minChunkX").getAsInt();
                int maxChunkX = obj.get("maxChunkX").getAsInt();
                int minChunkZ = obj.get("minChunkZ").getAsInt();
                int maxChunkZ = obj.get("maxChunkZ").getAsInt();

                RegionExclusionHelper.addChunkRange(dim, minChunkX, maxChunkX, minChunkZ, maxChunkZ);
                count++;
            } catch (Exception e) {
                LOGGER.warn("[civil-exclusion] Failed to parse {}: {}", entry.getKey(), e.getMessage());
            }
        }

        LOGGER.info("[civil-exclusion] Loaded {} exclusion zone(s)", count);
    }
}
