package io.papermc.paper.blurpworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

@Normal
class CraftBlurpWorldManagerTest {

    @Test
    void acceptsSnapshotWithinBudget() {
        assertEquals(900L, CraftBlurpWorldManager.checkedProjectedSnapshotBytes(600L, 0L, 300L, 1_000L));
    }

    @Test
    void rejectsSnapshotWithoutRemovingExistingUsage() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> CraftBlurpWorldManager.checkedProjectedSnapshotBytes(900L, 0L, 200L, 1_000L)
        );

        assertEquals("Snapshot cancelled: 200 compressed bytes required, but only 100 remain", exception.getMessage());
    }

    @Test
    void replacementOnlyCountsTheSizeDifference() {
        assertEquals(950L, CraftBlurpWorldManager.checkedProjectedSnapshotBytes(900L, 200L, 250L, 1_000L));
    }
}
