package ai.xelta.craft.client.screen.tabs;

import ai.xelta.craft.controller.AssetController;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * GalleryTab — UI Layer
 *
 * Paginated grid display of all locally cached generated assets.
 *
 * Features:
 *  • Scans xeltacraft/cache/images + videos directories
 *  • Grid layout with thumbnails (filename as fallback label)
 *  • Click to select (highlights entry)
 *  • "Open Folder" hint
 *  • Filter: All / Images / Videos
 */
public class GalleryTab {

    // ── Constants (shared colours) ────────────────────────────────────────────
    private static final int C_PANEL  = DashboardTab.C_PANEL;
    private static final int C_PANEL2 = DashboardTab.C_PANEL2;
    private static final int C_BDIM   = DashboardTab.C_BDIM;
    private static final int C_PRI    = DashboardTab.C_PRI;
    private static final int C_SEC    = DashboardTab.C_SEC;
    private static final int C_CYAN   = DashboardTab.C_CYAN;
    private static final int C_GREEN  = DashboardTab.C_GREEN;
    private static final int C_RED    = DashboardTab.C_RED;
    private static final int C_PURPLE = DashboardTab.C_PURPLE;
    private static final int C_GOLD   = DashboardTab.C_GOLD;
    private static final int C_TAG_BG = DashboardTab.C_TAG_BG;

    // ── Grid Config ───────────────────────────────────────────────────────────
    private static final int CARD_W   = 80;
    private static final int CARD_H   = 72;
    private static final int CARD_GAP = 6;

    // ── Filter ────────────────────────────────────────────────────────────────
    private enum Filter { ALL, IMAGES, VIDEOS }
    private Filter filter = Filter.ALL;

    // ── State ─────────────────────────────────────────────────────────────────
    private List<AssetController.GalleryEntry> entries = null;
    private int selectedIndex = -1;
    private int scrollOffset  = 0;

    // ── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Refreshes the gallery entries from disk.
     * Call when tab is first shown or after a new generation completes.
     */
    public void refresh() {
        entries = AssetController.getInstance().listCachedAssets();
        selectedIndex = -1;
        scrollOffset = 0;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public void render(GuiGraphics gfx, Font font, int x, int y, int w, int h, int mouseX, int mouseY) {
        if (entries == null) refresh();

        // ── Filter bar ────────────────────────────────────────────────────────
        renderFilterBar(gfx, font, x, y, mouseX, mouseY);
        int contentY = y + 20;

        List<AssetController.GalleryEntry> filtered = getFiltered();

        if (filtered.isEmpty()) {
            // Empty state
            String msg = entries.isEmpty()
                ? "No generated assets yet. Create something first!"
                : "No " + filter.name().toLowerCase() + " found.";
            int tw = font.width(msg);
            gfx.drawString(font, msg, x + (w - tw) / 2, contentY + h / 2, C_SEC, false);
            return;
        }

        // ── Grid ──────────────────────────────────────────────────────────────
        int cols = Math.max(1, (w - 8) / (CARD_W + CARD_GAP));
        int rowH = CARD_H + CARD_GAP;

        for (int i = scrollOffset; i < filtered.size(); i++) {
            int col = (i - scrollOffset) % cols;
            int row = (i - scrollOffset) / cols;
            int cx  = x + 4 + col * (CARD_W + CARD_GAP);
            int cy  = contentY + row * rowH;

            if (cy + CARD_H > y + h) break; // out of visible area

            renderAssetCard(gfx, font, cx, cy, filtered.get(i), i, mouseX, mouseY);
        }

        // ── Selected detail strip ─────────────────────────────────────────────
        if (selectedIndex >= 0 && selectedIndex < filtered.size()) {
            AssetController.GalleryEntry sel = filtered.get(selectedIndex);
            int detailY = y + h - 18;
            gfx.fill(x, detailY - 2, x + w, y + h, C_PANEL);
            gfx.fill(x, detailY - 2, x + w, detailY - 1, C_BDIM);
            String detail = sel.filename() + "  |  " + (sel.isVideo() ? "Video" : "Image");
            gfx.drawString(font, detail, x + 4, detailY + 2, C_CYAN, false);
        }
    }

    private void renderFilterBar(GuiGraphics gfx, Font font, int x, int y, int mouseX, int mouseY) {
        Filter[] filters = Filter.values();
        int bw = 50;
        for (int i = 0; i < filters.length; i++) {
            int bx = x + 4 + i * (bw + 4);
            boolean active  = filter == filters[i];
            boolean hovered = inside(mouseX, mouseY, bx, y, bw, 14);
            int bg     = active ? C_CYAN : (hovered ? 0xFF202A3A : C_PANEL2);
            int textC  = active ? 0xFF0A0E18 : C_SEC;
            gfx.fill(bx, y, bx + bw, y + 14, bg);
            int tw = font.width(filters[i].name());
            gfx.drawString(font, filters[i].name(), bx + (bw - tw) / 2, y + 3, textC, false);
        }
    }

    private void renderAssetCard(GuiGraphics gfx, Font font,
                                 int cx, int cy,
                                 AssetController.GalleryEntry entry,
                                 int index, int mouseX, int mouseY) {

        boolean selected = (index == selectedIndex);
        boolean hovered  = inside(mouseX, mouseY, cx, cy, CARD_W, CARD_H);
        int border = selected ? C_CYAN : (hovered ? C_PURPLE : C_BDIM);

        gfx.fill(cx - 1, cy - 1, cx + CARD_W + 1, cy + CARD_H + 1, border);
        gfx.fill(cx, cy, cx + CARD_W, cy + CARD_H, C_PANEL2);

        // Type badge
        int badgeColor = entry.isVideo() ? C_PURPLE : C_CYAN;
        String badgeText = entry.isVideo() ? "VID" : "IMG";
        gfx.fill(cx, cy, cx + CARD_W, cy + 3, badgeColor);
        gfx.drawString(font, badgeText, cx + 2, cy + 4, badgeColor, false);

        // Filename truncated
        String name = entry.filename();
        if (font.width(name) > CARD_W - 4) {
            while (name.length() > 3 && font.width(name + "...") > CARD_W - 4) {
                name = name.substring(0, name.length() - 1);
            }
            name += "...";
        }
        gfx.drawString(font, name, cx + 2, cy + CARD_H - 16, C_PRI, false);

        // Status dot
        gfx.fill(cx + CARD_W - 8, cy + 5, cx + CARD_W - 4, cy + 9, C_GREEN);
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    public void mouseClicked(int mouseX, int mouseY, int x, int y, int w, int h) {
        // Filter bar click
        Filter[] filters = Filter.values();
        int bw = 50;
        for (int i = 0; i < filters.length; i++) {
            int bx = x + 4 + i * (bw + 4);
            if (inside(mouseX, mouseY, bx, y, bw, 14)) {
                filter = filters[i];
                return;
            }
        }

        // Grid click
        List<AssetController.GalleryEntry> filtered = getFiltered();
        int contentY = y + 20;
        int cols = Math.max(1, (w - 8) / (CARD_W + CARD_GAP));

        for (int i = scrollOffset; i < filtered.size(); i++) {
            int col = (i - scrollOffset) % cols;
            int row = (i - scrollOffset) / cols;
            int cx  = x + 4 + col * (CARD_W + CARD_GAP);
            int cy  = contentY + row * (CARD_H + CARD_GAP);

            if (inside(mouseX, mouseY, cx, cy, CARD_W, CARD_H)) {
                selectedIndex = (selectedIndex == i) ? -1 : i;
                return;
            }
        }
    }

    public void mouseScrolled(double delta, int x, int y, int w) {
        if (entries == null) return;
        List<AssetController.GalleryEntry> filtered = getFiltered();
        int cols = Math.max(1, (w - 8) / (CARD_W + CARD_GAP));
        int rows = (int) Math.ceil(filtered.size() / (double) cols);
        int maxOffset = Math.max(0, rows - 3) * cols;
        scrollOffset = (int) Math.max(0, Math.min(maxOffset, scrollOffset - delta * cols));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<AssetController.GalleryEntry> getFiltered() {
        if (entries == null) return List.of();
        return switch (filter) {
            case IMAGES -> entries.stream().filter(AssetController.GalleryEntry::isImage).toList();
            case VIDEOS -> entries.stream().filter(AssetController.GalleryEntry::isVideo).toList();
            default     -> entries;
        };
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    public AssetController.GalleryEntry getSelected() {
        List<AssetController.GalleryEntry> filtered = getFiltered();
        return (selectedIndex >= 0 && selectedIndex < filtered.size()) ? filtered.get(selectedIndex) : null;
    }
}
