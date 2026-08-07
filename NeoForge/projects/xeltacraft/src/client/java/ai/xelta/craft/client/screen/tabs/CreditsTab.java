package ai.xelta.craft.client.screen.tabs;

import ai.xelta.craft.api.XeltaCreditClient;
import ai.xelta.craft.controller.CreditController;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * CreditsTab — UI Layer
 *
 * Displays the user's credit balance with breakdown and a purchase link.
 *
 * Features:
 *  • Large balance display with tier colour
 *  • Refresh button
 *  • Error state with helpful messages
 *  • "Buy Credits" button that opens browser
 *  • Usage guide text
 */
public class CreditsTab {

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

    // ── State ─────────────────────────────────────────────────────────────────
    private String errorMessage = "";
    private boolean isRefreshing = false;
    private boolean refreshHovered = false;
    private boolean buyHovered = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void onShow() {
        if (CreditController.getInstance().getCachedBalance() < 0 && !isRefreshing) {
            refresh();
        }
    }

    private void refresh() {
        isRefreshing = true;
        errorMessage = "";
        CreditController.getInstance().getFullResult().thenAccept(result -> {
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                isRefreshing = false;
                if (!result.success()) {
                    errorMessage = result.message();
                }
            });
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public void render(GuiGraphics gfx, Font font, int x, int y, int w, int h, int mouseX, int mouseY) {
        // ── Balance Card ──────────────────────────────────────────────────────
        int cardW = 220, cardH = 80;
        int cardX = x + (w - cardW) / 2;
        int cardY = y + 12;

        int balance = CreditController.getInstance().getCachedBalance();
        int balanceColor = balanceTierColor(balance);

        // Card border + fill
        gfx.fill(cardX - 1, cardY - 1, cardX + cardW + 1, cardY + cardH + 1, balanceColor);
        gfx.fill(cardX, cardY, cardX + cardW, cardY + cardH, C_PANEL);

        // Top accent bar
        gfx.fill(cardX, cardY, cardX + cardW, cardY + 3, balanceColor);

        gfx.drawString(font, "CREDIT BALANCE", cardX + 8, cardY + 8, C_SEC, false);

        if (isRefreshing) {
            gfx.drawString(font, "Fetching balance...", cardX + 8, cardY + 28, C_CYAN, false);
        } else if (balance < 0) {
            gfx.drawString(font, errorMessage.isBlank() ? "Unavailable" : errorMessage,
                cardX + 8, cardY + 28, C_RED, false);
        } else {
            // Large balance number
            String formatted = CreditController.getInstance().getFormattedBalance();
            gfx.drawString(font, formatted, cardX + 8, cardY + 26, balanceColor, false);

            // Tier label
            String tier = balanceTierLabel(balance);
            gfx.drawString(font, tier, cardX + 8, cardY + 42, C_SEC, false);

            // Progress bar (visual only — 0 to 10K as "full")
            int barW = cardW - 16;
            int fillW = (int) Math.min(barW, (balance / 10000.0) * barW);
            gfx.fill(cardX + 8, cardY + 58, cardX + 8 + barW, cardY + 66, C_PANEL2);
            gfx.fill(cardX + 8, cardY + 58, cardX + 8 + fillW, cardY + 66, balanceColor);
            gfx.drawString(font, "/ 10,000", cardX + 8 + barW - 40, cardY + 58, C_SEC, false);
        }

        // ── Buttons row ───────────────────────────────────────────────────────
        int btnY = cardY + cardH + 12;

        refreshHovered = inside(mouseX, mouseY, cardX, btnY, 80, 20);
        gfx.fill(cardX - 1, btnY - 1, cardX + 81, btnY + 21, refreshHovered ? C_CYAN : C_BDIM);
        gfx.fill(cardX, btnY, cardX + 80, btnY + 20, refreshHovered ? 0xFF0A303A : C_PANEL);
        int tw = font.width("⟳ Refresh");
        gfx.drawString(font, "⟳ Refresh", cardX + (80 - tw) / 2, btnY + 6, refreshHovered ? C_CYAN : C_SEC, false);

        int buyX = cardX + 88;
        buyHovered = inside(mouseX, mouseY, buyX, btnY, 80, 20);
        gfx.fill(buyX - 1, btnY - 1, buyX + 81, btnY + 21, buyHovered ? C_PURPLE : C_BDIM);
        gfx.fill(buyX, btnY, buyX + 80, btnY + 20, buyHovered ? 0xFF2A1060 : C_PANEL);
        tw = font.width("Buy Credits");
        gfx.drawString(font, "Buy Credits", buyX + (80 - tw) / 2, btnY + 6,
            buyHovered ? 0xFFBBABFF : C_SEC, false);

        // ── Usage guide ───────────────────────────────────────────────────────
        int guideY = btnY + 32;
        gfx.drawString(font, "HOW CREDITS ARE USED", x + 4, guideY, C_SEC, false);

        String[] guide = {
            "• Image generation: ~5–20 credits",
            "• Video generation: ~50–200 credits",
            "• Avatar generation: ~30–100 credits",
            "• Mix / Style transfer: ~10–30 credits",
        };
        for (int i = 0; i < guide.length; i++) {
            gfx.drawString(font, guide[i], x + 8, guideY + 12 + i * 11, C_PRI, false);
        }

        // ── Error strip ───────────────────────────────────────────────────────
        if (!errorMessage.isBlank() && !isRefreshing) {
            gfx.fill(x, y + h - 20, x + w, y + h, 0xFF2A0808);
            gfx.drawString(font, "⚠ " + errorMessage, x + 4, y + h - 14, C_RED, false);
        }
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    public void mouseClicked(int mouseX, int mouseY, int x, int y, int w) {
        int cardW = 220;
        int cardX = x + (w - cardW) / 2;
        int cardY = y + 12;
        int btnY = cardY + 80 + 12;

        if (refreshHovered && !isRefreshing) {
            refresh();
        }

        if (buyHovered) {
            try {
                java.awt.Desktop.getDesktop()
                    .browse(new java.net.URI("https://xelta.ai/pricing"));
            } catch (Exception ignored) {}
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int balanceTierColor(int balance) {
        if (balance < 0)       return DashboardTab.C_SEC;
        if (balance < 100)     return DashboardTab.C_RED;
        if (balance < 500)     return DashboardTab.C_GOLD;
        if (balance < 2000)    return DashboardTab.C_CYAN;
        return DashboardTab.C_GREEN;
    }

    private static String balanceTierLabel(int balance) {
        if (balance < 0)    return "";
        if (balance < 100)  return "Low — consider topping up";
        if (balance < 500)  return "Basic";
        if (balance < 2000) return "Standard";
        return "Pro";
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
