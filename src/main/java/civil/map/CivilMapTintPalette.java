package civil.map;

import civil.CivilServices;
import civil.civilization.CivilRegionClassifier;
import civil.civilization.CivilRegionKind;
import civil.faction.Faction;
import civil.faction.FactionManager;
import civil.registry.DimensionPolicyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Encodes civil map tint bands as bytes for network + client blending.
 * <p>
 * Blended overlay bands: {@link #HIGH}, {@link #MONSTER} (shrine), {@link #ZONE}; all other cases use
 * {@link #UNKNOWN} (no overlay). {@link #MONSTER} indicates an activated farm shrine
 * bypass box. {@link #ZONE} indicates structure zone policy (caution / non-civilized VC).
 * Legacy bytes {@link #LOW} / {@link #MEDIUM} / {@link #DIM_DISABLED} may still exist in old saves.
 */
public final class CivilMapTintPalette {

    /** Legacy / unused for new map tint output; client does not blend. */
    public static final byte DIM_DISABLED = 0;
    public static final byte LOW = 1;
    public static final byte MEDIUM = 2;
    public static final byte HIGH = 3;
    public static final byte MONSTER = 4;
    public static final byte UNKNOWN = 5;
    /** Structure zone policy (caution orange); use with {@link civil.config.CivilConfig#mapTintZoneFillAlpha} etc. */
    public static final byte ZONE = 6;

    /** When {@link #evaluateTintForChunk} returns {@link #UNKNOWN} from civilization lookup. */
    public enum ScoreUnknownReason {
        NULL_CSCORE,
        LOOKUP_EXCEPTION
    }

    public record ChunkTintEval(byte band, int factionColor, ScoreUnknownReason scoreUnknownReason) {
    }

    private CivilMapTintPalette() {
    }

    /**
     * Same as {@link #tintForChunk} but records why civilization path yielded {@link #UNKNOWN}.
     */
    public static ChunkTintEval evaluateTintForChunk(ServerLevel level, int cx, int cz, int sy) {
        if (!DimensionPolicyRegistry.policyFor(level).civilization()) {
            return new ChunkTintEval(UNKNOWN, 0, null);
        }
        CivilRegionClassifier.ClassifyResult r = CivilRegionClassifier.classify(level, cx, cz, sy);
        int factionColor = 0;
        if (r.kind() == CivilRegionKind.HIGH) {
            FactionManager fm = CivilServices.getFactionManager();
            if (fm != null && fm.isInitialized()) {
                BlockPos vcCenter = new BlockPos((cx << 4) + 8, (sy << 4) + 8, (cz << 4) + 8);
                Faction f = fm.getFactionAt(level, vcCenter);
                if (f != null) factionColor = f.color();
            }
        }
        return switch (r.kind()) {
            case SHRINE -> new ChunkTintEval(MONSTER, 0, null);
            case ZONE -> new ChunkTintEval(ZONE, 0, null);
            case HIGH -> new ChunkTintEval(HIGH, factionColor, null);
            case NONE -> {
                if (r.scoreUnknownReason() != null) {
                    yield new ChunkTintEval(UNKNOWN, 0, scoreUnknownReason(r.scoreUnknownReason()));
                }
                yield new ChunkTintEval(UNKNOWN, 0, null);
            }
        };
    }

    private static ScoreUnknownReason scoreUnknownReason(CivilRegionClassifier.ScoreUnknownReason reason) {
        return switch (reason) {
            case NULL_CSCORE -> ScoreUnknownReason.NULL_CSCORE;
            case LOOKUP_EXCEPTION -> ScoreUnknownReason.LOOKUP_EXCEPTION;
        };
    }

    public static byte tintForChunk(ServerLevel level, int cx, int cz, int sy) {
        return evaluateTintForChunk(level, cx, cz, sy).band();
    }
}
