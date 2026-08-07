package ai.xelta.craft.client.screen;

import ai.xelta.craft.api.auth.XeltaAuthClient;
import ai.xelta.craft.api.auth.XeltaSessionManager;
import ai.xelta.craft.menu.XeltaTerminalMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * XeltaLoginScreen — Authentication UI
 *
 * Displayed when the player opens the Xelta Terminal and is NOT authenticated.
 * After a successful login, the Terminal transitions to XeltaTerminalScreen (dashboard).
 *
 * Design language: dark glassmorphism matching XeltaTerminalScreen palette.
 *
 *   ┌─────────────────────────────────┐
 *   │        XELTA AI TERMINAL        │
 *   │   Sign in to your Xelta account │
 *   │                                 │
 *   │  [ Email address            ]   │
 *   │  [ Password                 ]   │
 *   │                                 │
 *   │        [ SIGN IN ]              │
 *   │                                 │
 *   │  ⚠ Error message here           │
 *   └─────────────────────────────────┘
 */
public class XeltaLoginScreen extends AbstractContainerScreen<XeltaTerminalMenu> {

    // ── Colour Palette (matches XeltaTerminalScreen) ──────────────────────────
    private static final int C_BG      = 0xFF0E1117;
    private static final int C_HEADER  = 0xFF0B0D18;
    private static final int C_PANEL   = 0xFF161A28;
    private static final int C_PANEL2  = 0xFF0D101C;
    private static final int C_BORDER  = 0xFF1E3A4A;
    private static final int C_PRI     = 0xFFE8EAF2;
    private static final int C_SEC     = 0xFF7A8BA0;
    private static final int C_CYAN    = 0xFF3DD6F5;
    private static final int C_GREEN   = 0xFF2DBE5A;
    private static final int C_PURPLE  = 0xFF6C47FF;
    private static final int C_PLIGHT  = 0xFF8866FF;
    private static final int C_RED     = 0xFFFF5555;
    private static final int C_GOLD    = 0xFFFFCC44;
    private static final int C_TAG_BG  = 0xFF202638;

    // ── Layout Constants ──────────────────────────────────────────────────────
    private static final double SCALE     = 0.80D;
    private static final int HEADER_H    = 56;
    private static final int CARD_W      = 280;
    private static final int CARD_H      = 220;
    private static final int FIELD_H     = 22;
    private static final int BTN_H       = 28;

    // ── Widgets ───────────────────────────────────────────────────────────────
    private EditBox emailField;
    private EditBox passwordField;

    // ── State ─────────────────────────────────────────────────────────────────
    private String statusMessage = "";
    private boolean statusIsError = false;
    private boolean isLoading = false;
    private boolean signInHovered = false;

    // ── Card Position (computed in init) ──────────────────────────────────────
    private int cardX, cardY;

    // ── Reference to menu (needed to open terminal after login) ───────────────
    private final XeltaTerminalMenu terminalMenu;
    private final Inventory playerInventory;

    public XeltaLoginScreen(XeltaTerminalMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.terminalMenu    = menu;
        this.playerInventory = inv;
    }

    @Override
    protected void init() {
        super.init();

        // Suppress the default container title rendering
        this.titleLabelX = -9999;
        this.inventoryLabelX = -9999;

        // Center card
        cardX = (this.width  - CARD_W) / 2;
        cardY = (this.height - CARD_H) / 2;

        int fieldX = cardX + 20;
        int fieldW = CARD_W - 40;

        // Email
        emailField = new EditBox(
            this.font,
            fieldX + 2, cardY + HEADER_H + 16 + 2,
            fieldW - 4, FIELD_H - 4,
            Component.literal("email")
        );
        emailField.setMaxLength(200);
        emailField.setBordered(false);
        emailField.setTextColor(0xFFDDEEFF);
        emailField.setHint(Component.literal("Email address")
            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        this.addRenderableWidget(emailField);

        // Password
        passwordField = new EditBox(
            this.font,
            fieldX + 2, cardY + HEADER_H + 16 + FIELD_H + 12 + 2,
            fieldW - 4, FIELD_H - 4,
            Component.literal("password")
        );
        passwordField.setMaxLength(200);
        passwordField.setBordered(false);
        passwordField.setTextColor(0xFFDDEEFF);
        passwordField.setHint(Component.literal("Password")
            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        // Mask password characters
        passwordField.setFormatter((text, pos) -> {
            StringBuilder masked = new StringBuilder();
            for (int i = 0; i < text.length(); i++) masked.append('•');
            return net.minecraft.util.FormattedCharSequence.forward(
                masked.toString(), net.minecraft.network.chat.Style.EMPTY.withColor(0xFFDDEEFF));
        });
        this.addRenderableWidget(passwordField);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx, mouseX, mouseY, partialTick);
        super.render(gfx, mouseX, mouseY, partialTick);
        this.renderTooltip(gfx, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        // Full screen dark overlay
        gfx.fill(0, 0, this.width, this.height, 0xCC060810);

        // ── Card ──────────────────────────────────────────────────────────────
        // Outer glow border
        gfx.fill(cardX - 1, cardY - 1, cardX + CARD_W + 1, cardY + CARD_H + 1, C_PURPLE);
        // Card background
        gfx.fill(cardX, cardY, cardX + CARD_W, cardY + CARD_H, C_BG);

        // ── Header area ───────────────────────────────────────────────────────
        gfx.fill(cardX, cardY, cardX + CARD_W, cardY + HEADER_H, C_HEADER);
        // Cyan accent line
        gfx.fill(cardX, cardY + HEADER_H - 1, cardX + CARD_W, cardY + HEADER_H, C_CYAN);

        // Logo icon
        int ix = cardX + 14, iy = cardY + 12;
        gfx.fill(ix, iy, ix + 18, iy + 18, C_PANEL);
        gfx.fill(ix + 1, iy + 1, ix + 17, iy + 17, C_PANEL2);
        gfx.fill(ix + 3, iy + 4, ix + 9,  iy + 9,  C_CYAN);
        gfx.fill(ix + 9, iy + 4, ix + 15, iy + 9,  0xFF2AAAD8);

        // Title text
        gfx.drawString(this.font, "XELTA AI TERMINAL", cardX + 40, cardY + 11, C_CYAN, false);
        gfx.drawString(this.font, "Sign in to your Xelta account", cardX + 40, cardY + 23, C_SEC, false);

        // ── Email field ───────────────────────────────────────────────────────
        int fX = cardX + 20;
        int fW = CARD_W - 40;
        int emailY = cardY + HEADER_H + 16;

        drawFieldLabel(gfx, "EMAIL ADDRESS", fX, emailY - 10);
        drawFieldBox(gfx, fX, emailY, fW, FIELD_H, emailField.isFocused());

        // ── Password field ────────────────────────────────────────────────────
        int passY = emailY + FIELD_H + 12;
        drawFieldLabel(gfx, "PASSWORD", fX, passY - 10);
        drawFieldBox(gfx, fX, passY, fW, FIELD_H, passwordField.isFocused());

        // ── Sign In button ────────────────────────────────────────────────────
        int btnY = passY + FIELD_H + 20;
        int btnX = cardX + 20;
        int btnW = CARD_W - 40;
        signInHovered = inside(mouseX, mouseY, btnX, btnY, btnW, BTN_H);

        if (isLoading) {
            // Loading state
            gfx.fill(btnX, btnY, btnX + btnW, btnY + BTN_H, C_PANEL);
            gfx.fill(btnX + 1, btnY + 1, btnX + btnW - 1, btnY + BTN_H - 1, C_PANEL2);
            String dots = switch ((int)(System.currentTimeMillis() / 400 % 4)) {
                case 0 -> "Signing in .  ";
                case 1 -> "Signing in .. ";
                case 2 -> "Signing in ...";
                default -> "Signing in    ";
            };
            int tw = this.font.width(dots);
            gfx.drawString(this.font, dots, btnX + (btnW - tw) / 2, btnY + 10, C_CYAN, false);
        } else {
            // Normal or hovered button
            int btnBg  = signInHovered ? C_PURPLE : 0xFF4A2ECC;
            int btnBdr = signInHovered ? C_PLIGHT : C_PURPLE;
            gfx.fill(btnX - 1, btnY - 1, btnX + btnW + 1, btnY + BTN_H + 1, btnBdr);
            gfx.fill(btnX, btnY, btnX + btnW, btnY + BTN_H, btnBg);
            String label = "SIGN IN";
            int tw = this.font.width(label);
            gfx.drawString(this.font, label, btnX + (btnW - tw) / 2, btnY + 10, C_PRI, false);
        }

        // ── Status message ────────────────────────────────────────────────────
        if (!statusMessage.isBlank()) {
            int statusY = btnY + BTN_H + 10;
            int color = statusIsError ? C_RED : C_GREEN;
            String prefix = statusIsError ? "⚠ " : "✓ ";
            int tw = this.font.width(prefix + statusMessage);
            gfx.drawString(this.font, prefix + statusMessage, cardX + (CARD_W - tw) / 2, statusY, color, false);
        }

        // ── Hint ─────────────────────────────────────────────────────────────
        String hint = "Or use: /xeltacraft settoken <your-token>";
        int htw = this.font.width(hint);
        gfx.drawString(this.font, hint, cardX + (CARD_W - htw) / 2, cardY + CARD_H - 12, C_SEC, false);
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && !isLoading) {
            int btnX = cardX + 20;
            int btnY = cardY + HEADER_H + 16 + FIELD_H + 12 + FIELD_H + 20;
            int btnW = CARD_W - 40;

            if (inside((int)mx, (int)my, btnX, btnY, btnW, BTN_H)) {
                triggerLogin();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter key triggers login
        if (keyCode == 257 /* ENTER */ && !isLoading) {
            triggerLogin();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── Login Trigger ─────────────────────────────────────────────────────────

    private void triggerLogin() {
        String email = emailField.getValue().trim();
        String pass  = passwordField.getValue();

        if (email.isBlank()) {
            setStatus("Please enter your email address.", true);
            return;
        }
        if (pass.isBlank()) {
            setStatus("Please enter your password.", true);
            return;
        }

        isLoading = true;
        statusMessage = "";

        XeltaSessionManager.getInstance().login(email, pass).thenAccept(result -> {
            // Callback on virtual thread — schedule onto next render tick safely
            if (result.success()) {
                net.minecraft.client.Minecraft.getInstance().execute(() -> onLoginSuccess());
            } else {
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    isLoading = false;
                    setStatus(result.errorMessage(), true);
                    passwordField.setValue("");
                });
            }
        });
    }

    private void onLoginSuccess() {
        isLoading = false;
        // Replace this screen with the full terminal dashboard
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.setScreen(new XeltaTerminalScreen(terminalMenu, playerInventory,
            Component.literal("Xelta AI Terminal")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void drawFieldLabel(GuiGraphics gfx, String label, int x, int y) {
        gfx.drawString(this.font, label, x, y, C_SEC, false);
    }

    private void drawFieldBox(GuiGraphics gfx, int x, int y, int w, int h, boolean focused) {
        int border = focused ? C_PURPLE : C_BORDER;
        gfx.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
        gfx.fill(x, y, x + w, y + h, C_PANEL);
    }

    private void setStatus(String msg, boolean isError) {
        this.statusMessage = msg;
        this.statusIsError = isError;
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        // Suppress default title + inventory label rendering
    }
}
