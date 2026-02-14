package velox.api.layer1.simplified.demo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Layer 2: AI Action Marker Icons
 * Custom icons for AI trading decisions (entries, exits, skips)
 */
public class AIMarkerIcons {
    private static final int ICON_SIZE = 16;

    /**
     * AI LONG Entry: CYAN circle (distinct from iceberg GREEN)
     */
    public static BufferedImage createLongEntryIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(Color.CYAN);
        g.fillOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.setColor(Color.BLACK);
        g.drawOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.dispose();
        return icon;
    }

    /**
     * AI SHORT Entry: PINK circle (distinct from iceberg RED)
     */
    public static BufferedImage createShortEntryIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(Color.PINK);
        g.fillOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.setColor(Color.BLACK);
        g.drawOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.dispose();
        return icon;
    }

    /**
     * Stop Loss: ORANGE X
     */
    public static BufferedImage createStopLossIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(Color.ORANGE);
        g.drawLine(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.drawLine(ICON_SIZE - 1, 0, 0, ICON_SIZE - 1);
        g.dispose();
        return icon;
    }

    /**
     * Take Profit: BLUE Diamond
     */
    public static BufferedImage createTakeProfitIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();

        // Draw diamond shape
        int[] xPoints = {ICON_SIZE / 2, 0, ICON_SIZE / 2, ICON_SIZE - 1};
        int[] yPoints = {0, ICON_SIZE / 2, ICON_SIZE - 1, ICON_SIZE / 2};
        g.setColor(Color.BLUE);
        g.fillPolygon(xPoints, yPoints, 4);

        g.dispose();
        return icon;
    }

    /**
     * Break-Even: YELLOW Square
     */
    public static BufferedImage createBreakEvenIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(Color.YELLOW);
        g.fillRect(2, 2, ICON_SIZE - 4, ICON_SIZE - 4);
        g.setColor(Color.BLACK);
        g.drawRect(2, 2, ICON_SIZE - 4, ICON_SIZE - 4);
        g.dispose();
        return icon;
    }

    /**
     * Skip: White Circle Outline
     */
    public static BufferedImage createSkipIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(Color.WHITE);
        g.fillOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.setColor(Color.GRAY);
        g.drawOval(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.dispose();
        return icon;
    }

    /**
     * Slippage Rejected: MAGENTA X (distinct from ORANGE X for stop loss)
     */
    public static BufferedImage createSlippageRejectedIcon() {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setColor(Color.MAGENTA);
        g.drawLine(0, 0, ICON_SIZE - 1, ICON_SIZE - 1);
        g.drawLine(ICON_SIZE - 1, 0, 0, ICON_SIZE - 1);
        g.dispose();
        return icon;
    }
}
