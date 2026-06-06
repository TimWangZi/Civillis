package civil.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import civil.CivilMod;
import civil.CivilServices;
import civil.aura.SonarScanManager;
import civil.aura.SonarType;
import civil.faction.Faction;
import civil.faction.FactionManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.arguments.MessageArgument;

import java.util.UUID;

import static net.minecraft.commands.Commands.literal;

/**
 * Admin commands for Civil.
 */
public final class CivilAdminCommands {

    private CivilAdminCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("civil")
                .then(literal("rebuild")
                        .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                        .executes(ctx -> executeRebuild(ctx.getSource())))
                .then(literal("ring")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> executeRing(ctx.getSource(), SonarType.STATIC.getRadius()))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(3, 64))
                                .executes(ctx -> executeRing(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(literal("faction")
                        .then(literal("create")
                                .then(Commands.argument("name", MessageArgument.message())
                                        .executes(ctx -> executeFactionCreate(ctx.getSource(),
                                                ctx.getInput().substring(ctx.getInput().lastIndexOf(' ') + 1)))))
                        .then(literal("join")
                                .then(Commands.argument("name", MessageArgument.message())
                                        .executes(ctx -> executeFactionJoin(ctx.getSource(),
                                                ctx.getInput().substring(ctx.getInput().lastIndexOf(' ') + 1)))))
                        .then(literal("leave")
                                .executes(ctx -> executeFactionLeave(ctx.getSource())))
                        .then(literal("info")
                                .executes(ctx -> executeFactionInfo(ctx.getSource())))));
    }

    private static int executeRebuild(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("[Civil] Rebuild started..."), true);
        boolean ok = CivilMod.rebuildCivilData(source.getServer());
        source.sendSuccess(() -> Component.literal(ok ? "[Civil] Rebuild complete." : "[Civil] Rebuild failed."), true);
        return 1;
    }

    private static int executeRing(CommandSourceStack source, int radius) {
        if (source.getEntity() instanceof ServerPlayer player) {
            if (player.level() instanceof ServerLevel serverLevel) {
                SonarScanManager.startScan(player, serverLevel, player.blockPosition(), SonarType.STATIC, radius);
                source.sendSuccess(() -> Component.literal("[Civil] Sonar ring started (radius=" + radius + ")."), false);
                return 1;
            }
        }
        source.sendSuccess(() -> Component.literal("[Civil] This command must be run by a player."), false);
        return 0;
    }

    private static int executeFactionCreate(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Player-only command."));
            return 0;
        }
        FactionManager fm = CivilServices.getFactionManager();
        if (fm == null || !fm.isInitialized()) {
            source.sendFailure(Component.literal("Faction system not initialized."));
            return 0;
        }
        Faction existing = fm.getPlayerFaction(player.getUUID());
        if (existing != null) {
            source.sendFailure(Component.literal("You are already in a faction: " + existing.name()));
            return 0;
        }
        Faction f = fm.createFaction(player.getUUID(), name);
        if (f != null) {
            source.sendSuccess(() -> Component.literal("Faction '" + name + "' created."), false);
            return 1;
        }
        source.sendFailure(Component.literal("Failed to create faction."));
        return 0;
    }

    private static int executeFactionJoin(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Player-only command."));
            return 0;
        }
        FactionManager fm = CivilServices.getFactionManager();
        if (fm == null || !fm.isInitialized()) {
            source.sendFailure(Component.literal("Faction system not initialized."));
            return 0;
        }
        if (fm.getPlayerFaction(player.getUUID()) != null) {
            source.sendFailure(Component.literal("You are already in a faction."));
            return 0;
        }
        Faction joined = fm.joinFactionByName(player.getUUID(), name);
        if (joined != null) {
            source.sendSuccess(() -> Component.literal("Joined faction '" + joined.name() + "'."), false);
            return 1;
        }
        source.sendFailure(Component.literal("Faction '" + name + "' not found or not open for joining."));
        return 0;
    }

    private static int executeFactionLeave(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Player-only command."));
            return 0;
        }
        FactionManager fm = CivilServices.getFactionManager();
        if (fm == null || !fm.isInitialized()) {
            source.sendFailure(Component.literal("Faction system not initialized."));
            return 0;
        }
        Faction f = fm.getPlayerFaction(player.getUUID());
        if (f == null) {
            source.sendFailure(Component.literal("You are not in a faction."));
            return 0;
        }
        String factionName = f.name();
        if (fm.leave(player.getUUID())) {
            source.sendSuccess(() -> Component.literal("Left faction '" + factionName + "'."), false);
            return 1;
        }
        source.sendFailure(Component.literal("Failed to leave faction."));
        return 0;
    }

    private static int executeFactionInfo(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Player-only command."));
            return 0;
        }
        FactionManager fm = CivilServices.getFactionManager();
        if (fm == null || !fm.isInitialized()) {
            source.sendFailure(Component.literal("Faction system not initialized."));
            return 0;
        }
        Faction f = fm.getPlayerFaction(player.getUUID());
        if (f == null) {
            source.sendSuccess(() -> Component.literal("You are not in any faction."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "Faction: " + f.name() + " | Members: " + (f.members().size() + 1) +
                " | Owner: " + (f.owner().equals(player.getUUID()) ? "You" : f.owner().toString())), false);
        return 1;
    }
}
