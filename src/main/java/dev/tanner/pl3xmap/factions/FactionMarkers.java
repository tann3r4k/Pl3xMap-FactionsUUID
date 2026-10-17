package dev.tanner.pl3xmap.factions;

import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.Factions;
import dev.kitteh.factions.event.FactionDisbandEvent;
import dev.kitteh.factions.event.FactionRenameEvent;
import dev.kitteh.factions.event.FactionSetHomeEvent;
import dev.kitteh.factions.event.LandClaimEvent;
import dev.kitteh.factions.event.LandUnclaimAllEvent;
import dev.kitteh.factions.event.LandUnclaimEvent;
import dev.kitteh.factions.util.LazyLocation;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.pl3x.map.core.Pl3xMap;
import net.pl3x.map.core.event.EventListener;
import net.pl3x.map.core.event.server.Pl3xMapDisabledEvent;
import net.pl3x.map.core.event.server.Pl3xMapEnabledEvent;
import net.pl3x.map.core.event.world.WorldLoadedEvent;
import net.pl3x.map.core.event.world.WorldUnloadedEvent;
import net.pl3x.map.core.markers.Point;
import net.pl3x.map.core.markers.Vector;
import net.pl3x.map.core.markers.layer.WorldLayer;
import net.pl3x.map.core.markers.marker.Icon;
import net.pl3x.map.core.markers.marker.Marker;
import net.pl3x.map.core.markers.marker.Polygon;
import net.pl3x.map.core.markers.marker.Polyline;
import net.pl3x.map.core.markers.option.Fill;
import net.pl3x.map.core.markers.option.Options;
import net.pl3x.map.core.markers.option.Popup;
import net.pl3x.map.core.markers.option.Stroke;
import net.pl3x.map.core.markers.option.Tooltip;
import net.pl3x.map.core.util.Colors;
import net.pl3x.map.core.world.World;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

/**
 * Claim, home, and warp layers follow the same shape as Pl3xMap's own world layers.
 * {@code getMarkers()} is read off the server thread, so faction data is snapshotted
 * on the global region thread and the polygons are built before the markers are swapped in.
 */
final class FactionMarkers implements Listener, EventListener {
    static final String CLAIMS = "factions_claims";
    static final String HOMES = "factions_homes";
    static final String WARPS = "factions_warps";

    private final FactionsMapPlugin plugin;
    private final AtomicInteger generation = new AtomicInteger();
    private final Map<String, WorldLayer> claims = new HashMap<>();
    private final Map<String, WorldLayer> homes = new HashMap<>();
    private final Map<String, WorldLayer> warps = new HashMap<>();
    private volatile FactionsMapConfig config;
    private volatile boolean running;
    private long fingerprint;
    private @Nullable ScheduledTask debounce;
    private @Nullable ScheduledTask poll;

    FactionMarkers(FactionsMapPlugin plugin, FactionsMapConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    void applyConfig(FactionsMapConfig config) {
        this.config = config;
        this.fingerprint = 0L;
        if (!this.running) {
            return;
        }
        if (!config.enabled) {
            clearAll();
            stopTasks();
            return;
        }
        restyle();
        if (!config.homes) {
            this.homes.values().forEach(WorldLayer::clearMarkers);
        }
        if (!config.warps) {
            this.warps.values().forEach(WorldLayer::clearMarkers);
        }
        restartPoll();
        schedule();
    }

    void start() {
        Pl3xMap api = Pl3xMap.api();
        if (api == null || !api.isEnabled() || !this.config.enabled) {
            return;
        }
        this.running = true;
        MapIcons.register(api);
        for (World world : api.getWorldRegistry()) {
            ensureLayers(world);
        }
        restartPoll();
        schedule();
    }

    void shutdown() {
        this.generation.incrementAndGet();
        this.running = false;
        stopTasks();
        unregister(this.claims);
        unregister(this.homes);
        unregister(this.warps);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClaim(LandClaimEvent event) {
        schedule();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnclaim(LandUnclaimEvent event) {
        schedule();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnclaimAll(LandUnclaimAllEvent event) {
        schedule();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisband(FactionDisbandEvent event) {
        schedule();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRename(FactionRenameEvent event) {
        schedule();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHome(FactionSetHomeEvent event) {
        schedule();
    }

    @net.pl3x.map.core.event.EventHandler
    public void onMapEnabled(Pl3xMapEnabledEvent event) {
        start();
    }

    @net.pl3x.map.core.event.EventHandler
    public void onMapDisabled(Pl3xMapDisabledEvent event) {
        this.running = false;
        stopTasks();
        this.claims.clear();
        this.homes.clear();
        this.warps.clear();
    }

    @net.pl3x.map.core.event.EventHandler
    public void onWorldLoaded(WorldLoadedEvent event) {
        if (!this.running || !this.config.enabled) {
            return;
        }
        ensureLayers(event.getWorld());
        this.fingerprint = 0L;
        schedule();
    }

    @net.pl3x.map.core.event.EventHandler
    public void onWorldUnloaded(WorldUnloadedEvent event) {
        String name = event.getWorld().getName();
        this.claims.remove(name);
        this.homes.remove(name);
        this.warps.remove(name);
    }

    private void schedule() {
        if (!this.running || !this.config.enabled) {
            return;
        }
        if (this.debounce != null) {
            this.debounce.cancel();
        }
        // Land events fire before the board is updated, so wait one tick.
        this.debounce = this.plugin.getServer().getGlobalRegionScheduler().runDelayed(this.plugin, task -> rebuild(), 1L);
    }

    private void restartPoll() {
        if (this.poll != null) {
            this.poll.cancel();
            this.poll = null;
        }
        this.poll = this.plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                this.plugin,
                task -> rebuild(),
                30L * 20L,
                30L * 20L
        );
    }

    private void stopTasks() {
        if (this.debounce != null) {
            this.debounce.cancel();
            this.debounce = null;
        }
        if (this.poll != null) {
            this.poll.cancel();
            this.poll = null;
        }
    }

    private void rebuild() {
        if (!this.running || !this.config.enabled) {
            return;
        }
        int token = this.generation.get();
        List<FactionSnap> snapshot;
        try {
            snapshot = snapshot();
        } catch (RuntimeException exception) {
            this.plugin.getSLF4JLogger().warn("Failed to read factions", exception);
            return;
        }
        long nextFingerprint = fingerprint(snapshot);
        if (nextFingerprint == this.fingerprint) {
            return;
        }
        FactionsMapConfig config = this.config;
        this.plugin.getServer().getAsyncScheduler().runNow(this.plugin, task -> {
            List<DrawnFaction> drawn = draw(snapshot, config);
            this.plugin.getServer().getGlobalRegionScheduler().run(this.plugin, apply -> {
                if (!this.running || token != this.generation.get()) {
                    return;
                }
                apply(drawn);
                this.fingerprint = nextFingerprint;
            });
        });
    }

    private List<FactionSnap> snapshot() {
        FactionsMapConfig config = this.config;
        List<FactionSnap> snaps = new ArrayList<>();
        for (Faction faction : Factions.factions().all()) {
            if (faction == null || faction.isWilderness()) {
                continue;
            }
            Map<String, List<Long>> building = new HashMap<>();
            for (FLocation location : faction.claims()) {
                building.computeIfAbsent(location.worldName(), name -> new ArrayList<>())
                        .add(ClaimOutlines.pack(location.x(), location.z()));
            }
            Map<String, long[]> packed = new HashMap<>();
            for (Map.Entry<String, List<Long>> entry : building.entrySet()) {
                long[] chunks = new long[entry.getValue().size()];
                for (int i = 0; i < chunks.length; i++) {
                    chunks[i] = entry.getValue().get(i);
                }
                packed.put(entry.getKey(), chunks);
            }
            HomeSnap homeSnap = null;
            if (config.homes) {
                Location home = faction.home();
                if (home != null && home.getWorld() != null) {
                    homeSnap = new HomeSnap(home.getWorld().getName(), home.getX(), home.getZ());
                }
            }
            List<WarpSnap> warpSnaps = new ArrayList<>();
            if (config.warps && faction.warps() != null) {
                for (Map.Entry<String, LazyLocation> entry : faction.warps().entrySet()) {
                    LazyLocation location = entry.getValue();
                    if (location == null) {
                        continue;
                    }
                    warpSnaps.add(new WarpSnap(entry.getKey(), location.worldName(), location.x(), location.z()));
                }
            }
            int members = faction.members() == null ? 0 : faction.members().size();
            snaps.add(new FactionSnap(
                    faction.id(),
                    faction.tag() == null ? "" : faction.tag(),
                    faction.description() == null ? "" : faction.description(),
                    members,
                    colorFor(faction.id()),
                    packed,
                    homeSnap,
                    List.copyOf(warpSnaps)
            ));
        }
        return snaps;
    }

    private static List<DrawnFaction> draw(List<FactionSnap> snapshot, FactionsMapConfig config) {
        List<DrawnFaction> drawn = new ArrayList<>(snapshot.size());
        for (FactionSnap faction : snapshot) {
            String popup = "<b>" + MapText.escape(faction.tag) + "</b>";
            if (!faction.description.isBlank()) {
                popup = popup + "<br>" + MapText.escape(faction.description);
            }
            popup = popup + "<br>" + faction.members + (faction.members == 1 ? " member" : " members");
            Map<String, List<ClaimOutlines.Area>> areas = new HashMap<>();
            for (Map.Entry<String, long[]> entry : faction.claims.entrySet()) {
                areas.put(entry.getKey(), ClaimOutlines.trace(entry.getValue()));
            }
            drawn.add(new DrawnFaction(faction, popup, MapText.escape(faction.tag), areas, config));
        }
        return drawn;
    }

    private void apply(List<DrawnFaction> drawn) {
        Pl3xMap api = Pl3xMap.api();
        if (api == null || !api.isEnabled()) {
            return;
        }
        for (World world : api.getWorldRegistry()) {
            ensureLayers(world);
        }
        this.claims.values().forEach(WorldLayer::clearMarkers);
        this.homes.values().forEach(WorldLayer::clearMarkers);
        this.warps.values().forEach(WorldLayer::clearMarkers);
        for (DrawnFaction faction : drawn) {
            String base = "faction-" + faction.snap.id;
            for (Map.Entry<String, List<ClaimOutlines.Area>> entry : faction.areas.entrySet()) {
                WorldLayer layer = this.claims.get(entry.getKey());
                if (layer == null) {
                    continue;
                }
                int index = 0;
                for (ClaimOutlines.Area area : entry.getValue()) {
                    if (area.rings().isEmpty()) {
                        continue;
                    }
                    String key = base + "-" + safe(entry.getKey()) + "-" + index++;
                    Polygon polygon = Marker.polygon(key, ring(key + "-outer", area.rings().getFirst()));
                    for (int hole = 1; hole < area.rings().size(); hole++) {
                        polygon.addPolyline(ring(key + "-hole-" + hole, area.rings().get(hole)));
                    }
                    polygon.setOptions(Options.builder()
                            .stroke(new Stroke(faction.config.strokeWeight, Colors.setAlpha(alpha(faction.config.strokeOpacity), faction.snap.rgb)))
                            .fill(new Fill(Colors.setAlpha(alpha(faction.config.fillOpacity), faction.snap.rgb)))
                            .tooltip(new Tooltip(faction.tooltip))
                            .popup(new Popup(faction.popup))
                            .build());
                    layer.addMarker(polygon);
                }
            }
            if (faction.snap.home != null) {
                WorldLayer layer = this.homes.get(faction.snap.home.world);
                if (layer != null) {
                    putIcon(layer, "home-" + faction.snap.id, faction.snap.home.x, faction.snap.home.z, MapIcons.HOME, faction.tooltip, faction.popup);
                }
            }
            for (WarpSnap warp : faction.snap.warps) {
                WorldLayer layer = this.warps.get(warp.world);
                if (layer == null) {
                    continue;
                }
                String label = MapText.escape(warp.name);
                putIcon(layer, "warp-" + faction.snap.id + "-" + safe(warp.name), warp.x, warp.z, MapIcons.WARP, label, "<b>" + label + "</b><br>" + faction.tooltip);
            }
        }
    }

    private static void putIcon(WorldLayer layer, String key, double x, double z, String image, String tooltip, String popup) {
        Icon icon = Marker.icon(key, x, z, image, 16);
        icon.setAnchor(Vector.of(8, 16));
        icon.setOptions(Options.builder()
                .tooltipContent(tooltip)
                .tooltipDirection(Tooltip.Direction.TOP)
                .popupContent(popup)
                .build());
        layer.addMarker(icon);
    }

    private static Polyline ring(String key, ClaimOutlines.Ring ring) {
        List<Point> points = new ArrayList<>(ring.vertices().size());
        for (ClaimOutlines.Vertex vertex : ring.vertices()) {
            points.add(Point.of(vertex.x(), vertex.z()));
        }
        return Marker.polyline(key, points);
    }

    private static int colorFor(int id) {
        if (id == Factions.ID_SAFEZONE) {
            return 0x3DDCFF;
        }
        if (id == Factions.ID_WARZONE) {
            return 0xFF4D3A;
        }
        float hue = Math.floorMod(id * 137, 360) / 360.0F;
        return Color.HSBtoRGB(hue, 0.62F, 0.88F) & 0xFFFFFF;
    }

    private static int alpha(double opacity) {
        return (int) Math.round(Math.clamp(opacity, 0.0D, 1.0D) * 255.0D);
    }

    private static long fingerprint(List<FactionSnap> snapshot) {
        long hash = 0x9E3779B97F4A7C15L;
        for (FactionSnap faction : snapshot) {
            hash = (hash ^ faction.id) * 0x100000001B3L;
            hash = (hash ^ faction.tag.hashCode()) * 0x100000001B3L;
            hash = (hash ^ faction.description.hashCode()) * 0x100000001B3L;
            hash = (hash ^ faction.members) * 0x100000001B3L;
            hash = (hash ^ faction.rgb) * 0x100000001B3L;
            for (Map.Entry<String, long[]> entry : faction.claims.entrySet()) {
                hash = (hash ^ entry.getKey().hashCode()) * 0x100000001B3L;
                for (long chunk : entry.getValue()) {
                    hash ^= chunk * 0x9E3779B97F4A7C15L;
                }
            }
            if (faction.home != null) {
                hash = (hash ^ faction.home.world.hashCode()) * 0x100000001B3L;
                hash = (hash ^ Double.doubleToLongBits(faction.home.x)) * 0x100000001B3L;
                hash = (hash ^ Double.doubleToLongBits(faction.home.z)) * 0x100000001B3L;
            }
            for (WarpSnap warp : faction.warps) {
                hash = (hash ^ warp.name.hashCode()) * 0x100000001B3L;
                hash = (hash ^ warp.world.hashCode()) * 0x100000001B3L;
                hash = (hash ^ Double.doubleToLongBits(warp.x)) * 0x100000001B3L;
                hash = (hash ^ Double.doubleToLongBits(warp.z)) * 0x100000001B3L;
            }
        }
        return hash;
    }

    private void ensureLayers(World world) {
        this.claims.computeIfAbsent(world.getName(), name -> register(world, new ClaimsLayer(world, () -> this.config.claimsLabel)));
        this.homes.computeIfAbsent(world.getName(), name -> register(world, new HomesLayer(world, () -> this.config.homesLabel)));
        this.warps.computeIfAbsent(world.getName(), name -> register(world, new WarpsLayer(world, () -> this.config.warpsLabel, this.config.warpsHidden)));
    }

    private static WorldLayer register(World world, WorldLayer layer) {
        var existing = world.getLayerRegistry().get(layer.getKey());
        if (existing instanceof WorldLayer current) {
            return current;
        }
        world.getLayerRegistry().register(layer);
        return layer;
    }

    private void restyle() {
        this.claims.values().forEach(layer -> layer.setLabel(this.config.claimsLabel));
        this.homes.values().forEach(layer -> layer.setLabel(this.config.homesLabel));
        for (WorldLayer layer : this.warps.values()) {
            layer.setLabel(this.config.warpsLabel);
            layer.setDefaultHidden(this.config.warpsHidden);
        }
    }

    private void clearAll() {
        this.claims.values().forEach(WorldLayer::clearMarkers);
        this.homes.values().forEach(WorldLayer::clearMarkers);
        this.warps.values().forEach(WorldLayer::clearMarkers);
    }

    private void unregister(Map<String, WorldLayer> layers) {
        Pl3xMap api = Pl3xMap.api();
        if (api != null) {
            for (Map.Entry<String, WorldLayer> entry : layers.entrySet()) {
                World world = api.getWorldRegistry().get(entry.getKey());
                if (world != null) {
                    world.getLayerRegistry().unregister(entry.getValue().getKey());
                }
            }
        }
        layers.clear();
    }

    private static String safe(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private record FactionSnap(
            int id,
            String tag,
            String description,
            int members,
            int rgb,
            Map<String, long[]> claims,
            @Nullable HomeSnap home,
            List<WarpSnap> warps
    ) {
    }

    private record HomeSnap(String world, double x, double z) {
    }

    private record WarpSnap(String name, String world, double x, double z) {
    }

    private record DrawnFaction(
            FactionSnap snap,
            String popup,
            String tooltip,
            Map<String, List<ClaimOutlines.Area>> areas,
            FactionsMapConfig config
    ) {
    }

    private static final class ClaimsLayer extends WorldLayer {
        ClaimsLayer(World world, Supplier<String> label) {
            super(CLAIMS, world, label);
            setUpdateInterval(5);
            setLiveUpdate(true);
            setShowControls(true);
            setDefaultHidden(false);
            setPriority(40);
            setZIndex(200);
        }
    }

    private static final class HomesLayer extends WorldLayer {
        HomesLayer(World world, Supplier<String> label) {
            super(HOMES, world, label);
            setUpdateInterval(5);
            setLiveUpdate(true);
            setShowControls(true);
            setDefaultHidden(false);
            setPriority(12);
            setZIndex(600);
        }
    }

    private static final class WarpsLayer extends WorldLayer {
        WarpsLayer(World world, Supplier<String> label, boolean hidden) {
            super(WARPS, world, label);
            setUpdateInterval(5);
            setLiveUpdate(true);
            setShowControls(true);
            setDefaultHidden(hidden);
            setPriority(13);
            setZIndex(590);
        }
    }
}
