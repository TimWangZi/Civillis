package civil.registry;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks chunk ranges excluded from civilization scoring.
 * Addressed by packed chunk coordinates: {@code (long) cx << 32 | (cz & 0xFFFFFFFFL)}.
 * Thread-safe, accessed from main server thread and IO thread.
 */
public final class RegionExclusionHelper {

    private static final ConcurrentHashMap<String, Set<Long>> EXCLUSIONS = new ConcurrentHashMap<>();

    private RegionExclusionHelper() {}

    /**
     * Register a chunk range as excluded.
     * Coordinates are inclusive.
     */
    public static void addChunkRange(String dim, int minCx, int maxCx, int minCz, int maxCz) {
        Set<Long> set = EXCLUSIONS.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet());
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                set.add(pack(cx, cz));
            }
        }
    }

    /**
     * Check if a chunk position is in any excluded range.
     */
    public static boolean isExcluded(String dim, int cx, int cz) {
        Set<Long> set = EXCLUSIONS.get(dim);
        return set != null && set.contains(pack(cx, cz));
    }

    /**
     * Clear all exclusions. Call on world unload or /reload.
     */
    public static void clear() {
        EXCLUSIONS.clear();
    }

    private static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
