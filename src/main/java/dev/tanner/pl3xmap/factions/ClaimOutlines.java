package dev.tanner.pl3xmap.factions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns claimed chunks into map polygons.
 * <p>
 * Each claimed chunk contributes the block edges that do not touch another claimed chunk.
 * Edges keep the claim on their left, so the outside of a claim traces one way and a hole
 * traces the other. Corner-touching claims stay separate polygons.
 */
public final class ClaimOutlines {
    public static final int BLOCKS_PER_CHUNK = 16;

    private ClaimOutlines() {
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long packed) {
        return (int) (packed >> 32);
    }

    public static int chunkZ(long packed) {
        return (int) packed;
    }

    public static List<Area> trace(long[] chunks) {
        if (chunks.length == 0) {
            return List.of();
        }
        Set<Long> claimed = HashSet.newHashSet(chunks.length);
        for (long packed : chunks) {
            claimed.add(packed);
        }
        Map<Vertex, List<Vertex>> next = new HashMap<>();
        int edges = 0;
        for (long packed : claimed) {
            int chunkX = chunkX(packed);
            int chunkZ = chunkZ(packed);
            int x = chunkX * BLOCKS_PER_CHUNK;
            int z = chunkZ * BLOCKS_PER_CHUNK;
            if (!claimed.contains(pack(chunkX, chunkZ - 1))) {
                edge(next, x + BLOCKS_PER_CHUNK, z, x, z);
                edges++;
            }
            if (!claimed.contains(pack(chunkX + 1, chunkZ))) {
                edge(next, x + BLOCKS_PER_CHUNK, z + BLOCKS_PER_CHUNK, x + BLOCKS_PER_CHUNK, z);
                edges++;
            }
            if (!claimed.contains(pack(chunkX, chunkZ + 1))) {
                edge(next, x, z + BLOCKS_PER_CHUNK, x + BLOCKS_PER_CHUNK, z + BLOCKS_PER_CHUNK);
                edges++;
            }
            if (!claimed.contains(pack(chunkX - 1, chunkZ))) {
                edge(next, x, z, x, z + BLOCKS_PER_CHUNK);
                edges++;
            }
        }

        List<Ring> outers = new ArrayList<>();
        List<Ring> holes = new ArrayList<>();
        while (!next.isEmpty()) {
            Vertex start = next.keySet().iterator().next();
            List<Vertex> loop = new ArrayList<>();
            Vertex previous = null;
            Vertex cursor = start;
            int guard = edges + 2;
            while (guard-- > 0) {
                List<Vertex> outs = next.get(cursor);
                if (outs == null || outs.isEmpty()) {
                    break;
                }
                Vertex end = previous == null ? outs.removeFirst() : takeLeftmost(previous, cursor, outs);
                if (outs.isEmpty()) {
                    next.remove(cursor);
                }
                loop.add(cursor);
                previous = cursor;
                cursor = end;
                if (cursor.equals(start)) {
                    break;
                }
            }
            List<Vertex> simplified = simplify(loop);
            if (simplified.size() < 3) {
                continue;
            }
            Ring ring = new Ring(simplified, signedArea(simplified) >= 0.0D);
            if (ring.hole()) {
                holes.add(ring);
            } else {
                outers.add(ring);
            }
        }

        if (outers.isEmpty()) {
            return List.of();
        }
        List<List<Ring>> grouped = new ArrayList<>();
        for (Ring ignored : outers) {
            grouped.add(new ArrayList<>());
        }
        holes.sort(Comparator.comparingDouble(ring -> Math.abs(signedArea(ring.vertices()))));
        for (Ring hole : holes) {
            Vertex sample = hole.vertices().get(0);
            double x = sample.x() + 0.5D;
            double z = sample.z() + 0.5D;
            // Step inside the hole. The first edge walks with the hole on the right of a negative-area
            // outer, so a half-block nudge along the left of the reversed first edge lands in the hole.
            if (hole.vertices().size() >= 2) {
                Vertex nextVertex = hole.vertices().get(1);
                double dx = nextVertex.x() - sample.x();
                double dz = nextVertex.z() - sample.z();
                double length = Math.hypot(dx, dz);
                if (length > 0.0D) {
                    x = sample.x() + dx / length * 0.5D + dz / length * 0.5D;
                    z = sample.z() + dz / length * 0.5D - dx / length * 0.5D;
                }
            }
            int owner = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < outers.size(); i++) {
                Ring outer = outers.get(i);
                double area = Math.abs(signedArea(outer.vertices()));
                if (area < best && contains(outer.vertices(), x, z)) {
                    best = area;
                    owner = i;
                }
            }
            if (owner >= 0) {
                grouped.get(owner).add(hole);
            }
        }

        List<Area> areas = new ArrayList<>(outers.size());
        for (int i = 0; i < outers.size(); i++) {
            List<Ring> rings = new ArrayList<>(1 + grouped.get(i).size());
            rings.add(outers.get(i));
            rings.addAll(grouped.get(i));
            areas.add(new Area(List.copyOf(rings)));
        }
        return List.copyOf(areas);
    }

    private static void edge(Map<Vertex, List<Vertex>> next, int x1, int z1, int x2, int z2) {
        next.computeIfAbsent(new Vertex(x1, z1), key -> new ArrayList<>()).add(new Vertex(x2, z2));
    }

    /**
     * Claims stay on the left of each edge. When two claims only meet at a corner, pick the
     * outgoing edge that turns leftmost so the walks do not jump from one claim into the other.
     */
    private static Vertex takeLeftmost(Vertex previous, Vertex current, List<Vertex> outs) {
        int inX = current.x() - previous.x();
        int inZ = current.z() - previous.z();
        int best = 0;
        double bestAngle = Double.POSITIVE_INFINITY;
        for (int i = 0; i < outs.size(); i++) {
            Vertex candidate = outs.get(i);
            int outX = candidate.x() - current.x();
            int outZ = candidate.z() - current.z();
            double cross = (double) inX * outZ - (double) inZ * outX;
            double dot = (double) inX * outX + (double) inZ * outZ;
            double angle = Math.atan2(cross, dot);
            if (angle < bestAngle) {
                bestAngle = angle;
                best = i;
            }
        }
        return outs.remove(best);
    }

    private static List<Vertex> simplify(List<Vertex> loop) {
        if (loop.size() < 3) {
            return loop;
        }
        List<Vertex> simplified = new ArrayList<>();
        int count = loop.size();
        for (int i = 0; i < count; i++) {
            Vertex previous = loop.get((i + count - 1) % count);
            Vertex current = loop.get(i);
            Vertex next = loop.get((i + 1) % count);
            int dx1 = current.x() - previous.x();
            int dz1 = current.z() - previous.z();
            int dx2 = next.x() - current.x();
            int dz2 = next.z() - current.z();
            long cross = (long) dx1 * dz2 - (long) dz1 * dx2;
            long dot = (long) dx1 * dx2 + (long) dz1 * dz2;
            if (cross == 0L && dot > 0L) {
                continue;
            }
            simplified.add(current);
        }
        return simplified;
    }

    static double signedArea(List<Vertex> vertices) {
        double sum = 0.0D;
        for (int i = 0; i < vertices.size(); i++) {
            Vertex current = vertices.get(i);
            Vertex next = vertices.get((i + 1) % vertices.size());
            sum += (double) current.x() * next.z() - (double) next.x() * current.z();
        }
        return sum * 0.5D;
    }

    private static boolean contains(List<Vertex> ring, double x, double z) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            Vertex current = ring.get(i);
            Vertex previous = ring.get(j);
            if ((current.z() > z) != (previous.z() > z)) {
                double intersect = (previous.x() - current.x()) * (z - current.z()) / (double) (previous.z() - current.z()) + current.x();
                if (x < intersect) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    public record Vertex(int x, int z) {
    }

    public record Ring(List<Vertex> vertices, boolean hole) {
        public Ring {
            vertices = List.copyOf(vertices);
        }
    }

    public record Area(List<Ring> rings) {
        public Area {
            rings = List.copyOf(rings);
        }
    }
}
