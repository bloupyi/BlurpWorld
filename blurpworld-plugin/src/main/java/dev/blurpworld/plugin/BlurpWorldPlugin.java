package dev.blurpworld.plugin;

import io.papermc.paper.blurpworld.BlurpWorldConfiguration;
import io.papermc.paper.blurpworld.BlurpWorldManager;
import io.papermc.paper.blurpworld.BlurpWorldSnapshot;
import io.papermc.paper.blurpworld.BlurpWorldStatistics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class BlurpWorldPlugin extends JavaPlugin implements Listener {

    private static final String ARCHIVE_EXTENSION = ".bws";

    private BlurpWorldManager worlds;
    private Path archiveDirectory;
    private ExecutorService ioExecutor;
    private final ConcurrentHashMap<String, LinkedArchive> linkedArchives = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> linkedWrites = new ConcurrentHashMap<>();
    private final Set<String> suppressedSaveEvents = ConcurrentHashMap.newKeySet();
    private final Set<String> archiveNames = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.archiveDirectory = this.getDataFolder().toPath().resolve("archives").toAbsolutePath().normalize();
        this.worlds = Bukkit.getServer().getBlurpWorldManager();
        this.worlds.configure(this.readConfiguration());
        Bukkit.getPluginManager().registerEvents(this, this);
        this.refreshArchiveNames();
    }

    @Override
    public void onDisable() {
        if (this.worlds == null) {
            if (this.ioExecutor != null) {
                this.ioExecutor.close();
            }
            return;
        }
        World fallback = Bukkit.getWorlds().stream().filter(world -> !this.worlds.isMemoryWorld(world)).findFirst().orElse(null);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.worlds.isMemoryWorld(player.getWorld()) && fallback != null) {
                player.teleport(fallback.getSpawnLocation());
                player.saveData();
            }
        }
        for (LinkedArchive link : List.copyOf(this.linkedArchives.values())) {
            World world = Bukkit.getWorld(link.worldName());
            if (world == null) {
                continue;
            }
            try {
                this.queueLinkedWrite(world, link, true).join();
            } catch (CompletionException exception) {
                this.getLogger().severe("Could not save linked world " + world.getName() + ": " + rootMessage(exception));
            }
        }
        if (this.ioExecutor != null) {
            this.ioExecutor.close();
        }
    }

    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        String key = normalize(event.getWorld().getName());
        LinkedArchive link = this.linkedArchives.get(key);
        if (link == null || this.suppressedSaveEvents.contains(key)) {
            return;
        }
        long started = System.nanoTime();
        Bukkit.getScheduler().runTask(this, () -> {
            this.queueLinkedWrite(event.getWorld(), link, false).whenComplete((ignored, error) -> {
                if (error != null) {
                    this.getLogger().severe("Could not update " + link.path() + ": " + rootMessage(error));
                } else {
                    this.getLogger().info("Updated linked world archive " + link.path().getFileName() + " in " + elapsed(started));
                }
            });
        });
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            this.usage(sender);
            return true;
        }
        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> this.create(sender, args);
                case "snapshot" -> this.snapshot(sender, args);
                case "rename" -> this.rename(sender, args);
                case "restore" -> this.restore(sender, args);
                case "unload" -> this.unload(sender, args);
                case "list" -> this.list(sender);
                case "stats" -> this.stats(sender, args);
                case "import" -> this.importArchive(sender, args);
                case "export" -> this.exportArchive(sender, args);
                case "tp", "teleport" -> this.teleport(sender, args);
                case "reload" -> this.reload(sender, args);
                default -> {
                    this.usage(sender);
                    yield true;
                }
            };
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage("BlurpWorld: " + exception.getMessage());
            return true;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("create", "snapshot", "rename", "restore", "unload", "list", "stats", "import", "export", "tp", "reload"), args[0]);
        }
        if (args.length == 2 && List.of("snapshot", "unload", "stats").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(this.memoryWorldNames(), args[1]);
        }
        if (args.length == 2 && List.of("rename", "restore").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(this.snapshotIds(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            return filter(List.copyOf(this.archiveNames), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("export")) {
            List<String> sources = new ArrayList<>(this.memoryWorldNames());
            sources.addAll(this.snapshotIds());
            return filter(sources, args[1]);
        }
        if (args.length == 2 && List.of("tp", "teleport").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getWorlds().stream().map(World::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("export")) {
            return filter(List.copyOf(this.archiveNames), args[2]);
        }
        if (args.length == 3 && List.of("tp", "teleport").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("import")) {
            return filter(List.of("false", "true"), args[3]);
        }
        return List.of();
    }

    private boolean create(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /blurpworld create <world>");
            return true;
        }
        String worldName = args[1];
        if (Bukkit.getWorld(worldName) != null) {
            throw new IllegalStateException("World already loaded: " + worldName);
        }
        long started = System.nanoTime();
        sender.sendMessage("World creation started for " + worldName);
        this.worlds.prepare(worldName);
        World world = Bukkit.createWorld(worldCreator(worldName));
        if (world == null) {
            this.worlds.discard(worldName);
            throw new IllegalStateException("Paper could not create " + worldName);
        }
        world.setAutoSave(false);
        // Memory worlds never generate terrain, so the spawn platform is placed directly
        Location spawn = world.getSpawnLocation();
        int platformY = spawn.getBlockY() - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.getBlockAt(x, platformY, z).setType(Material.BEDROCK, false);
            }
        }
        sender.sendMessage("Created compressed memory world " + worldName + " in " + elapsed(started));
        return true;
    }

    private boolean snapshot(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /blurpworld snapshot <world> [label]");
            return true;
        }
        World world = requireWorld(args[1]);
        String snapshotLabel = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : world.getName();
        long started = System.nanoTime();
        sender.sendMessage("Snapshot started for " + world.getName());
        this.worlds.snapshot(world, snapshotLabel).whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                sender.sendMessage("Snapshot failed: " + rootMessage(error));
                return;
            }
            sender.sendMessage("Snapshot " + snapshot.id() + " created in " + elapsed(started) + ": " + snapshot.chunkCount() + " records, " + formatBytes(snapshot.compressedBytes()));
        }));
        return true;
    }

    private boolean restore(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /blurpworld restore <snapshot-id> <world>");
            return true;
        }
        UUID snapshotId = UUID.fromString(args[1]);
        String worldName = args[2];
        long started = System.nanoTime();
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            this.evacuate(loaded);
            if (!Bukkit.unloadWorld(loaded, false)) {
                throw new IllegalStateException("Could not unload " + worldName);
            }
            this.worlds.discard(worldName);
        }
        sender.sendMessage("Restore preparation started for " + worldName);
        this.worlds.prepareAsync(worldName, snapshotId).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                sender.sendMessage("Restore failed: " + rootMessage(error));
                return;
            }
            this.finishRestore(sender, snapshotId, worldName, started);
        }));
        return true;
    }

    private void finishRestore(CommandSender sender, UUID snapshotId, String worldName, long started) {
        World restored = Bukkit.createWorld(worldCreator(worldName));
        if (restored == null) {
            this.worlds.discard(worldName);
            sender.sendMessage("Restore failed: Paper could not restore " + worldName);
            return;
        }
        restored.setAutoSave(false);
        sender.sendMessage("Restored snapshot " + snapshotId + " as " + worldName + " in " + elapsed(started));
    }

    private boolean rename(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /blurpworld rename <snapshot-id> [label]");
            return true;
        }
        UUID snapshotId = UUID.fromString(args[1]);
        BlurpWorldSnapshot current = this.worlds.snapshot(snapshotId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown snapshot: " + snapshotId));
        String snapshotLabel = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : current.sourceWorld();
        long started = System.nanoTime();
        sender.sendMessage("Snapshot rename started for " + snapshotId);
        this.worlds.renameSnapshot(snapshotId, snapshotLabel).whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                sender.sendMessage("Snapshot rename failed: " + rootMessage(error));
                return;
            }
            sender.sendMessage("Snapshot " + snapshot.id() + " renamed to " + snapshot.label() + " in " + elapsed(started));
        }));
        return true;
    }

    private boolean unload(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /blurpworld unload <world>");
            return true;
        }
        World world = requireWorld(args[1]);
        if (!this.worlds.isMemoryWorld(world)) {
            throw new IllegalArgumentException("World is not memory-backed: " + world.getName());
        }
        long started = System.nanoTime();
        LinkedArchive link = this.linkedArchives.get(normalize(world.getName()));
        if (link != null) {
            sender.sendMessage("Saving linked world " + world.getName() + " before unload");
            this.queueLinkedWrite(world, link, true).whenComplete((ignored, error) -> this.runMain(() -> {
                if (error != null) {
                    sender.sendMessage("Unload cancelled because the linked archive could not be saved: " + rootMessage(error));
                    return;
                }
                this.unloadNow(sender, world, started);
            }));
            return true;
        }
        this.unloadNow(sender, world, started);
        return true;
    }

    private boolean list(CommandSender sender) {
        sender.sendMessage("Memory worlds:");
        Bukkit.getWorlds().stream().filter(this.worlds::isMemoryWorld).forEach(world -> sender.sendMessage(" - " + world.getName()));
        sender.sendMessage("Snapshots:");
        for (BlurpWorldSnapshot snapshot : this.worlds.snapshots()) {
            sender.sendMessage(" - " + snapshot.id() + " " + snapshot.label() + " (" + snapshot.chunkCount() + " records, " + formatBytes(snapshot.compressedBytes()) + ")");
        }
        sender.sendMessage("Linked archives:");
        for (LinkedArchive link : this.linkedArchives.values()) {
            sender.sendMessage(" - " + link.worldName() + " -> " + link.path().getFileName());
        }
        return true;
    }

    private boolean stats(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /blurpworld stats <world>");
            return true;
        }
        String worldName = args[1];
        long started = System.nanoTime();
        sender.sendMessage("Statistics calculation started for " + worldName);
        this.worlds.statisticsAsync(worldName).whenComplete((statistics, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                sender.sendMessage("Statistics failed: " + rootMessage(error));
                return;
            }
            sendStatistics(sender, statistics);
            sender.sendMessage("Calculated in " + elapsed(started));
        }));
        return true;
    }

    private boolean importArchive(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4) {
            sender.sendMessage("Usage: /blurpworld import <archive> <world> [savable]");
            return true;
        }
        Path archive = this.resolveArchive(args[1]);
        String worldName = args[2];
        boolean savable = args.length == 4 && parseBoolean(args[3], "savable");
        if (Bukkit.getWorld(worldName) != null || this.worlds.isPrepared(worldName)) {
            throw new IllegalStateException("World already exists or is prepared: " + worldName);
        }
        long started = System.nanoTime();
        sender.sendMessage("Import started from " + archive.getFileName() + " to " + worldName);
        CompletableFuture.supplyAsync(() -> readArchive(archive), this.ioExecutor)
            .thenCompose(data -> this.worlds.prepareFromArchiveAsync(worldName, data))
            .thenCompose(snapshot -> this.onMain(() -> {
                World world = Bukkit.createWorld(worldCreator(worldName));
                if (world == null) {
                    this.worlds.discard(worldName);
                    throw new IllegalStateException("Paper could not import " + worldName);
                }
                world.setAutoSave(savable);
                if (savable) {
                    this.linkedArchives.put(normalize(worldName), new LinkedArchive(worldName, archive));
                }
                return world;
            }))
            .whenComplete((imported, error) -> this.runMain(() -> {
                if (error != null) {
                    if (Bukkit.getWorld(worldName) == null && this.worlds.isPrepared(worldName)) {
                        this.worlds.discard(worldName);
                    }
                    sender.sendMessage("Import failed: " + rootMessage(error));
                    return;
                }
                this.archiveNames.add(archive.getFileName().toString());
                String linkStatus = savable ? ", linked saves enabled" : ", unlinked";
                sender.sendMessage("Imported " + worldName + " from " + archive.getFileName() + " in " + elapsed(started) + linkStatus);
            }));
        return true;
    }

    private boolean exportArchive(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /blurpworld export <world|snapshot-id> <archive>");
            return true;
        }
        Path archive = this.resolveArchive(args[2]);
        long started = System.nanoTime();
        CompletableFuture<byte[]> exported;
        World world = Bukkit.getWorld(args[1]);
        if (world != null) {
            if (!this.worlds.isMemoryWorld(world)) {
                throw new IllegalArgumentException("World is not memory-backed: " + world.getName());
            }
            this.saveWorld(world);
            exported = this.worlds.exportWorldAsync(world, world.getName());
        } else {
            UUID snapshotId = UUID.fromString(args[1]);
            exported = this.worlds.exportSnapshotAsync(snapshotId);
        }
        sender.sendMessage("Export started to " + archive.getFileName());
        exported.thenCompose(data -> this.writeArchiveAsync(archive, data).thenApply(ignored -> data.length))
            .whenComplete((bytes, error) -> this.runMain(() -> {
                if (error != null) {
                    sender.sendMessage("Export failed: " + rootMessage(error));
                    return;
                }
                this.archiveNames.add(archive.getFileName().toString());
                sender.sendMessage("Exported " + formatBytes(bytes) + " to " + archive.getFileName() + " in " + elapsed(started));
            }));
        return true;
    }

    private boolean teleport(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage("Usage: /blurpworld tp <world> [player]");
            return true;
        }
        World world = requireWorld(args[1]);
        Player player;
        if (args.length == 3) {
            player = Bukkit.getPlayerExact(args[2]);
            if (player == null) {
                throw new IllegalArgumentException("Unknown online player: " + args[2]);
            }
        } else if (sender instanceof Player commandPlayer) {
            player = commandPlayer;
        } else {
            throw new IllegalArgumentException("Console must specify a player");
        }
        long started = System.nanoTime();
        sender.sendMessage("Teleport started for " + player.getName() + " to " + world.getName());
        player.teleportAsync(world.getSpawnLocation()).whenComplete((success, error) -> this.runMain(() -> {
            if (error != null) {
                sender.sendMessage("Teleport failed: " + rootMessage(error));
            } else if (!success) {
                sender.sendMessage("Teleport was cancelled after " + elapsed(started));
            } else {
                sender.sendMessage("Teleported " + player.getName() + " to " + world.getName() + " in " + elapsed(started));
            }
        }));
        return true;
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("Usage: /blurpworld reload");
            return true;
        }
        long started = System.nanoTime();
        this.reloadConfig();
        BlurpWorldConfiguration configuration = this.readConfiguration();
        this.worlds.configure(configuration);
        sender.sendMessage(
            "BlurpWorld configuration reloaded: snapshot budget " + formatBytes(configuration.maxSnapshotBytes())
                + ", compression level " + configuration.compressionLevel() + " in " + elapsed(started)
        );
        return true;
    }

    private BlurpWorldConfiguration readConfiguration() {
        long maxMegabytes = this.getConfig().getLong("max-snapshot-megabytes", 512L);
        if (maxMegabytes < 1L) {
            throw new IllegalArgumentException("max-snapshot-megabytes must be positive");
        }
        long maxBytes;
        try {
            maxBytes = Math.multiplyExact(maxMegabytes, 1024L * 1024L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("max-snapshot-megabytes is too large", exception);
        }
        int compressionLevel = this.getConfig().getInt("compression-level", 3);
        return new BlurpWorldConfiguration(maxBytes, compressionLevel);
    }

    private void unloadNow(CommandSender sender, World world, long started) {
        this.evacuate(world);
        if (!Bukkit.unloadWorld(world, false)) {
            sender.sendMessage("Could not unload " + world.getName());
            return;
        }
        this.linkedArchives.remove(normalize(world.getName()));
        this.linkedWrites.remove(normalize(world.getName()));
        this.worlds.discard(world.getName());
        sender.sendMessage("Unloaded and released " + world.getName() + " in " + elapsed(started));
    }

    private static void sendStatistics(CommandSender sender, BlurpWorldStatistics statistics) {
        double ratio = statistics.uncompressedBytes() == 0L ? 1.0D : (double) statistics.compressedBytes() / statistics.uncompressedBytes();
        sender.sendMessage("World: " + statistics.worldName());
        sender.sendMessage("Stores: " + statistics.storageCount() + ", records: " + statistics.chunkCount());
        sender.sendMessage("Memory: " + formatBytes(statistics.compressedBytes()) + " compressed from " + formatBytes(statistics.uncompressedBytes()));
        sender.sendMessage("Ratio: " + String.format(Locale.ROOT, "%.2f%%", ratio * 100.0D));
    }

    private void evacuate(World world) {
        World fallback = Bukkit.getWorlds().stream()
            .filter(candidate -> candidate != world)
            .filter(candidate -> !this.worlds.isMemoryWorld(candidate))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No persistent fallback world is loaded"));
        for (Player player : new ArrayList<>(world.getPlayers())) {
            player.teleport(fallback.getSpawnLocation());
        }
    }

    private CompletableFuture<Void> queueLinkedWrite(World world, LinkedArchive link, boolean saveFirst) {
        if (saveFirst) {
            this.saveWorld(world);
        }
        String key = normalize(world.getName());
        return this.linkedWrites.compute(key, (ignored, current) -> {
            CompletableFuture<Void> base = current == null
                ? CompletableFuture.completedFuture(null)
                : current.handle((result, error) -> null);
            return base.thenCompose(result -> this.worlds.exportWorldAsync(world, link.worldName()))
                .thenCompose(data -> this.writeArchiveAsync(link.path(), data));
        });
    }

    private void saveWorld(World world) {
        String key = normalize(world.getName());
        this.suppressedSaveEvents.add(key);
        try {
            world.save(true);
        } finally {
            this.suppressedSaveEvents.remove(key);
        }
    }

    private CompletableFuture<Void> writeArchiveAsync(Path target, byte[] data) {
        byte[] archive = data.clone();
        return CompletableFuture.runAsync(() -> writeArchive(target, archive), this.ioExecutor);
    }

    private static void writeArchive(Path target, byte[] data) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
            Files.write(temporary, data, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static byte[] readArchive(Path archive) {
        try {
            return Files.readAllBytes(archive);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private Path resolveArchive(String value) {
        String fileName = value.toLowerCase(Locale.ROOT).endsWith(ARCHIVE_EXTENSION) ? value : value + ARCHIVE_EXTENSION;
        if (!fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.bws")) {
            throw new IllegalArgumentException("Archive name must contain only letters, numbers, dots, underscores, or hyphens");
        }
        Path resolved = this.archiveDirectory.resolve(fileName).normalize();
        if (!resolved.startsWith(this.archiveDirectory)) {
            throw new IllegalArgumentException("Archive path escapes the BlurpWorld archive directory");
        }
        return resolved;
    }

    private void refreshArchiveNames() {
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(this.archiveDirectory);
                try (Stream<Path> files = Files.list(this.archiveDirectory)) {
                    files.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(ARCHIVE_EXTENSION))
                        .forEach(this.archiveNames::add);
                }
            } catch (IOException exception) {
                this.getLogger().warning("Could not list BlurpWorld archives: " + exception.getMessage());
            }
        }, this.ioExecutor);
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> action) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(action.get());
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                result.complete(action.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        });
        return result;
    }

    private void runMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (this.isEnabled()) {
            Bukkit.getScheduler().runTask(this, action);
        }
    }

    private List<String> memoryWorldNames() {
        return Bukkit.getWorlds().stream().filter(this.worlds::isMemoryWorld).map(World::getName).toList();
    }

    private List<String> snapshotIds() {
        return this.worlds.snapshots().stream().map(snapshot -> snapshot.id().toString()).toList();
    }

    private static boolean parseBoolean(String value, String option) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(option + " must be true or false");
    }

    private static World requireWorld(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            throw new IllegalArgumentException("Unknown world: " + name);
        }
        return world;
    }

    private static WorldCreator worldCreator(String name) {
        return new WorldCreator(name)
            .generator(new BlurpVoidGenerator());
    }

    private static List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0D;
        if (kib < 1024.0D) {
            return String.format(Locale.ROOT, "%.1f KiB", kib);
        }
        return String.format(Locale.ROOT, "%.1f MiB", kib / 1024.0D);
    }

    private static String elapsed(long started) {
        double milliseconds = (System.nanoTime() - started) / 1_000_000.0D;
        if (milliseconds < 1_000.0D) {
            return String.format(Locale.ROOT, "%.1f ms", milliseconds);
        }
        return String.format(Locale.ROOT, "%.2f s", milliseconds / 1_000.0D);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("/blurpworld create <world>");
        sender.sendMessage("/blurpworld snapshot <world> [label]");
        sender.sendMessage("/blurpworld rename <snapshot-id> [label]");
        sender.sendMessage("/blurpworld restore <snapshot-id> <world>");
        sender.sendMessage("/blurpworld unload <world>");
        sender.sendMessage("/blurpworld list");
        sender.sendMessage("/blurpworld stats <world>");
        sender.sendMessage("/blurpworld import <archive> <world> [savable]");
        sender.sendMessage("/blurpworld export <world|snapshot-id> <archive>");
        sender.sendMessage("/blurpworld tp <world> [player]");
        sender.sendMessage("/blurpworld reload");
    }

    private record LinkedArchive(String worldName, Path path) {
    }

}
