# BlurpWorld

BlurpWorld is a Paper 26.2 fork and companion plugin for disposable, compressed, memory-backed worlds.
It is fully usable on its own. SubServer is an optional integration, not a runtime requirement.

## Current implementation

- `paper-api` exposes `Server#getBlurpWorldManager()`.
- Chunk, entity, and POI NBT are intercepted below Bukkit at Paper's region-storage boundary.
- Each record is compressed independently with Zstd for random access.
- Snapshots are immutable, checksummed, importable, exportable, and retained in a bounded in-memory store.
- Snapshot creation and import are cancelled when the configured compressed-memory budget would be exceeded; existing snapshots are never evicted automatically.
- Snapshot archives include world generation settings, seed, time, spawn, gamerules, weather, border, PDC, raids, scheduled events, and chunk tickets. Restored instances receive a fresh Paper world UUID so multiple clones can coexist.
- `blurpworld-plugin` provides creation, snapshot, restore, unload, listing, statistics, and live configuration reload commands.
- `.bws` archives can be imported directly into a world without occupying the retained-snapshot budget, and live worlds can be exported without creating a retained snapshot.
- Imported worlds can optionally be linked to their archive. Linked saves replace the archive atomically; linking is disabled by default.
- Snapshot preparation, rename, import, export, and statistics have asynchronous API variants backed by virtual threads.
- Newly created worlds use a void generator with a single bedrock spawn platform and do not keep spawn chunks loaded.
- When a memory world loads, the server itself brings every chunk visible from spawn (view distance + 1) to full status without ticking it, then hands the area over to the first player that enters. `BlurpWorldManager#spawnWarmup(World)` completes once that area is ready; creation, restore, and import wait for it before reporting completion, so the first join no longer triggers a burst of chunk loads.
- Memory chunk reads only hand the compressed record to Paper's I/O thread; Zstd and NBT decoding run on the parallel decompression workers.
- The void generators use a fixed biome, and Bukkit biome providers that ignore `BiomeParameterPoint` no longer pay for vanilla climate sampling, so generating the empty chunks around a map is cheap.
- Players still inside memory worlds are moved to a persistent fallback world before shutdown player data is saved.

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
/blurpworld import <archive> <world> [savable]
/blurpworld export <world|snapshot-id> <archive>
/blurpworld tp <world> [player]
/blurpworld reload
```

Archives are stored under `plugins/BlurpWorld/archives`. The optional `savable` argument accepts `true` or `false` and defaults to `false`. A linked world updates its `.bws` file whenever the world is saved and once more before unload or shutdown. The archive is the world's only persistent representation; no vanilla world folder is created.

`/blurpworld reload` rereads `plugins/BlurpWorld/config.yml` and immediately applies the snapshot budget and compression level. Existing snapshots remain intact even when their total size exceeds the new limit.

Snapshots are currently retained in process memory. Use the API's `exportSnapshot` and `importSnapshot` methods to connect a durable blob store.
