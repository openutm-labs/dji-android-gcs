import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Builds MapLibre glyph files ({start}-{end}.pbf, signed distance fields) from a TrueType font, so the
 * offline map can draw text labels without fetching glyphs from a server (docs/12, Zones and maps).
 *
 *   java -Djava.awt.headless=true tools/glyphs/BuildGlyphs.java Roboto.ttf "Roboto Regular" out/ 0 255 256 511 ...
 *
 * Same conventions as Mapbox's fontnik, which MapLibre's shaders expect: glyphs rasterised at 24 px,
 * a 3 px buffer round each bitmap, distance radius 8 px, edge at 0.75 (cutoff 0.25); `top` relative to
 * the font's ascender. The distance transform is Felzenszwalb and Huttenlocher's, as in Mapbox's tiny-sdf.
 * Plain JDK, no libraries.
 */
public class BuildGlyphs {
    static final int SIZE = 24, BUFFER = 3, RADIUS = 8;
    static final double CUTOFF = 0.25, INF = 1e20;

    public static void main(String[] a) throws Exception {
        if (a.length < 5 || (a.length - 3) % 2 != 0) {
            System.err.println("usage: BuildGlyphs <font.ttf> <fontstack name> <out dir> <start> <end> [<start> <end> ...]");
            System.exit(2);
        }
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File(a[0])).deriveFont((float) SIZE);
        String name = a[1];
        File out = new File(a[2], name);
        out.mkdirs();
        FontRenderContext frc = new FontRenderContext(null, true, true);
        int ascender = (int) Math.ceil(font.getLineMetrics("Hg", frc).getAscent());
        for (int i = 3; i < a.length; i += 2) {
            int start = Integer.parseInt(a[i]), end = Integer.parseInt(a[i + 1]);
            ByteArrayOutputStream glyphs = new ByteArrayOutputStream();
            int count = 0;
            for (int cp = start; cp <= end; cp++) {
                if (!font.canDisplay(cp) || Character.isISOControl(cp)) continue;
                byte[] g = glyph(font, frc, cp, ascender);
                field(glyphs, 3, g);
                count++;
            }
            ByteArrayOutputStream stack = new ByteArrayOutputStream();
            field(stack, 1, name.getBytes("UTF-8"));
            field(stack, 2, (start + "-" + end).getBytes("UTF-8"));
            stack.write(glyphs.toByteArray());
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            field(file, 1, stack.toByteArray());
            File f = new File(out, start + "-" + end + ".pbf");
            Files.write(f.toPath(), file.toByteArray());
            System.out.println(f + ": " + count + " glyphs, " + f.length() + " bytes");
        }
    }

    /** One `glyph` message: id, SDF bitmap, width, height, left, top, advance. */
    static byte[] glyph(Font font, FontRenderContext frc, int cp, int ascender) throws Exception {
        String s = new String(Character.toChars(cp));
        GlyphVector gv = font.createGlyphVector(frc, s);
        int advance = (int) Math.round(gv.getGlyphMetrics(0).getAdvance());
        // Rasterise with room to spare, pen at (pad, pad + ascender), then crop to the inked pixels.
        int pad = SIZE * 2, canvas = SIZE * 5;
        BufferedImage img = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(font);
        g.setColor(java.awt.Color.BLACK);
        int ox = pad, oy = pad + ascender;
        g.drawGlyphVector(gv, ox, oy);
        g.dispose();
        int minX = canvas, minY = canvas, maxX = -1, maxY = -1;
        for (int y = 0; y < canvas; y++) for (int x = 0; x < canvas; x++) {
            if ((img.getRGB(x, y) >>> 24) != 0) {
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        varintField(m, 1, cp);
        if (maxX < 0) { // a space: metrics only
            varintField(m, 3, 0); varintField(m, 4, 0); sintField(m, 5, 0); sintField(m, 6, -ascender); varintField(m, 7, advance);
            return m.toByteArray();
        }
        int w = maxX - minX + 1, h = maxY - minY + 1;
        int bw = w + 2 * BUFFER, bh = h + 2 * BUFFER;
        double[] outer = new double[bw * bh], inner = new double[bw * bh];
        Arrays.fill(outer, INF);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double alpha = (img.getRGB(minX + x, minY + y) >>> 24) / 255.0;
            if (alpha == 0) continue;
            int j = (y + BUFFER) * bw + x + BUFFER;
            if (alpha == 1) { outer[j] = 0; inner[j] = INF; }
            else { double d = 0.5 - alpha; outer[j] = d > 0 ? d * d : 0; inner[j] = d < 0 ? d * d : 0; }
        }
        edt(outer, 0, 0, bw, bh, bw);
        edt(inner, BUFFER, BUFFER, w, h, bw);
        byte[] bitmap = new byte[bw * bh];
        for (int i = 0; i < bitmap.length; i++) {
            double d = Math.sqrt(outer[i]) - Math.sqrt(inner[i]);
            bitmap[i] = (byte) Math.max(0, Math.min(255, Math.round(255 - 255 * (d / RADIUS + CUTOFF))));
        }
        field(m, 2, bitmap);
        varintField(m, 3, w);
        varintField(m, 4, h);
        sintField(m, 5, minX - ox);
        sintField(m, 6, (oy - minY) - ascender);
        varintField(m, 7, advance);
        return m.toByteArray();
    }

    static void edt(double[] data, int x0, int y0, int width, int height, int gridSize) {
        int n = Math.max(width, height);
        double[] f = new double[n], z = new double[n + 1];
        int[] v = new int[n];
        for (int x = x0; x < x0 + width; x++) edt1d(data, y0 * gridSize + x, gridSize, height, f, v, z);
        for (int y = y0; y < y0 + height; y++) edt1d(data, y * gridSize + x0, 1, width, f, v, z);
    }

    static void edt1d(double[] grid, int offset, int stride, int length, double[] f, int[] v, double[] z) {
        v[0] = 0; z[0] = -INF; z[1] = INF; f[0] = grid[offset];
        for (int q = 1, k = 0; q < length; q++) {
            f[q] = grid[offset + q * stride];
            double q2 = (double) q * q, s;
            do {
                int r = v[k];
                s = (f[q] - f[r] + q2 - (double) r * r) / (q - r) / 2;
            } while (s <= z[k] && --k > -1);
            k++; v[k] = q; z[k] = s; z[k + 1] = INF;
        }
        for (int q = 0, k = 0; q < length; q++) {
            while (z[k + 1] < q) k++;
            int r = v[k];
            double qr = q - r;
            grid[offset + q * stride] = f[r] + qr * qr;
        }
    }

    // --- protobuf, by hand: length-delimited fields, varints, zigzag sint32
    static void field(ByteArrayOutputStream o, int tag, byte[] b) { varint(o, (tag << 3) | 2); varint(o, b.length); o.write(b, 0, b.length); }
    static void varintField(ByteArrayOutputStream o, int tag, long v) { varint(o, tag << 3); varint(o, v); }
    static void sintField(ByteArrayOutputStream o, int tag, int v) { varintField(o, tag, ((v << 1) ^ (v >> 31)) & 0xFFFFFFFFL); }
    static void varint(ByteArrayOutputStream o, long v) { while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; } o.write((int) v); }
}
