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
- Newly created worlds get a single bedrock spawn platform placed by the plugin and do not keep spawn chunks loaded.
- Memory worlds never run terrain generation. A chunk missing from the store is loaded directly as an empty full chunk, like a saved void chunk and like ASP slime worlds, so no generator, biome noise, structure placement or lighting pass runs. Nothing needs preloading: a 6x6 chunk archive loads in about 50 ms and the 529 chunks a joining player asks for are ready in 200-300 ms.
- `prepareAsync` and `prepareFromArchiveAsync` also build the level's Spigot and Paper configurations, decode its saved data and create its noise state off the server thread, so `Bukkit#createWorld` only assembles the level: about 2-3 ms on the server thread (worst case under 5 ms when the server is otherwise idle). The synchronous `prepare` methods keep building them in `createWorld`.
- Memory worlds skip the noise router, the stronghold ring computation, the spawn chunk wait, the load progress logging and the rewrite of `spigot.yml`. Chunks read from memory at the current data version stay clean, so unloading an unmodified chunk serializes nothing, and untouched empty chunks are never stored.
- Paper world defaults, the paletted container factory and the list of biome attributes are computed once instead of for each level (the container factory also for each chunk).
- Memory chunk reads only hand the compressed record to Paper's I/O thread; Zstd and NBT decoding run on the parallel decompression workers.
- Bukkit biome providers that ignore `BiomeParameterPoint` no longer pay for vanilla climate sampling.
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
