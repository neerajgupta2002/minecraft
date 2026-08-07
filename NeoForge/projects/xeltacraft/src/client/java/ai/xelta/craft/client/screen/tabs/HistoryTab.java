package ai.xelta.craft.client.screen.tabs;

import ai.xelta.craft.controller.HistoryController;
import ai.xelta.craft.sdk.XeltaHistoryClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * HistoryTab — UI Layer
 *
 * Displays server-side generation history fetched from the Xelta History API.
 *
 * Features:
 *  • Paginated list of history items with type badge, prompt, date, status
 *  • "Load More" button for pagination
 *  • "Re-download" button for individual items
 *  • Status colouring: completed=green, failed=red, processing=cyan
 */
public class HistoryTab {

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int C_PANEL  = DashboardTab.C_PANEL;
    private static final int C_PANEL2 = DashboardTab.C_PANEL2;
    private static final int C_BDIM   = DashboardTab.C_BDIM;
    private static final int C_PRI    = DashboardTab.C_PRI;
    private static final int C_SEC    = DashboardTab.C_SEC;
    private static final int C_CYAN   = DashboardTab.C_CYAN;
    private static final int C_GREEN  = DashboardTab.C_GREEN;
    private static final int C_PURPLE = DashboardTab.C_PURPLE;
    private static final int C_RED    = DashboardTab.C_RED;
    private static final int C_GOLD   = DashboardTab.C_GOLD;

    private static final int ROW_H    = 28;

    // ── State ─────────────────────────────────────────────────────────────────
    private String statusMessage    = "";
    private boolean isLoading       = false;
    private int hoveredRow          = -1;
    private int hoveredLoadMore     = 0;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Call when the tab becomes visible to trigger initial load. */
    public void onShow() {
        if (HistoryController.getInstance().getCachedItems().isEmpty() && !isLoading) {
            loadHistory();
        }
    }

    private void loadHistory() {
        isLoading = true;
        statusMessage = "Loading history...";
        HistoryController.getInstance().loadFirstPage().thenAccept(result -> {
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                isLoading = false;
                statusMessage = result.success() ? "" : result.errorMessage();
            });
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public void render(GuiGraphics gfx, Font font, int x, int y, int w, int h, int mouseX, int mouseY) {
        // Header row
        gfx.fill(x, y, x + w, y + 16, C_PANEL2);
        gfx.drawString(font, "TYPE",    x + 4,       y + 4, C_SEC, false);
        gfx.drawString(font, "PROMPT",  x + 42,      y + 4, C_SEC, false);
        gfx.drawString(font, "STATUS",  x + w - 90,  y + 4, C_SEC, false);
        gfx.drawString(font, "DATE",    x + w - 50,  y + 4, C_SEC, false);
        gfx.fill(x, y + 16, x + w, y + 17, C_BDIM);

        int contentY = y + 18;
        List<XeltaHistoryClient.HistoryItem> items = HistoryController.getInstance().getCachedItems();

        // Loading / empty states
        if (isLoading) {
            gfx.drawString(font, "Loading history...", x + 8, contentY + 20, C_CYAN, false);
            return;
        }
        if (!statusMessage.isBlank()) {
            gfx.drawString(font, statusMessage, x + 8, contentY + 20, C_RED, false);
            renderRefreshButton(gfx, font, x, y + h - 20, mouseX, mouseY);
            return;
        }
        if (items.isEmpty()) {
            gfx.drawString(font, "No generation history found.", x + 8, contentY + 20, C_SEC, false);
            renderRefreshButton(gfx, font, x, y + h - 20, mouseX, mouseY);
            return;
        }

        // Rows
        hoveredRow = -1;
        int maxRows = (h - 40) / ROW_H;
        for (int i = 0; i < Math.min(items.size(), maxRows); i++) {
            int ry = contentY + i * ROW_H;
            boolean hovered = inside(mouseX, mouseY, x, ry, w, ROW_H - 2);
            if (hovered) hoveredRow = i;
            renderRow(gfx, font, x, ry, w, items.get(i), hovered);
        }

        // Load more button
        if (HistoryController.getInstance().hasMore()) {
            renderLoadMoreButton(gfx, font, x, y + h - 20, w, mouseX, mouseY);
        }

        // Refresh button (always)
        renderRefreshButton(gfx, font, x + w - 76, y + h - 20, mouseX, mouseY);
    }

    private void renderRow(GuiGraphics gfx, Font font, int x, int y, int w,
                           XeltaHistoryClient.HistoryItem item, boolean hovered) {
        int bg = hovered ? 0xFF1A1E30 : C_PANEL;
        gfx.fill(x, y, x + w, y + ROW_H - 2, bg);
        gfx.fill(x, y + ROW_H - 2, x + w, y + ROW_H - 1, C_BDIM);

        // Type badge
        String type = item.type().isBlank() ? "?" : item.type().toUpperCase();
        if (type.length() > 3) type = type.substring(0, 3);
        int badgeColor = typeBadgeColor(item.type());
        gfx.fill(x + 2, y + 6, x + 36, y + 20, badgeColor & 0x33FFFFFF | 0x55000000);
        gfx.drawString(font, type, x + 4, y + 9, badgeColor, false);

        // Prompt (truncated)
        String prompt = item.prompt();
        if (font.width(prompt) > w - 160) {
            while (!prompt.isEmpty() && font.width(prompt + "...") > w - 160) {
                prompt = prompt.substring(0, prompt.length() - 1);
            }
            prompt += "...";
        }
        gfx.drawString(font, prompt, x + 42, y + 9, C_PRI, false);

        // Status
        int statusColor = item.isCompleted() ? C_GREEN : (item.isFailed() ? C_RED : C_CYAN);
        String status = item.status().isBlank() ? "?" : item.status();
        gfx.drawString(font, status, x + w - 90, y + 9, statusColor, false);

        // Date (first 10 chars of ISO timestamp)
        String date = item.createdAt().length() >= 10 ? item.createdAt().substring(0, 10) : item.createdAt();
        gfx.drawString(font, date, x + w - 50, y + 9, C_SEC, false);
    }

    private void renderLoadMoreButton(GuiGraphics gfx, Font font, int x, int y, int w,
                                      int mouseX, int mouseY) {
        int bw = 80;
        boolean hov = inside(mouseX, mouseY, x + 4, y, bw, 16);
        hoveredLoadMore = hov ? 1 : 0;
        gfx.fill(x + 4, y, x + 4 + bw, y + 16, hov ? C_PURPLE : C_PANEL2);
        gfx.fill(x + 5, y + 1, x + 3 + bw, y + 15, hov ? 0xFF4A2ECC : C_PANEL);
        int tw = font.width("Load More");
        gfx.drawString(font, "Load More", x + 4 + (bw - tw) / 2, y + 4, C_CYAN, false);
    }

    private void renderRefreshButton(GuiGraphics gfx, Font font, int x, int y, int mouseX, int mouseY) {
        boolean hov = inside(mouseX, mouseY, x, y, 72, 16);
        gfx.fill(x, y, x + 72, y + 16, hov ? C_PANEL : C_PANEL2);
        gfx.drawString(font, "⟳ Refresh", x + 4, y + 4, hov ? C_CYAN : C_SEC, false);
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    public void mouseClicked(int mouseX, int mouseY, int x, int y, int w, int h,
                             Font font) {
        // Load more
        if (hoveredLoadMore == 1 && !isLoading) {
            isLoading = true;
            HistoryController.getInstance().loadNextPage().thenAccept(result -> {
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    isLoading = false;
                    statusMessage = result.success() ? "" : result.errorMessage();
                });
            });
        }

        // Refresh button area
        if (inside(mouseX, mouseY, x + w - 76, y + h - 20, 72, 16)) {
            HistoryController.getInstance().clearCache();
            loadHistory();
        }

        // Re-download on row double-click (select → re-download on second click)
        // For now: single click on row logs the item
        if (hoveredRow >= 0) {
            List<XeltaHistoryClient.HistoryItem> items = HistoryController.getInstance().getCachedItems();
            if (hoveredRow < items.size()) {
                XeltaHistoryClient.HistoryItem item = items.get(hoveredRow);
                if (item.hasResult()) {
                    HistoryController.getInstance().reDownload(item)
                        .thenAccept(pathOpt ->
                            pathOpt.ifPresent(p ->
                                net.minecraft.client.Minecraft.getInstance().execute(() ->
                                    statusMessage = "Downloaded: " + p.getFileName())));
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int typeBadgeColor(String type) {
        if (type == null) return DashboardTab.C_SEC;
        return switch (type.toLowerCase()) {
            case "image"  -> DashboardTab.C_CYAN;
            case "video"  -> DashboardTab.C_PURPLE;
            case "avatar" -> DashboardTab.C_GREEN;
            case "mix"    -> DashboardTab.C_GOLD;
            default       -> DashboardTab.C_SEC;
        };
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
