package ai.xelta.craft.client.screen.tabs;

import ai.xelta.craft.api.auth.XeltaSessionManager;
import ai.xelta.craft.config.XeltaCraftConfig;
import ai.xelta.craft.controller.CreditController;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * SettingsTab — UI Layer
 *
 * In-game configuration and session management.
 *
 * Sections:
 *  • Token  — paste full JWT token directly (bypasses 256-char chat limit)
 *  • Session — shows login state, logout button
 *  • Service URLs — configurable base URLs for each Xelta service
 *  • Behaviour — debug mode toggle, timeout settings
 *  • About — mod version, Xelta branding
 */
public class SettingsTab {

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
    private static final int C_TAG_BG = DashboardTab.C_TAG_BG;

    // ── Hover state ───────────────────────────────────────────────────────────
    private boolean logoutHovered  = false;
    private boolean debugHovered   = false;
    private boolean saveTokenHov   = false;

    // ── Token paste box ───────────────────────────────────────────────────────
    // Managed by the parent screen via init() / tick() delegation
    private EditBox tokenBox;
    private String  tokenSaveMsg   = "";
    private boolean tokenSaveMsgOk = true;

    // Cached Y position of the token save button (computed at render time)
    private int tokenSaveBtnY = 0;

    // ── Init (called from XeltaTerminalScreen.init when tab is visible) ───────

    /**
     * Creates and returns the token EditBox so the parent screen can register it
     * as a Vanilla widget (required for keyboard focus to work).
     * Call this from XeltaTerminalScreen.init() when the Settings tab is selected.
     *
     * @param font  Minecraft font renderer
     * @param x     Left edge of settings content area
     * @param y     Top edge of settings content area (after tab bar)
     * @param w     Width of settings content area
     */
    public EditBox buildTokenBox(Font font, int x, int y, int w) {
        int fieldX = x + 8;
        int fieldW = w - 16 - 52; // leave room for Save button
        int fieldY = y + 4 + 13 + 18 + 2; // after TOKEN section header

        tokenBox = new EditBox(font, fieldX, fieldY, fieldW, 18,
            Component.literal("Paste JWT token"));
        tokenBox.setMaxLength(2048);          // JWT tokens are ~460 chars — well within
        tokenBox.setHint(Component.literal("Paste your Xelta JWT token here (Ctrl+V)"));
        tokenBox.setValue(maskToken(XeltaCraftConfig.API_KEY.get()));
        return tokenBox;
    }

    /** Called each game tick — no manual EditBox ticking needed in NeoForge 1.21.1. */
    public void tick() {
        // EditBox cursor updates are handled by the Screen widget system automatically.
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public void render(GuiGraphics gfx, Font font, int x, int y, int w, int h,
                       int mouseX, int mouseY) {

        int cy = y + 4;
        int sectionW = w - 8;

        // ── TOKEN SECTION ─────────────────────────────────────────────────────
        cy = renderSectionHeader(gfx, font, x + 4, cy, sectionW, "AUTH TOKEN");

        gfx.drawString(font, "Paste your full JWT token and click Save:",
            x + 8, cy, C_SEC, false);
        cy += 12;

        // The EditBox is rendered by the parent screen as a native widget.
        // We just render the Save button and status message here.
        int boxH   = 18;
        int btnX   = x + sectionW - 48;
        int btnY   = cy;
        tokenSaveBtnY = btnY; // cache for click detection

        saveTokenHov = inside(mouseX, mouseY, btnX, btnY, 46, boxH);
        gfx.fill(btnX, btnY, btnX + 46, btnY + boxH,
            saveTokenHov ? C_PURPLE : 0xFF2A1860);
        gfx.fill(btnX, btnY, btnX + 46, btnY + 1, C_PURPLE);
        gfx.drawString(font, "Save", btnX + 13, btnY + 5,
            saveTokenHov ? 0xFFFFFFFF : C_CYAN, false);

        cy += boxH + 4;

        // Status message (shown after Save click)
        if (!tokenSaveMsg.isEmpty()) {
            gfx.drawString(font, tokenSaveMsg, x + 8, cy,
                tokenSaveMsgOk ? C_GREEN : C_RED, false);
        }
        cy += 14;

        // ── SESSION SECTION ───────────────────────────────────────────────────
        cy = renderSectionHeader(gfx, font, x + 4, cy, sectionW, "SESSION");

        boolean authed = XeltaSessionManager.getInstance().isAuthenticated();
        String authLabel = authed ? "● Signed in" : "○ Not signed in";
        int authColor = authed ? C_GREEN : C_RED;
        gfx.drawString(font, authLabel, x + 8, cy + 2, authColor, false);

        logoutHovered = inside(mouseX, mouseY, x + sectionW - 62, cy, 60, 14) && authed;
        if (authed) {
            int lbg = logoutHovered ? 0xFF2A0808 : C_PANEL;
            gfx.fill(x + sectionW - 62, cy, x + sectionW - 2, cy + 14, lbg);
            gfx.fill(x + sectionW - 62, cy, x + sectionW - 2, cy + 1, C_RED);
            gfx.drawString(font, "Sign Out", x + sectionW - 58, cy + 3,
                logoutHovered ? C_RED : C_SEC, false);
        }
        cy += 20;

        // ── BEHAVIOUR SECTION ─────────────────────────────────────────────────
        cy = renderSectionHeader(gfx, font, x + 4, cy, sectionW, "BEHAVIOUR");

        boolean debug = XeltaCraftConfig.DEBUG_MODE.get();
        debugHovered = inside(mouseX, mouseY, x + 4, cy, sectionW, 14);
        gfx.fill(x + 4, cy, x + 4 + sectionW, cy + 14, debugHovered ? C_PANEL : C_PANEL2);
        gfx.drawString(font, "Debug Mode (verbose logging)", x + 8, cy + 3, C_SEC, false);
        int toggleColor = debug ? C_GREEN : C_BDIM;
        gfx.fill(x + sectionW - 28, cy + 3, x + sectionW - 4, cy + 11, toggleColor);
        gfx.drawString(font, debug ? "ON" : "OFF", x + sectionW - 26, cy + 4,
            debug ? 0xFF000000 : C_SEC, false);
        cy += 18;

        gfx.drawString(font, "Timeout: " + XeltaCraftConfig.TIMEOUT_SECONDS.get() + "s  "
            + "Max Retries: " + XeltaCraftConfig.MAX_RETRIES.get(),
            x + 8, cy, C_SEC, false);
        cy += 14;

        // ── SERVICE URLS SECTION ──────────────────────────────────────────────
        cy = renderSectionHeader(gfx, font, x + 4, cy, sectionW, "SERVICE URLS");

        String[][] urls = {
            { "Photo Lab (Image)",    XeltaCraftConfig.PHOTO_LAB_URL.get() },
            { "Motion Control (Vid)", XeltaCraftConfig.MOTION_CONTROL_URL.get() },
            { "Credit API",           XeltaCraftConfig.CREDIT_API_URL.get() },
            { "GenAvatar",            XeltaCraftConfig.AVATAR_API_URL.get() },
            { "Xelta Mix",            XeltaCraftConfig.XELTA_MIX_URL.get() },
            { "SuperXelta",           XeltaCraftConfig.SUPER_XELTA_URL.get() },
        };

        for (String[] row : urls) {
            String label = row[0] + ":";
            String urlText = row[1];
            if (font.width(urlText) > sectionW - font.width(label) - 16) {
                while (!urlText.isEmpty() && font.width(urlText + "…") > sectionW - font.width(label) - 16) {
                    urlText = urlText.substring(0, urlText.length() - 1);
                }
                urlText += "…";
            }
            gfx.drawString(font, label, x + 8, cy, C_SEC, false);
            gfx.drawString(font, urlText, x + 8 + font.width(label) + 4, cy, C_CYAN, false);
            cy += 11;
        }
        cy += 4;

        // ── ABOUT SECTION ─────────────────────────────────────────────────────
        cy = renderSectionHeader(gfx, font, x + 4, cy, sectionW, "ABOUT");
        gfx.drawString(font, "XeltaCraft — Xelta AI × Minecraft NeoForge", x + 8, cy, C_PRI, false);
        cy += 11;
        gfx.drawString(font, "Version: 1.0.0-alpha  |  NeoForge 21.1.172", x + 8, cy, C_SEC, false);
        cy += 11;
        gfx.drawString(font, "Visit: xelta.ai  |  discord.gg/xelta", x + 8, cy, C_CYAN, false);
    }

    private int renderSectionHeader(GuiGraphics gfx, Font font, int x, int y, int w, String title) {
        gfx.fill(x, y, x + w, y + 13, C_PANEL2);
        gfx.fill(x, y + 13, x + w, y + 14, C_CYAN);
        gfx.drawString(font, title, x + 4, y + 2, C_CYAN, false);
        return y + 18;
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    public void mouseClicked(int mouseX, int mouseY, int x, int y, int w) {
        int sectionW = w - 8;

        // Token EditBox click — delegate to Vanilla widget (handled by parent screen)
        if (tokenBox != null) tokenBox.mouseClicked(mouseX, mouseY, 0);

        // Save Token button
        int btnX = x + sectionW - 48;
        if (inside(mouseX, mouseY, btnX, tokenSaveBtnY, 46, 18)) {
            saveToken();
            return;
        }

        // Logout button
        if (logoutHovered) {
            XeltaSessionManager.getInstance().logout();
            CreditController.getInstance().invalidate();
            tokenSaveMsg = "Signed out.";
            tokenSaveMsgOk = false;
        }

        // Debug toggle
        int debugY = y + 4 + 13 + 18 + 18 + 2 + 12 + 18 + 4 + 14 + 13 + 18;
        if (inside(mouseX, mouseY, x + 4, debugY, sectionW, 14)) {
            boolean current = XeltaCraftConfig.DEBUG_MODE.get();
            XeltaCraftConfig.DEBUG_MODE.set(!current);
            XeltaCraftConfig.SPEC.save();
        }
    }

    // ── Key / Char input ──────────────────────────────────────────────────────

    /** Forward keyboard input to the token EditBox. */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (tokenBox != null && tokenBox.isFocused()) {
            return tokenBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return false;
    }

    /** Forward char typing to the token EditBox. */
    public boolean charTyped(char c, int modifiers) {
        if (tokenBox != null && tokenBox.isFocused()) {
            return tokenBox.charTyped(c, modifiers);
        }
        return false;
    }

    // ── Save logic ────────────────────────────────────────────────────────────

    private void saveToken() {
        if (tokenBox == null) return;
        String raw = tokenBox.getValue().trim();

        if (raw.isBlank()) {
            tokenSaveMsg = "⚠ Token field is empty.";
            tokenSaveMsgOk = false;
            return;
        }
        if (!raw.startsWith("eyJ")) {
            tokenSaveMsg = "⚠ Not a valid JWT (must start with eyJ...)";
            tokenSaveMsgOk = false;
            return;
        }
        if (raw.split("\\.").length != 3) {
            tokenSaveMsg = "⚠ Invalid JWT format (needs 3 parts).";
            tokenSaveMsgOk = false;
            return;
        }

        // Save to config
        XeltaCraftConfig.API_KEY.set(raw);
        XeltaCraftConfig.SPEC.save();

        // Inject into live session immediately
        XeltaSessionManager.getInstance().setManualToken(raw);

        tokenSaveMsg = "✓ Token saved & applied!";
        tokenSaveMsgOk = true;

        // Mask the box for security
        tokenBox.setValue(maskToken(raw));
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static String maskToken(String token) {
        if (token == null || token.length() < 20) return token;
        // Show first 6 and last 4 characters only
        return token.substring(0, 6) + "••••••••" + token.substring(token.length() - 4);
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
