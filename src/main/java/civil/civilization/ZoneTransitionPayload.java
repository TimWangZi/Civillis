package civil.civilization;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client: zone semantic transition for HUD (epoch ordering + state id + faction context).
 */
public record ZoneTransitionPayload(long epoch, int stateId, String factionName, boolean isOwnFaction) implements CustomPacketPayload {

    public static final Type<ZoneTransitionPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath("civil", "zone_transition"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ZoneTransitionPayload> CODEC =
            StreamCodec.ofMember(ZoneTransitionPayload::encode, ZoneTransitionPayload::decode);

    private static void encode(ZoneTransitionPayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeLong(payload.epoch);
        buf.writeByte(payload.stateId);
        buf.writeUtf(payload.factionName);
        buf.writeBoolean(payload.isOwnFaction);
    }

    private static ZoneTransitionPayload decode(RegistryFriendlyByteBuf buf) {
        return new ZoneTransitionPayload(buf.readLong(), buf.readByte() & 0xFF, buf.readUtf(), buf.readBoolean());
    }

    public ZoneSemanticState state() {
        return ZoneSemanticState.fromId(stateId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
