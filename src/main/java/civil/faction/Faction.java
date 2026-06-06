package civil.faction;

import java.util.Set;
import java.util.UUID;

public record Faction(
        UUID id,
        String name,
        UUID owner,
        Set<UUID> members,
        int color,
        long createdAt
) {}
