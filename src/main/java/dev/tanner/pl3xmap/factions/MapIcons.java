package dev.tanner.pl3xmap.factions;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import net.pl3x.map.core.Pl3xMap;
import net.pl3x.map.core.image.IconImage;

final class MapIcons {
    static final String HOME = "pl3xmap-factions-home";
    static final String WARP = "pl3xmap-factions-warp";

    private MapIcons() {
    }

    static void register(Pl3xMap api) {
        register(api, HOME, flag(new Color(48, 176, 80)));
        register(api, WARP, flag(new Color(64, 140, 255)));
    }

    private static void register(Pl3xMap api, String key, BufferedImage image) {
        if (api.getIconRegistry().has(key)) {
            return;
        }
        api.getIconRegistry().register(key, new IconImage(key, image, "png"));
    }

    private static BufferedImage flag(Color color) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(35, 35, 35));
        graphics.setStroke(new BasicStroke(2f));
        graphics.drawLine(8, 5, 8, 28);
        graphics.setColor(color);
        graphics.fillPolygon(new int[]{8, 26, 8}, new int[]{5, 12, 19}, 3);
        graphics.dispose();
        return image;
    }
}
