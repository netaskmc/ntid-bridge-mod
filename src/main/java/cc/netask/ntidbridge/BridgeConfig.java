package cc.netask.ntidbridge;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class BridgeConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable the NTID server bridge WebSocket client.")
            .define("enabled", true);

    public static final ModConfigSpec.ConfigValue<String> BRIDGE_URL = BUILDER
            .comment("Bridge WebSocket endpoint. Use wss://serverbridge.netask.cc/minecraft for production.")
            .define("bridgeUrl", "wss://serverbridge.netask.cc/minecraft");

    public static final ModConfigSpec.ConfigValue<String> SERVER_TOKEN = BUILDER
            .comment("Server token from the website admin UI. Leave empty to keep the bridge disconnected.")
            .define("serverToken", "");

    public static final ModConfigSpec.IntValue COMMAND_PERMISSION_LEVEL = BUILDER
            .comment("Permission level used when executing backend.command messages.")
            .defineInRange("commandPermissionLevel", 4, 0, 4);

    public static final ModConfigSpec.IntValue RECONNECT_SECONDS = BUILDER
            .comment("Delay between reconnect attempts when the bridge socket closes or fails.")
            .defineInRange("reconnectSeconds", 10, 1, 300);

    public static final ModConfigSpec.BooleanValue FORWARD_JOIN_LEAVE = BUILDER
            .comment("Forward player join and leave events as minecraft.system messages.")
            .define("forwardJoinLeave", true);

    public static final ModConfigSpec.ConfigValue<String> MESSAGE_LANGUAGE = BUILDER
            .comment("Language used when forwarding server-generated messages like deaths and advancements. Use ru or ru_ru for Russian.")
            .define("messageLanguage", "ru");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private BridgeConfig() {
    }
}
