package dev.workout.telegram.message;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.*;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Headless, local PNG rendering with no external chart service or user data upload. */
public final class ChartRenderer {
  private static final Color INK = new Color(0xEAF0FA),
      MUTED = new Color(0xA9B9D0),
      GRID = new Color(0x29374D);

  private ChartRenderer() {}

  public static byte[] render(Chart chart) {
    int height = chart.series().size() > 1 ? 1000 : 720;
    var image = new BufferedImage(1200, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setColor(new Color(0x101A2C));
      g.fillRect(0, 0, 1200, height);
      text(g, "WORKOUT / PROGRESS", 54, 50, 19, MUTED, false);
      text(g, fit(g, chart.title(), 1090, 38), 54, 105, 38, INK, true);
      text(g, chart.subtitle(), 54, 146, 23, MUTED, false);
      int panelHeight = (height - 235) / chart.series().size();
      for (int i = 0; i < chart.series().size(); i++)
        panel(
            g,
            chart.series().get(i),
            178 + i * panelHeight,
            panelHeight - 18,
            new Color(i == 0 ? 0x62D7C0 : 0x91ADFF));
      text(g, chart.footer(), 54, height - 28, 21, MUTED, false);
    } finally {
      g.dispose();
    }
    try (var out = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("Cannot render chart", ex);
    }
  }

  private static void panel(Graphics2D g, Chart.Series series, int top, int height, Color accent) {
    g.setColor(new Color(0x18253A));
    g.fillRoundRect(36, top, 1128, height, 26, 26);
    text(g, series.title() + " · " + series.unit(), 62, top + 44, 25, INK, true);
    int left = 132, right = 1120, bottom = top + height - 55, upper = top + 85;
    var points = series.points();
    double max =
        points.stream()
            .filter(p -> p.value() != null)
            .mapToDouble(p -> p.value().doubleValue())
            .max()
            .orElse(0);
    boolean hasData =
        points.stream()
            .anyMatch(p -> p.value() != null && (!series.bars() || p.value().signum() > 0));
    if (!hasData) {
      text(g, series.emptyText(), 80, top + height / 2 + 10, 27, MUTED, false);
      return;
    }
    double ceiling =
        series.bars() && max <= 5 ? Math.max(1, Math.ceil(max)) : Math.max(1, max * 1.15);
    int ticks = 4;
    if (series.integerAxis()) {
      ticks = Math.max(1, Math.min(4, (int) Math.ceil(max)));
      ceiling = Math.max(1, Math.ceil(max / ticks)) * ticks;
    }
    for (int tick = 0; tick <= ticks; tick++) {
      int y = bottom - (bottom - upper) * tick / ticks;
      g.setColor(GRID);
      g.setStroke(new BasicStroke(1));
      g.drawLine(left, y, right, y);
      String label = compact(ceiling * tick / ticks);
      text(g, label, left - 15 - width(g, label, 19), y + 7, 19, MUTED, false);
    }
    double step = (right - left) / (double) Math.max(1, points.size());
    var path = new Path2D.Double();
    boolean connected = false;
    for (int i = 0; i < points.size(); i++) {
      var p = points.get(i);
      int x = (int) (left + step * (i + .5));
      if (p.value() == null) {
        connected = false;
        continue;
      }
      int y = bottom - (int) ((bottom - upper) * p.value().doubleValue() / ceiling);
      g.setColor(accent);
      if (series.bars()) {
        int barWidth = Math.max(2, (int) Math.min(54, step * .7));
        g.fillRoundRect(x - barWidth / 2, y, barWidth, bottom - y, 5, 5);
      } else {
        if (connected) path.lineTo(x, y);
        else path.moveTo(x, y);
        connected = true;
        g.fillOval(x - 7, y - 7, 14, 14);
        if (points.size() <= 12) {
          String value = Format.n(p.value());
          text(g, value, x - width(g, value, 21) / 2, y - 15, 21, INK, false);
        }
      }
    }
    if (!series.bars()) {
      g.setColor(accent);
      g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g.draw(path);
    }
    int stride = Math.max(1, (int) Math.ceil(points.size() / 6.0));
    for (int i = 0; i < points.size(); i += stride) {
      String label = points.get(i).label();
      int x = (int) (left + step * (i + .5));
      text(g, label, x - width(g, label, 19) / 2, bottom + 32, 19, MUTED, false);
    }
  }

  private static String compact(double value) {
    if (value >= 1000000) return String.format(Locale.ROOT, "%.1fM", value / 1000000);
    if (value >= 1000) return String.format(Locale.ROOT, "%.1fk", value / 1000);
    return BigDecimal.valueOf(value)
        .setScale(1, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString();
  }

  private static int width(Graphics2D g, String text, int size) {
    g.setFont(new Font("SansSerif", Font.PLAIN, size));
    return g.getFontMetrics().stringWidth(text);
  }

  private static String fit(Graphics2D g, String text, int max, int size) {
    String value = text.replace('\n', ' ');
    while (value.length() > 1 && width(g, value, size) > max)
      value = value.substring(0, value.length() - 1);
    return value.equals(text) ? value : value.stripTrailing() + "…";
  }

  private static void text(
      Graphics2D g, String text, int x, int y, int size, Color color, boolean bold) {
    g.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size));
    g.setColor(color);
    g.drawString(text, x, y);
  }
}
