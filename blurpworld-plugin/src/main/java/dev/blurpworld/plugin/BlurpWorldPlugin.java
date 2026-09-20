package dev.blurpworld.plugin;

import io.papermc.paper.blurpworld.BlurpWorldConfiguration;
import io.papermc.paper.blurpworld.BlurpWorldManager;
import io.papermc.paper.blurpworld.BlurpWorldSnapshot;
import io.papermc.paper.blurpworld.BlurpWorldStatistics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class BlurpWorldPlugin extends JavaPlugin {

    private BlurpWorldManager worlds;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.worlds = Bukkit.getServer().getBlurpWorldManager();
        long maxBytes = Math.multiplyExact(this.getConfig().getLong("max-snapshot-megabytes", 512L), 1024L * 1024L);
        int compressionLevel = this.getConfig().getInt("compression-level", 3);
        this.worlds.configure(new BlurpWorldConfiguration(maxBytes, compressionLevel));
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
            return filter(List.of("create", "snapshot", "rename", "restore", "unload", "list", "stats"), args[0]);
        }
        if (args.length == 2 && List.of("snapshot", "unload", "stats").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getWorlds().stream().map(World::getName).toList(), args[1]);
        }
        if (args.length == 2 && List.of("rename", "restore").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(this.worlds.snapshots().stream().map(snapshot -> snapshot.id().toString()).toList(), args[1]);
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
        this.worlds.prepare(worldName);
        World world = Bukkit.createWorld(worldCreator(worldName));
        if (world == null) {
            this.worlds.discard(worldName);
            throw new IllegalStateException("Paper could not create " + worldName);
        }
        world.setAutoSave(false);
        sender.sendMessage("Created compressed memory world " + worldName);
        return true;
    }

    private boolean snapshot(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /blurpworld snapshot <world> [label]");
            return true;
        }
        World world = requireWorld(args[1]);
        String snapshotLabel = args.length > 2 ? String.join(" ", List.of(args).subList(2, args.length)) : world.getName();
        sender.sendMessage("Snapshot started for " + world.getName());
        this.worlds.snapshot(world, snapshotLabel).whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                sender.sendMessage("Snapshot failed: " + rootMessage(error));
                return;
            }
            sender.sendMessage("Snapshot " + snapshot.id() + " created: " + snapshot.chunkCount() + " records, " + formatBytes(snapshot.compressedBytes()));
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
            this.finishRestore(sender, snapshotId, worldName);
        }));
        return true;
    }

    private void finishRestore(CommandSender sender, UUID snapshotId, String worldName) {
        World restored = Bukkit.createWorld(worldCreator(worldName));
        if (restored == null) {
            this.worlds.discard(worldName);
            sender.sendMessage("Restore failed: Paper could not restore " + worldName);
            return;
        }
        restored.setAutoSave(false);
        sender.sendMessage("Restored snapshot " + snapshotId + " as " + worldName);
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
        sender.sendMessage("Snapshot rename started for " + snapshotId);
        this.worlds.renameSnapshot(snapshotId, snapshotLabel).whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                sender.sendMessage("Snapshot rename failed: " + rootMessage(error));
                return;
            }
            sender.sendMessage("Snapshot " + snapshot.id() + " renamed to " + snapshot.label());
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
        this.evacuate(world);
        if (!Bukkit.unloadWorld(world, false)) {
            throw new IllegalStateException("Could not unload " + world.getName());
        }
        this.worlds.discard(world.getName());
        sender.sendMessage("Unloaded and released " + world.getName());
        return true;
    }

    private boolean list(CommandSender sender) {
        sender.sendMessage("Memory worlds:");
        Bukkit.getWorlds().stream().filter(this.worlds::isMemoryWorld).forEach(world -> sender.sendMessage(" - " + world.getName()));
        sender.sendMessage("Snapshots:");
        for (BlurpWorldSnapshot snapshot : this.worlds.snapshots()) {
            sender.sendMessage(" - " + snapshot.id() + " " + snapshot.label() + " (" + snapshot.chunkCount() + " records, " + formatBytes(snapshot.compressedBytes()) + ")");
        }
        return true;
    }

    private boolean stats(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /blurpworld stats <world>");
            return true;
        }
        String worldName = args[1];
        sender.sendMessage("Statistics calculation started for " + worldName);
        this.worlds.statisticsAsync(worldName).whenComplete((statistics, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) {
                sender.sendMessage("Statistics failed: " + rootMessage(error));
                return;
            }
            sendStatistics(sender, statistics);
        }));
        return true;
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

    private void usage(CommandSender sender) {
        sender.sendMessage("/blurpworld create <world>");
        sender.sendMessage("/blurpworld snapshot <world> [label]");
        sender.sendMessage("/blurpworld rename <snapshot-id> [label]");
        sender.sendMessage("/blurpworld restore <snapshot-id> <world>");
        sender.sendMessage("/blurpworld unload <world>");
        sender.sendMessage("/blurpworld list");
        sender.sendMessage("/blurpworld stats <world>");
    }
}
