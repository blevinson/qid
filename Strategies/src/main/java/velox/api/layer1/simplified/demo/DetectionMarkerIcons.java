package velox.api.layer1.simplified.demo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;

/**
 * Layer 1: Detection Marker Icons
 * Custom icons for market detection signals (iceberg, spoofing, absorption)
 */
public class DetectionMarkerIcons {
    private static final int ICON_SIZE = 16;

    /**
     * Iceberg BUY: GREEN circle
     */
    public static BufferedImage createIcebergBuyIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(Color.GREEN);
        g.fillOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.setColor(Color.BLACK);
        g.drawOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.dispose();
        return icon;
    }

    /**
     * Iceberg SELL: RED circle
     */
    public static BufferedImage createIcebergSellIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(Color.RED);
        g.fillOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.setColor(Color.BLACK);
        g.drawOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.dispose();
        return icon;
    }

    /**
     * Spoofing: MAGENTA triangle (points up)
     */
    public static BufferedImage createSpoofingIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();

        // Draw triangle pointing up
        int[] xPoints = {ICON_SIZE / 2, 0, ICON_SIZE - 1};
        int[] yPoints = {0, ICON_SIZE - 1, ICON_SIZE - 1};
        g.setColor(Color.MAGENTA);
        g.fillPolygon(xPoints, yPoints, 3);

        g.dispose();
        return icon;
    }

    /**
     * Absorption: YELLOW square
     */
    public static BufferedImage createAbsorptionIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(Color.YELLOW);
        g.fillRect(2, 2, ICON_SIZE - 4, ICON_SIZE - 4);
        g.setColor(Color.BLACK);
        g.drawRect(2, 2, ICON_SIZE - 4, ICON_SIZE - 4);
        g.dispose();
        return icon;
    }
}
