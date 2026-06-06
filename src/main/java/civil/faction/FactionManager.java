package civil.faction;

import civil.CivilMod;
import civil.CivilServices;
import civil.civilization.TownCenterTracker;
import civil.civilization.VoxelChunkKey;
import civil.civilization.storage.CivilStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FactionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("civil-faction");

    private static final int[] DEFAULT_PALETTE = {
            0xFF4A90D9, 0xFFD94A4A, 0xFF4AD97A, 0xFFD9A64A,
            0xFFD94AD9, 0xFF4AD9D9, 0xFFA6A6A6, 0xFF8B5A2B,
            0xFFFF6B8A, 0xFF6B8AFF, 0xFF8AFF6B, 0xFFFFD700,
            0xFFFF8C00, 0xFF00CED1, 0xFFFF1493, 0xFF32CD32,
            0xFF9370DB, 0xFF20B2AA, 0xFFFF6347, 0xFF4682B4,
            0xFF7FFF00, 0xFFDC143C, 0xFF00BFFF, 0xFFFFDAB9
    };

    private final Map<UUID, Faction> factions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerFactionMap = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> invitationMap = new ConcurrentHashMap<>();  // playerId -> factionId

    private volatile CivilStorage storage;
    private volatile boolean initialized;
    private volatile boolean dirty;

    public void initialize(CivilStorage civilStorage) {
        this.storage = civilStorage;
        this.dirty = false;
        factions.clear();
        playerFactionMap.clear();
        invitationMap.clear();

        List<CivilStorage.StoredFaction> loaded = civilStorage.loadFactions();
        for (CivilStorage.StoredFaction sf : loaded) {
            Faction f = new Faction(sf.id(), sf.name(), sf.owner(), sf.members(), sf.color(), sf.createdAt());
            factions.put(sf.id(), f);
            playerFactionMap.put(sf.owner(), sf.id());
            for (UUID member : sf.members()) {
                playerFactionMap.put(member, sf.id());
            }
        }

        initialized = true;
        LOGGER.info("[civil-faction] Loaded {} faction(s) from storage", loaded.size());
    }

    public void shutdown() {
        initialized = false;
        factions.clear();
        playerFactionMap.clear();
        invitationMap.clear();
        storage = null;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public List<CivilStorage.StoredFaction> snapshotAllFactions() {
        List<CivilStorage.StoredFaction> out = new ArrayList<>();
        for (Faction f : factions.values()) {
            out.add(new CivilStorage.StoredFaction(f.id(), f.name(), f.owner(), f.members(), f.color(), f.createdAt()));
        }
        return out;
    }

    public Faction joinFactionByName(UUID playerId, String name) {
        if (!initialized) return null;
        for (Faction f : factions.values()) {
            if (f.name().equalsIgnoreCase(name)) {
                if (join(playerId, f.id())) return f;
            }
        }
        return null;
    }

    public Faction createFaction(UUID owner, String name) {
        if (!initialized) return null;
        if (playerFactionMap.containsKey(owner)) return null;

        UUID id = UUID.randomUUID();
        int colorIdx = Math.abs(id.hashCode()) % DEFAULT_PALETTE.length;
        int color = DEFAULT_PALETTE[colorIdx];
        Faction faction = new Faction(id, name, owner, ConcurrentHashMap.newKeySet(), color, System.currentTimeMillis());
        factions.put(id, faction);
        playerFactionMap.put(owner, id);
        dirty = true;
        return faction;
    }

    public UUID getOrCreateFactionForPlayer(UUID playerId, String playerName) {
        if (!initialized) return null;
        UUID existing = playerFactionMap.get(playerId);
        if (existing != null) return existing;
        Faction f = createFaction(playerId, playerName);
        return f != null ? f.id() : null;
    }

    public boolean disbandFaction(UUID factionId) {
        if (!initialized) return false;
        Faction f = factions.remove(factionId);
        if (f == null) return false;
        playerFactionMap.remove(f.owner());
        for (UUID m : f.members()) {
            playerFactionMap.remove(m);
        }
        dirty = true;
        return true;
    }

    public boolean join(UUID playerId, UUID factionId) {
        if (!initialized) return false;
        if (playerFactionMap.containsKey(playerId)) return false;
        Faction f = factions.get(factionId);
        if (f == null) return false;
        f.members().add(playerId);
        playerFactionMap.put(playerId, factionId);
        invitationMap.remove(playerId);
        dirty = true;
        return true;
    }

    public boolean leave(UUID playerId) {
        if (!initialized) return false;
        UUID factionId = playerFactionMap.get(playerId);
        if (factionId == null) return false;
        Faction f = factions.get(factionId);
        if (f == null) return false;

        if (f.owner().equals(playerId)) {
            if (!f.members().isEmpty()) {
                UUID newOwner = f.members().iterator().next();
                f.members().remove(newOwner);
                playerFactionMap.put(newOwner, factionId);
                playerFactionMap.remove(playerId);
                factions.put(factionId, new Faction(f.id(), f.name(), newOwner, f.members(), f.color(), f.createdAt()));
                dirty = true;
                return true;
            } else {
                return disbandFaction(factionId);
            }
        }

        f.members().remove(playerId);
        playerFactionMap.remove(playerId);
        dirty = true;
        return true;
    }

    public boolean invite(UUID inviterId, UUID targetId) {
        if (!initialized) return false;
        UUID factionId = playerFactionMap.get(inviterId);
        if (factionId == null) return false;
        Faction f = factions.get(factionId);
        if (f == null || !f.owner().equals(inviterId)) return false;
        invitationMap.put(targetId, factionId);
        return true;
    }

    public boolean kick(UUID ownerId, UUID targetId) {
        if (!initialized) return false;
        UUID factionId = playerFactionMap.get(ownerId);
        if (factionId == null) return false;
        Faction f = factions.get(factionId);
        if (f == null || !f.owner().equals(ownerId)) return false;
        if (!f.members().contains(targetId)) return false;
        f.members().remove(targetId);
        playerFactionMap.remove(targetId);
        dirty = true;
        return true;
    }

    public boolean transferOwnership(UUID factionId, UUID newOwner) {
        if (!initialized) return false;
        Faction f = factions.get(factionId);
        if (f == null) return false;
        if (!f.members().contains(newOwner)) return false;
        f.members().remove(newOwner);
        f.members().add(f.owner());
        playerFactionMap.put(f.owner(), factionId);
        playerFactionMap.put(newOwner, factionId);
        factions.put(factionId, new Faction(f.id(), f.name(), newOwner, f.members(), f.color(), f.createdAt()));
        dirty = true;
        return true;
    }

    public boolean setColor(UUID playerId, int color) {
        if (!initialized) return false;
        UUID factionId = playerFactionMap.get(playerId);
        if (factionId == null) return false;
        Faction f = factions.get(factionId);
        if (f == null || !f.owner().equals(playerId)) return false;
        factions.put(factionId, new Faction(f.id(), f.name(), f.owner(), f.members(), color, f.createdAt()));
        dirty = true;
        return true;
    }

    public boolean setOpenRegistration(UUID playerId, boolean open) {
        // Stored on TownCenter level for now; faction-level flag is implicit
        return true;
    }

    public Faction getFaction(UUID id) {
        return factions.get(id);
    }

    public Faction getPlayerFaction(UUID playerId) {
        UUID factionId = playerFactionMap.get(playerId);
        return factionId != null ? factions.get(factionId) : null;
    }

    public boolean isMember(UUID playerId, UUID factionId) {
        UUID pf = playerFactionMap.get(playerId);
        return factionId.equals(pf);
    }

    public Faction getFactionAt(ServerLevel world, BlockPos pos) {
        if (!initialized) return null;
        TownCenterTracker tc = CivilServices.getTownCenterTracker();
        if (tc == null || !tc.isInitialized()) return null;

        String dim = world.dimension().identifier().toString();
        VoxelChunkKey vc = VoxelChunkKey.from(pos);
        List<TownCenterTracker.TownCenterEntry> entries = tc.entriesCoveringVc(dim, vc);
        if (entries.isEmpty()) return null;

        TownCenterTracker.TownCenterEntry best = null;
        double bestDistSq = Double.MAX_VALUE;
        double px = pos.getX() + 0.5;
        double pz = pos.getZ() + 0.5;
        for (TownCenterTracker.TownCenterEntry e : entries) {
            double dx = e.x() + 0.5 - px;
            double dz = e.z() + 0.5 - pz;
            double d2 = dx * dx + dz * dz;
            if (d2 < bestDistSq) {
                bestDistSq = d2;
                best = e;
            }
        }
        if (best == null || best.factionId() == null) return null;
        return factions.get(best.factionId());
    }
}
