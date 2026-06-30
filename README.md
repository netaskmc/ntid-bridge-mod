# NTID Server Bridge NeoForge Mod

Server-side NeoForge 1.21.1 mod that connects a Minecraft server to the NTID
server bridge WebSocket API described in
`C:/Users/sasha/source/repos/ntid/SERVER_BRIDGE.md`.

## Build

```powershell
.\gradlew.bat build
```

The jar is written to `build/libs/ntid_bridge-1.0.0.jar`.

## Configure

Install the jar on the NeoForge dedicated server, start once, then edit the
generated server config:

```toml
# serverconfig/ntid_bridge-server.toml inside the world folder
enabled = true
bridgeUrl = "wss://serverbridge.netask.cc/minecraft"
serverToken = "<Server.token from the website admin UI>"
commandPermissionLevel = 4
reconnectSeconds = 10
forwardJoinLeave = true
```

Restart the server after changing `serverToken`.

## Bridge Coverage

- Sends `minecraft.chat` for in-game chat.
- Sends `minecraft.system` for deaths, advancements, joins, and leaves.
- Receives `discord.chat` and broadcasts it in Minecraft chat.
- Receives `backend.broadcast` and broadcasts it in Minecraft chat.
- Receives `backend.command`, executes it as the server, and responds with
  `backend.command.result`.
