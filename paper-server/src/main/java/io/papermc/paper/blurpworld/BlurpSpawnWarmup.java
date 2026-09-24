package io.papermc.paper.blurpworld;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import org.bukkit.Location;

/**
 * Loads the chunks a player sees at the spawn of a memory world as soon as the world is created, so that entering it
 * does not trigger a burst of chunk loads and generations. The chunks are held at full status without being ticked,
 * then handed over to the first player's own chunk loader.
 */
public final class BlurpSpawnWarmup {

    private static final TicketType WARMUP = ChunkSystemTicketType.create(
        "blurpworld:spawn_warmup", Long::compareTo, TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING
    );
    // Keeps the area loaded while the first player's chunk loader, which is rate limited, adds its own tickets.
    private static final TicketType HANDOFF = ChunkSystemTicketType.create(
        "blurpworld:spawn_handoff", Long::compareTo, 30L * 20L, TicketType.FLAG_LOADING
    );
    private static final int MAX_RADIUS = 32;
    private static final AtomicLong IDS = new AtomicLong();
    private static final Map<ServerLevel, Warmup> WARMUPS = new ConcurrentHashMap<>();

    private BlurpSpawnWarmup() {
    }

    public static void start(ServerLevel level) {
        TickThread.ensureTickThread("Cannot warm a memory world asynchronously");
        Location spawn = level.getWorld().getSpawnLocation();
        // The player chunk loader brings every chunk within view distance + 1 to full status.
        int radius = Math.min(MAX_RADIUS, level.getWorld().getViewDistance() + 1);
        Warmup warmup = new Warmup(IDS.incrementAndGet(), spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4, radius);
        Warmup previous = WARMUPS.put(level, warmup);
        if (previous != null) {
            previous.release(level);
        }

        ChunkTaskScheduler scheduler = level.moonrise$getChunkTaskScheduler();
        ChunkHolderManager holders = scheduler.chunkHolderManager;
        warmup.forEachChunk((chunkX, chunkZ) ->
            holders.addTicketAtLevel(WARMUP, chunkX, chunkZ, ChunkHolderManager.FULL_LOADED_TICKET_LEVEL, warmup.id)
        );
        holders.processTicketUpdates();
        warmup.forEachChunk((chunkX, chunkZ) ->
            scheduler.scheduleTickingState(chunkX, chunkZ, FullChunkStatus.FULL, false, Priority.NORMAL, ignored -> warmup.chunkReady())
        );
    }

    public static void onPlayerAdded(ServerLevel level) {
        Warmup warmup = WARMUPS.remove(level);
        if (warmup != null) {
            warmup.release(level);
        }
    }

    public static void forget(ServerLevel level) {
        Warmup warmup = WARMUPS.remove(level);
        if (warmup != null) {
            warmup.completion.complete(null);
        }
    }

    public static CompletableFuture<Void> completion(ServerLevel level) {
        Warmup warmup = WARMUPS.get(level);
        return warmup == null ? CompletableFuture.completedFuture(null) : warmup.completion.copy();
    }

    @FunctionalInterface
    private interface ChunkAction {
        void accept(int chunkX, int chunkZ);
    }

    private static final class Warmup {

        private final Long id;
        private final int centerX;
        private final int centerZ;
        private final int radius;
        private final AtomicInteger pending;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private Warmup(long id, int centerX, int centerZ, int radius) {
            this.id = id;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.pending = new AtomicInteger((2 * radius + 1) * (2 * radius + 1));
        }

        private void forEachChunk(ChunkAction action) {
            for (int chunkX = this.centerX - this.radius; chunkX <= this.centerX + this.radius; chunkX++) {
                for (int chunkZ = this.centerZ - this.radius; chunkZ <= this.centerZ + this.radius; chunkZ++) {
                    action.accept(chunkX, chunkZ);
                }
            }
        }

        private void chunkReady() {
            if (this.pending.decrementAndGet() == 0) {
                this.completion.complete(null);
            }
        }

        private void release(ServerLevel level) {
            ChunkHolderManager holders = level.moonrise$getChunkTaskScheduler().chunkHolderManager;
            int ticketLevel = ChunkHolderManager.FULL_LOADED_TICKET_LEVEL;
            this.forEachChunk((chunkX, chunkZ) -> {
                holders.addTicketAtLevel(HANDOFF, chunkX, chunkZ, ticketLevel, this.id);
                holders.removeTicketAtLevel(WARMUP, chunkX, chunkZ, ticketLevel, this.id);
            });
            this.completion.complete(null);
        }
    }
}
