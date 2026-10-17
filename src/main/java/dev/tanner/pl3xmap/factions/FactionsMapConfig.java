package dev.tanner.pl3xmap.factions;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class FactionsMapConfig {
    final boolean enabled;
    final String claimsLabel;
    final int strokeWeight;
    final double strokeOpacity;
    final double fillOpacity;
    final boolean homes;
    final String homesLabel;
    final boolean warps;
    final String warpsLabel;
    final boolean warpsHidden;

    private FactionsMapConfig(
            boolean enabled,
            String claimsLabel,
            int strokeWeight,
            double strokeOpacity,
            double fillOpacity,
            boolean homes,
            String homesLabel,
            boolean warps,
            String warpsLabel,
            boolean warpsHidden
    ) {
        this.enabled = enabled;
        this.claimsLabel = claimsLabel;
        this.strokeWeight = strokeWeight;
        this.strokeOpacity = strokeOpacity;
        this.fillOpacity = fillOpacity;
        this.homes = homes;
        this.homesLabel = homesLabel;
        this.warps = warps;
        this.warpsLabel = warpsLabel;
        this.warpsHidden = warpsHidden;
    }

    static FactionsMapConfig load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        return new FactionsMapConfig(
                config.getBoolean("enabled", true),
                config.getString("claims.label", "Claims"),
                Math.max(1, config.getInt("claims.stroke-weight", 2)),
                config.getDouble("claims.stroke-opacity", 0.9D),
                config.getDouble("claims.fill-opacity", 0.28D),
                config.getBoolean("homes.enabled", true),
                config.getString("homes.label", "Faction Homes"),
                config.getBoolean("warps.enabled", true),
                config.getString("warps.label", "Faction Warps"),
                config.getBoolean("warps.default-hidden", true)
        );
    }
}
