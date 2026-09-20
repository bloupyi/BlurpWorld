# BlurpWorld

BlurpWorld is a Paper 1.21.8 fork and companion plugin for disposable, compressed, memory-backed worlds.

## Current implementation

- `paper-api` exposes `Server#getBlurpWorldManager()`.
- Chunk, entity, and POI NBT are intercepted below Bukkit at Paper's region-storage boundary.
- Each record is compressed independently with Zstd for random access.
- Snapshots are immutable, checksummed, importable, exportable, and retained in a bounded in-memory LRU.
- `blurpworld-plugin` provides creation, snapshot, restore, unload, listing, and statistics commands.
- Newly created worlds use a void generator with a single bedrock spawn platform and do not keep spawn chunks loaded.

World metadata such as `level.dat`, maps, and scoreboard state still uses Paper's regular world metadata path. Region payloads do not use Anvil files for prepared BlurpWorld worlds. Full metadata virtualization is the next storage milestone.

## Build

```powershell
.\gradlew.bat applyPatches
.\gradlew.bat :paper-server:createMojmapPaperclipJar :blurpworld-plugin:build
```

The server jar is produced under `paper-server/build/libs`. The plugin jar is produced under `blurpworld-plugin/build/libs`.

## Commands

```text
/blurpworld create <world>
/blurpworld snapshot <world> [label]
/blurpworld restore <snapshot-id> <world>
/blurpworld unload <world>
/blurpworld list
/blurpworld stats <world>
```

Snapshots are currently retained in process memory. Use the API's `exportSnapshot` and `importSnapshot` methods to connect a durable blob store.
