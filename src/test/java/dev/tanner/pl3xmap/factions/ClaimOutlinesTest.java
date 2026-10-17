package dev.tanner.pl3xmap.factions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClaimOutlinesTest {
    @Test
    void emptyClaimsMakeNoAreas() {
        assertTrue(ClaimOutlines.trace(new long[0]).isEmpty());
    }

    @Test
    void singleChunkIsOneSquare() {
        List<ClaimOutlines.Area> areas = ClaimOutlines.trace(new long[]{ClaimOutlines.pack(0, 0)});
        assertEquals(1, areas.size());
        assertEquals(1, areas.getFirst().rings().size());
        assertFalse(areas.getFirst().rings().getFirst().hole());
        assertEquals(Set.of(
                new ClaimOutlines.Vertex(0, 0),
                new ClaimOutlines.Vertex(16, 0),
                new ClaimOutlines.Vertex(16, 16),
                new ClaimOutlines.Vertex(0, 16)
        ), set(areas.getFirst().rings().getFirst()));
    }

    @Test
    void adjacentChunksMerge() {
        long[] chunks = {ClaimOutlines.pack(0, 0), ClaimOutlines.pack(1, 0)};
        List<ClaimOutlines.Area> areas = ClaimOutlines.trace(chunks);
        assertEquals(1, areas.size());
        assertEquals(Set.of(
                new ClaimOutlines.Vertex(0, 0),
                new ClaimOutlines.Vertex(32, 0),
                new ClaimOutlines.Vertex(32, 16),
                new ClaimOutlines.Vertex(0, 16)
        ), set(areas.getFirst().rings().getFirst()));
    }

    @Test
    void diagonalChunksStaySeparate() {
        long[] chunks = {ClaimOutlines.pack(0, 0), ClaimOutlines.pack(1, 1)};
        List<ClaimOutlines.Area> areas = ClaimOutlines.trace(chunks);
        assertEquals(2, areas.size());
        assertEquals(1, areas.get(0).rings().size());
        assertEquals(1, areas.get(1).rings().size());
    }

    @Test
    void negativeChunkIsASquare() {
        List<ClaimOutlines.Area> areas = ClaimOutlines.trace(new long[]{ClaimOutlines.pack(-1, -1)});
        assertEquals(Set.of(
                new ClaimOutlines.Vertex(-16, -16),
                new ClaimOutlines.Vertex(0, -16),
                new ClaimOutlines.Vertex(0, 0),
                new ClaimOutlines.Vertex(-16, 0)
        ), set(areas.getFirst().rings().getFirst()));
    }

    @Test
    void enclosedGapBecomesAHole() {
        long[] chunks = new long[8];
        int index = 0;
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                if (x == 1 && z == 1) {
                    continue;
                }
                chunks[index++] = ClaimOutlines.pack(x, z);
            }
        }
        List<ClaimOutlines.Area> areas = ClaimOutlines.trace(chunks);
        assertEquals(1, areas.size());
        assertEquals(2, areas.getFirst().rings().size());
        ClaimOutlines.Ring hole = areas.getFirst().rings().get(1);
        assertTrue(hole.hole());
        assertEquals(Set.of(
                new ClaimOutlines.Vertex(16, 16),
                new ClaimOutlines.Vertex(32, 16),
                new ClaimOutlines.Vertex(32, 32),
                new ClaimOutlines.Vertex(16, 32)
        ), set(hole));
        assertEquals(Set.of(
                new ClaimOutlines.Vertex(0, 0),
                new ClaimOutlines.Vertex(48, 0),
                new ClaimOutlines.Vertex(48, 48),
                new ClaimOutlines.Vertex(0, 48)
        ), set(areas.getFirst().rings().getFirst()));
    }

    private static Set<ClaimOutlines.Vertex> set(ClaimOutlines.Ring ring) {
        return new HashSet<>(ring.vertices());
    }
}
