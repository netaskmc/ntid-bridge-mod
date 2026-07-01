# NTID Server Bridge NeoForge Mod

Server-side NeoForge 1.21.1 mod that connects a Minecraft server to the NTID
server bridge WebSocket API.

## Build

```powershell
.\gradlew.bat build
```

The jar is written to `build/libs/ntid_bridge-1.0.3.jar`.

## Configure

Install the jar on the NeoForge dedicated server, start once, then edit the
generated config:

```toml
# config/ntid_bridge-common.toml
enabled = true
bridgeUrl = "wss://serverbridge.netask.cc/minecraft"
serverToken = "<Server.token from the website admin UI>"
commandPermissionLevel = 4
reconnectSeconds = 10
forwardJoinLeave = true
messageLanguage = "ru"
minecraftAssetsDirectory = ""
downloadMinecraftLocalization = true
```

Restart the server after changing `serverToken`.

## Bridge Coverage

- Sends `minecraft.chat` for in-game chat.
- Sends `minecraft.system` for deaths, advancements, joins, and leaves.
- Formats death and advancement text using `messageLanguage`, defaulting to
  Russian (`ru`/`ru_ru`). Vanilla non-English translations are loaded from
  Minecraft client assets when available; set `minecraftAssetsDirectory` to an
  assets directory containing `indexes/` and `objects/` if auto-detection cannot
  find one. If still missing, `downloadMinecraftLocalization` downloads the
  needed vanilla language asset from Mojang and caches it under the server
  directory.
- Receives `discord.chat` and broadcasts it in Minecraft chat.
- Receives `backend.broadcast` and broadcasts it in Minecraft chat.
- Receives `backend.command`, executes it as the server, and responds with
  `backend.command.result`.
