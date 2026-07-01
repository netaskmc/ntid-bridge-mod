package cc.netask.ntidbridge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(NtidBridgeMod.MODID)
public final class NtidBridgeMod {
    public static final String MODID = "ntid_bridge";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final BridgeClient bridgeClient = new BridgeClient();

    public NtidBridgeMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, BridgeConfig.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        bridgeClient.start(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        bridgeClient.stop();
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        bridgeClient.sendMinecraftChat(event.getPlayer().getGameProfile().getName(), event.getMessage().getString());
    }

    @SubscribeEvent
    public void onPlayerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            bridgeClient.sendSystem("join", player.getGameProfile().getName() + " joined the game", player);
        }
    }

    @SubscribeEvent
    public void onPlayerLeft(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            bridgeClient.sendSystem("leave", player.getGameProfile().getName() + " left the game", player);
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            bridgeClient.sendSystem("death", player.getCombatTracker().getDeathMessage(), player);
        }
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            var advancement = event.getAdvancement();
            var display = advancement.value().display().orElse(null);
            if (display == null || !display.shouldAnnounceChat()) {
                return;
            }

            bridgeClient.sendAdvancement(
                    player,
                    display.getTitle(),
                    display.getDescription()
            );
        }
    }
}
