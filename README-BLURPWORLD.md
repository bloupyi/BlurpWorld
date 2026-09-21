# BlurpWorld

BlurpWorld is a Paper 26.2 fork and companion plugin for disposable, compressed, memory-backed worlds.

## Current implementation

- `paper-api` exposes `Server#getBlurpWorldManager()`.
- Chunk, entity, and POI NBT are intercepted below Bukkit at Paper's region-storage boundary.
- Each record is compressed independently with Zstd for random access.
- Snapshots are immutable, checksummed, importable, exportable, and retained in a bounded in-memory store.
- Snapshot creation and import are cancelled when the configured compressed-memory budget would be exceeded; existing snapshots are never evicted automatically.
- Snapshot archives include world generation settings, seed, time, spawn, gamerules, weather, border, PDC, raids, scheduled events, and chunk tickets. Restored instances receive a fresh Paper world UUID so multiple clones can coexist.
- `blurpworld-plugin` provides creation, snapshot, restore, unload, listing, and statistics commands.
- Snapshot preparation, rename, import, export, and statistics have asynchronous API variants backed by virtual threads.
- Newly created worlds use a void generator with a single bedrock spawn platform and do not keep spawn chunks loaded.

Prepared BlurpWorld dimensions do not create a world folder, `level.dat`, `paper-world.yml`, region files, or saved-data files. Paper's world defaults are materialized in memory, while restored metadata is injected from the snapshot before the `ServerLevel` is constructed.

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
/blurpworld rename <snapshot-id> [label]
/blurpworld restore <snapshot-id> <world>
/blurpworld unload <world>
/blurpworld list
/blurpworld stats <world>
```

Snapshots are currently retained in process memory. Use the API's `exportSnapshot` and `importSnapshot` methods to connect a durable blob store.
