package ai.xelta.craft.client.screen.tabs;

import ai.xelta.craft.controller.AssetController;
import ai.xelta.craft.controller.CreditController;
import ai.xelta.craft.controller.GenerationController;
import ai.xelta.craft.sdk.XeltaSDK;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * DashboardTab — UI Layer
 *
 * The main landing tab after login. Displays:
 *  • AI tool cards (Image, Video, Avatar, Mix, Build)
 *  • Credit balance chip
 *  • Quick-generate area (prompt input + generate button)
 *  • Active job count widget
 *
 * Design: dark glassmorphism, matching the terminal palette.
 */
public class DashboardTab {

    // ── Colours ───────────────────────────────────────────────────────────────
    static final int C_PANEL    = 0xFF161A28;
    static final int C_PANEL2   = 0xFF0D101C;
    static final int C_BDIM     = 0xFF1E3A4A;
    static final int C_PRI      = 0xFFE8EAF2;
    static final int C_SEC      = 0xFF7A8BA0;
    static final int C_CYAN     = 0xFF3DD6F5;
    static final int C_GREEN    = 0xFF2DBE5A;
    static final int C_GDIM     = 0xFF0D2A1A;
    static final int C_PURPLE   = 0xFF6C47FF;
    static final int C_PLIGHT   = 0xFF8866FF;
    static final int C_RED      = 0xFFFF5555;
    static final int C_GOLD     = 0xFFFFCC44;
    static final int C_TAG_BG   = 0xFF202638;

    // ── Tool Cards ────────────────────────────────────────────────────────────
    private static final String[] TOOL_NAMES   = { "Image", "Video", "Avatar", "Mix", "Build" };
    private static final int[]    TOOL_COLORS  = {
        0xFF3DD6F5,  // Image  — cyan
        0xFF6C47FF,  // Video  — purple
        0xFF2DBE5A,  // Avatar — green
        0xFFFFCC44,  // Mix    — gold
        0xFFFF8844,  // Build  — orange
    };
    private static final String[] TOOL_DESCS   = {
        "AI image from prompt",
        "AI video generation",
        "Talking avatar",
        "Style transfer mix",
        "World architecture"
    };

    // ── State ─────────────────────────────────────────────────────────────────
    private int selectedTool = 0;
    private int hoveredTool  = -1;

    // ── Render ────────────────────────────────────────────────────────────────

    /**
     * Renders the dashboard tab content area.
     *
     * @param gfx     GuiGraphics
     * @param font    Minecraft font
     * @param x       Left edge of the content area
     * @param y       Top edge of the content area
     * @param w       Width of the content area
     * @param h       Height of the content area
     * @param mouseX  Mouse X
     * @param mouseY  Mouse Y
     */
    public void render(GuiGraphics gfx, Font font, int x, int y, int w, int h, int mouseX, int mouseY) {
        // ── Section: AI Tools Grid ────────────────────────────────────────────
        gfx.drawString(font, "AI TOOLS", x + 4, y + 4, C_SEC, false);

        int cardW = (w - 20) / 5;
        int cardH = 56;
        int cardY = y + 16;

        hoveredTool = -1;
        for (int i = 0; i < TOOL_NAMES.length; i++) {
            int cx = x + 4 + i * (cardW + 4);
            boolean hovered = inside(mouseX, mouseY, cx, cardY, cardW, cardH);
            boolean selected = (i == selectedTool);
            if (hovered) hoveredTool = i;

            renderToolCard(gfx, font, cx, cardY, cardW, cardH, i, hovered, selected);
        }

        // ── Section: Stats Row ────────────────────────────────────────────────
        int statsY = cardY + cardH + 10;
        renderStatsRow(gfx, font, x, statsY, w);

        // ── Section: Quick tip ────────────────────────────────────────────────
        int tipY = statsY + 26;
        gfx.drawString(font, "SELECTED: " + TOOL_NAMES[selectedTool], x + 4, tipY, C_CYAN, false);
        gfx.drawString(font, TOOL_DESCS[selectedTool], x + 4, tipY + 12, C_SEC, false);
    }

    private void renderToolCard(GuiGraphics gfx, Font font, int cx, int cy, int cw, int ch,
                                int index, boolean hovered, boolean selected) {
        int color = TOOL_COLORS[index];
        int bg    = selected ? 0xFF1E2840 : (hovered ? 0xFF1A1E30 : C_PANEL);
        int border = selected ? color : (hovered ? 0xFF304060 : C_BDIM);

        // Border + fill
        gfx.fill(cx - 1, cy - 1, cx + cw + 1, cy + ch + 1, border);
        gfx.fill(cx, cy, cx + cw, cy + ch, bg);

        // Colour chip on top
        gfx.fill(cx, cy, cx + cw, cy + 3, color);

        // Icon area (simple coloured square)
        int ix = cx + 6, iy = cy + 8;
        gfx.fill(ix, iy, ix + 14, iy + 14, 0x33FFFFFF & color | 0x55000000);
        gfx.fill(ix + 2, iy + 2, ix + 12, iy + 12, color);

        // Name
        gfx.drawString(font, TOOL_NAMES[index], cx + 24, cy + 10, C_PRI, false);

        // Status dot
        gfx.fill(cx + cw - 10, cy + 6, cx + cw - 4, cy + 12, C_GREEN);
    }

    private void renderStatsRow(GuiGraphics gfx, Font font, int x, int y, int w) {
        // Cached assets count
        int assetCount = AssetController.getInstance().getCachedAssetCount();
        renderStatChip(gfx, font, x + 4, y, "Gallery", assetCount + " assets", C_CYAN);

        // Credit balance
        String balance = CreditController.getInstance().getFormattedBalance();
        renderStatChip(gfx, font, x + 100, y, "Credits", balance, C_GOLD);

        // Auth status
        boolean authed = XeltaSDK.get().isAuthenticated();
        renderStatChip(gfx, font, x + 210, y, "Session", authed ? "Active" : "Guest", authed ? C_GREEN : C_RED);
    }

    private void renderStatChip(GuiGraphics gfx, Font font, int x, int y, String label, String value, int color) {
        int chipW = 88;
        int chipH = 22;
        gfx.fill(x - 1, y - 1, x + chipW + 1, y + chipH + 1, color);
        gfx.fill(x, y, x + chipW, y + chipH, C_TAG_BG);
        gfx.drawString(font, label, x + 4, y + 3, C_SEC, false);
        gfx.drawString(font, value, x + 4, y + 12, C_PRI, false);
    }

    // ── Mouse Interaction ─────────────────────────────────────────────────────

    /**
     * Handle a left click in the dashboard content area.
     *
     * @return the service index if a tool card was clicked, -1 otherwise
     */
    public int mouseClicked(int mouseX, int mouseY, int x, int y, int w) {
        int cardW = (w - 20) / 5;
        int cardH = 56;
        int cardY = y + 16;

        for (int i = 0; i < TOOL_NAMES.length; i++) {
            int cx = x + 4 + i * (cardW + 4);
            if (inside(mouseX, mouseY, cx, cardY, cardW, cardH)) {
                selectedTool = i;
                return i;
            }
        }
        return -1;
    }

    public int getSelectedTool() { return selectedTool; }
    public void setSelectedTool(int i) { if (i >= 0 && i < TOOL_NAMES.length) selectedTool = i; }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
