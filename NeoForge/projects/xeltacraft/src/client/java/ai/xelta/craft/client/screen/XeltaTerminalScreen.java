package ai.xelta.craft.client.screen;

import ai.xelta.craft.api.auth.XeltaSessionManager;
import ai.xelta.craft.blockentity.XeltaTerminalBlockEntity.GenerationMode;
import ai.xelta.craft.client.screen.tabs.CreditsTab;
import ai.xelta.craft.client.screen.tabs.DashboardTab;
import ai.xelta.craft.client.screen.tabs.GalleryTab;
import ai.xelta.craft.client.screen.tabs.HistoryTab;
import ai.xelta.craft.client.screen.tabs.SettingsTab;
import ai.xelta.craft.client.screen.tabs.SuperComputerTab;
import ai.xelta.craft.controller.CreditController;
import ai.xelta.craft.controller.GenerationController;
import ai.xelta.craft.item.PhotoSlideItem;
import ai.xelta.craft.menu.XeltaTerminalMenu;
import ai.xelta.craft.network.C2SGeneratePacket;
import ai.xelta.craft.sdk.AssetCacheManager;
import ai.xelta.craft.sdk.AssetDownloader;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.FileInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XeltaTerminalScreen — Tabbed UI host screen.
 *
 * Tabs: Dashboard | Gallery | History | Credits | Settings
 * Auth-gated: opens XeltaLoginScreen if no session is active.
 */
public class XeltaTerminalScreen extends AbstractContainerScreen<XeltaTerminalMenu> {

    // ═══════════════════════════════════════════════════
    //  Dynamic Layout Anchors (Computed in init)
    // ═══════════════════════════════════════════════════
    private int W, H;
    private int LP_X, LP_W;
    private int RP_X, RP_W;
    private int CONTENT_Y;
    private int PANEL_BOTTOM;

    // ── Predefined Constants ─────────────────────────
    private static final int HEADER_H = 40;
    private static final int BAR_Y    = 40;
    private static final int BAR_H    = 20;
    private static final int ROW_GAP  = 32;
    private static final int TOKEN_GAP = 36;
    private static final int GENERATE_H = 28;
    private static final int STATUS_H = 20;
    private static final int SCREEN_MARGIN = 8;
    private static final int MIN_TERMINAL_H = 280;
    private static final double TERMINAL_SCALE = 0.80D;

    // Dropdown for Services
    private static final String[] SERVICES = { "Image", "Video", "Avatar", "Mix", "Build" };
    private boolean serviceDropdownOpen = false;

    // Slide Sizes
    private static final String[] SLIDE_SIZE_OPTIONS = { "1x1", "3x3", "5x5", "7x7" };

    // ── Tab System ──────────────────────────────────────────────────────────
    private static final String[] TAB_NAMES =
        { "Dashboard", "Generate", "Super Computer", "Gallery", "History", "Credits", "Settings" };
    private static final int TAB_GENERATE       = 1; // Gemini-powered Build + other Xelta services
    private static final int TAB_SUPER_COMPUTER = 2; // Nexus Deep Agent backend (chat + skills)
    private static final int TAB_GALLERY        = 3;
    private static final int TAB_HISTORY        = 4;
    private static final int TAB_CREDITS        = 5;
    private static final int TAB_SETTINGS       = 6;
    private int selectedTab = TAB_GENERATE;    // Default: Generate panel (existing layout)
    private int hoveredTab  = -1;
    private static final int TAB_BAR_H = 20;

    // ── Tab instances ────────────────────────────────────────────────────────
    private final DashboardTab      dashboardTab      = new DashboardTab();
    private final SuperComputerTab  superComputerTab  = new SuperComputerTab();
    private final GalleryTab        galleryTab        = new GalleryTab();
    private final HistoryTab        historyTab        = new HistoryTab();
    private final CreditsTab        creditsTab        = new CreditsTab();
    private final SettingsTab       settingsTab       = new SettingsTab();

    // ═══════════════════════════════════════════════════
    //  Colour palette
    // ═══════════════════════════════════════════════════
    private static final int C_BG       = 0xFF0E1117;
    private static final int C_HEADER   = 0xFF0B0D18;
    private static final int C_PANEL    = 0xFF161A28;
    private static final int C_PANEL2   = 0xFF0D101C;
    private static final int C_BDIM     = 0xFF1E3A4A;
    private static final int C_PRI      = 0xFFE8EAF2;
    private static final int C_SEC      = 0xFF7A8BA0;
    private static final int C_CYAN     = 0xFF3DD6F5;
    private static final int C_GREEN    = 0xFF2DBE5A;
    private static final int C_GDIM     = 0xFF0D2A1A;
    private static final int C_PURPLE   = 0xFF6C47FF;
    private static final int C_PLIGHT   = 0xFF8866FF;
    private static final int C_PDIM     = 0xFF2A1860;
    private static final int C_RED      = 0xFFFF5555;
    private static final int C_GOLD     = 0xFFFFCC44;
    private static final int C_TAG_BG   = 0xFF202638;

    // ═══════════════════════════════════════════════════
    //  State
    // ═══════════════════════════════════════════════════
    private boolean genHovered    = false;
    private boolean uploadHovered = false;
    private boolean serviceHovered = false;
    private int     hoveredSlide  = -1;
    
    private int     selectedService   = 4; // Build default
    private int     selectedSlideSize = 1; // 3x3 default
    private String  referenceImagePath  = ""; // local path, for the picker's own bookkeeping only
    private byte[]  referenceImageBytes = null; // read from disk — this is what actually gets sent

    private enum ReferenceUploadState { NONE, UPLOADING, READY, FAILED }
    private ReferenceUploadState referenceUploadState = ReferenceUploadState.NONE;

    private EditBox promptField;
    private EditBox tokenField;
    private String  statusText   = "Ready";
    private boolean statusIsErr  = false;
    private String  creditText   = "";

    // Store inventory locally — AbstractContainerScreen.playerInventory is not reliably
    // accessible from the client sourceSet under Parchment mappings (NeoForge 1.21.1).
    private final Inventory localInventory;

    // ── Preview panel image texture cache (keyed by image URL) ─────────────
    // Static so a texture already loaded once (e.g. re-opening the Terminal on
    // the same result) doesn't need to re-download.
    private static final Map<String, ResourceLocation> PREVIEW_TEXTURE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, int[]> PREVIEW_TEXTURE_SIZE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> PREVIEW_DOWNLOADING = new ConcurrentHashMap<>();
    private static final java.util.Set<String> PREVIEW_FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public XeltaTerminalScreen(XeltaTerminalMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.localInventory = playerInventory;
    }

    @Override
    protected void init() {
        super.init();

        // ── Auth gate: redirect to login if not authenticated ────────────────
        if (!XeltaSessionManager.getInstance().isAuthenticated()) {
            // Check if there's a legacy token in config and inject it into session
            String legacyToken = ai.xelta.craft.config.XeltaCraftConfig.API_KEY.get();
            if (!legacyToken.isBlank()) {
                XeltaSessionManager.getInstance().setManualToken(legacyToken);
            } else {
                // No token — open login screen instead
                net.minecraft.client.Minecraft.getInstance().execute(() ->
                    net.minecraft.client.Minecraft.getInstance().setScreen(
                        new XeltaLoginScreen(this.menu, this.localInventory,
                            Component.literal("Xelta AI Terminal"))));
                return;
            }
        }

        // Kick off async credit refresh for header display
        CreditController.getInstance().getBalance(false)
            .thenAccept(bal -> net.minecraft.client.Minecraft.getInstance().execute(() ->
                creditText = CreditController.getInstance().getFormattedBalance()));

        // Keep the terminal at 80% of the current Minecraft UI.
        this.W = (int) (this.width * TERMINAL_SCALE);
        this.H = Math.min(this.height - SCREEN_MARGIN, Math.max((int) (this.height * TERMINAL_SCALE), MIN_TERMINAL_H));

        this.imageWidth = W;
        this.imageHeight = H;
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;
        this.titleLabelX = -9999; 

        // Calculate internal anchors
        this.CONTENT_Y = 62;
        this.PANEL_BOTTOM = H - STATUS_H - 4;

        // Split remaining width roughly 50-50
        this.LP_X = 8;
        this.LP_W = (W - 24) / 2;

        this.RP_X = LP_X + LP_W + 8;
        this.RP_W = (W - 24) / 2;

        // ── Delegate widget setup to the tab lifecycle ─────────────────────────
        // Do NOT add widgets here directly — rebuildWidgets() manages all of them
        // per-tab so that switching tabs never leaves stale/overlapping widgets.
        rebuildWidgets(selectedTab);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx, mouseX, mouseY, partialTick);
        super.render(gfx, mouseX, mouseY, partialTick);
        this.renderTooltip(gfx, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        final int x = this.leftPos;
        final int y = this.topPos;

        // Evaluate hovers dynamically based on variables
        Layout layout = createLayout();
        
        hoveredSlide = -1;
        
        int slideAreaW = LP_W - 8;
        int slideBtnW = (slideAreaW - 9) / 4;
        int slideStartX = LP_X + 4;
        
        // Slide Size boxes hover
        for (int i = 0; i < 4; i++) {
            int bx = x + slideStartX + i*(slideBtnW + 3);
            if (inside(mouseX, mouseY, bx, y + layout.slideY, slideBtnW, 18)) {
                hoveredSlide = i;
            }
        }
        
        int uW = LP_W - 8;
        uploadHovered = referenceSupported(selectedService)
            && inside(mouseX, mouseY, x + LP_X + 4, y + layout.refY, uW, 18);
        
        genHovered = inside(mouseX, mouseY, x + LP_X + 2, y + layout.genY, LP_W - 4, layout.generateH);

        serviceHovered = inside(mouseX, mouseY, x + 8, y + BAR_Y, 140, BAR_H);

        // Evaluate tab hover
        hoveredTab = -1;
        int tabTotalW = W - 16;
        int tabW = tabTotalW / TAB_NAMES.length;
        for (int ti = 0; ti < TAB_NAMES.length; ti++) {
            int tx = x + 8 + ti * tabW;
            if (inside(mouseX, mouseY, tx, y + HEADER_H, tabW, TAB_BAR_H)) hoveredTab = ti;
        }

        // Fill background
        gfx.fill(x, y, x+W, y+H, C_BG);

        renderHeader(gfx, x, y);
        renderTabBar(gfx, x, y);

        // Render active tab content
        int contentX = x + 8;
        int contentY2 = y + HEADER_H + TAB_BAR_H + 2;
        int contentW = W - 16;
        int contentH = H - HEADER_H - TAB_BAR_H - STATUS_H - 6;

        switch (selectedTab) {
            case 0 -> dashboardTab.render(gfx, this.font, contentX, contentY2, contentW, contentH, mouseX, mouseY);
            case TAB_GENERATE -> {
                renderServicesBar(gfx, x, y + HEADER_H + TAB_BAR_H - BAR_Y + BAR_Y, mouseX, mouseY);
                renderLeftPanel(gfx, x, y, layout);
                renderRightPanel(gfx, x, y);
                if (serviceDropdownOpen) renderDropdown(gfx, x, y, mouseX, mouseY);
            }
            case TAB_SUPER_COMPUTER -> superComputerTab.render(gfx, this.font, contentX, contentY2, contentW, contentH, mouseX, mouseY);
            case TAB_GALLERY  -> galleryTab.render(gfx, this.font, contentX, contentY2, contentW, contentH, mouseX, mouseY);
            case TAB_HISTORY  -> historyTab.render(gfx, this.font, contentX, contentY2, contentW, contentH, mouseX, mouseY);
            case TAB_CREDITS  -> creditsTab.render(gfx, this.font, contentX, contentY2, contentW, contentH, mouseX, mouseY);
            case TAB_SETTINGS -> settingsTab.render(gfx, this.font, contentX, contentY2, contentW, contentH, mouseX, mouseY);
        }

        renderStatusStrip(gfx, x, y);
    }

    private void renderHeader(GuiGraphics gfx, int x, int y) {
        gfx.fill(x, y, x+W, y+HEADER_H, C_HEADER);
        // No cyan line here — the tab bar sits directly below header now

        // Logo
        int ix = x+12, iy = y+10;
        gfx.fill(ix, iy, ix+18, iy+18, C_PANEL);
        gfx.fill(ix+1, iy+1, ix+17, iy+17, C_PANEL2);
        gfx.fill(ix+3, iy+4, ix+9,  iy+9,  C_CYAN);
        gfx.fill(ix+9, iy+4, ix+15, iy+9,  0xFF2AAAD8);

        gfx.drawString(this.font, "XELTA AI TERMINAL", x+36, y+9, C_CYAN, false);
        gfx.drawString(this.font, "Create anything for Minecraft with AI", x+36, y+21, C_SEC, false);

        // Credit balance chip
        int cx = x + W - 140;
        gfx.fill(cx, y+9, cx+10, y+19, C_GREEN);
        String credStr = creditText.isEmpty() ? CreditController.getInstance().getFormattedBalance() : creditText;
        if (credStr.isBlank() || credStr.equals("-1 credits")) credStr = "--- credits";
        gfx.drawString(this.font, credStr, cx+14, y+10, C_PRI, false);

        // Player chip
        int px = x + W - 56;
        gfx.fill(px, y+5, x+W-8, y+35, C_PANEL);
        String pName = (this.minecraft != null && this.minecraft.player != null) ? this.minecraft.player.getName().getString() : "Steve";
        boolean authed = XeltaSessionManager.getInstance().isAuthenticated();
        gfx.drawString(this.font, pName, px+4, y+11, C_PRI, false);
        gfx.drawString(this.font, authed ? "Online" : "Guest", px+10, y+21, authed ? C_GREEN : C_RED, false);
    }

    /** Renders the horizontal tab bar below the header. */
    private void renderTabBar(GuiGraphics gfx, int x, int y) {
        int tabY = y + HEADER_H;
        gfx.fill(x, tabY, x + W, tabY + TAB_BAR_H, 0xFF0D0F1A);
        gfx.fill(x, tabY + TAB_BAR_H - 1, x + W, tabY + TAB_BAR_H, C_CYAN);

        int tabTotalW = W - 16;
        int tabW = tabTotalW / TAB_NAMES.length;

        for (int ti = 0; ti < TAB_NAMES.length; ti++) {
            int tx = x + 8 + ti * tabW;
            boolean active  = (ti == selectedTab);
            boolean hovered = (ti == hoveredTab);

            int bg = active ? C_PANEL : (hovered ? 0xFF12151F : 0xFF0D0F1A);
            gfx.fill(tx, tabY, tx + tabW, tabY + TAB_BAR_H - 1, bg);

            if (active) {
                // Active accent — bottom line in cyan
                gfx.fill(tx + 2, tabY + TAB_BAR_H - 2, tx + tabW - 2, tabY + TAB_BAR_H - 1, C_CYAN);
            }

            int textColor = active ? C_CYAN : (hovered ? C_PRI : C_SEC);
            int tw = this.font.width(TAB_NAMES[ti]);
            gfx.drawString(this.font, TAB_NAMES[ti], tx + (tabW - tw) / 2, tabY + 6, textColor, false);
        }
    }

    private void renderServicesBar(GuiGraphics gfx, int x, int y, int mx, int my) {
        gfx.fill(x, y+BAR_Y, x+W, y+BAR_Y+BAR_H, 0xFF141726);
        gfx.fill(x, y+BAR_Y, x+W, y+BAR_Y+1, C_BDIM);

        // Dropdown button for services
        int sx = x + 8;
        int sy = y + BAR_Y;
        gfx.fill(sx, sy, sx+140, sy+BAR_H, serviceHovered || serviceDropdownOpen ? 0xFF202A3A : 0xFF1A1D2E);
        
        String lbl = "Service: " + SERVICES[selectedService] + (serviceDropdownOpen ? " \u25B2" : " \u25BC");
        gfx.drawString(this.font, lbl, sx + 8, sy + 6, C_CYAN, false);
    }

    private void renderDropdown(GuiGraphics gfx, int x, int y, int mx, int my) {
        int dropX = x + 8;
        int dropY = y + BAR_Y + BAR_H;
        int dropH = SERVICES.length * 16;
        
        gfx.fill(dropX-1, dropY, dropX+141, dropY+dropH+1, C_CYAN);
        gfx.fill(dropX, dropY, dropX+140, dropY+dropH, C_PANEL2);
        
        for (int i = 0; i < SERVICES.length; i++) {
            int itemY = dropY + i * 16;
            boolean isHover = inside(mx, my, dropX, itemY, 140, 16);
            if (isHover) gfx.fill(dropX, itemY, dropX+140, itemY+16, C_PANEL);
            gfx.drawString(this.font, SERVICES[i], dropX + 8, itemY + 4, i == selectedService ? C_CYAN : C_PRI, false);
        }
    }

    private void renderLeftPanel(GuiGraphics gfx, int x, int y, Layout layout) {
        int lx = x + LP_X;
        gfx.fill(lx, y+CONTENT_Y, lx+LP_W, y+PANEL_BOTTOM, C_PANEL);

        // A. Prompt
        gfx.drawString(this.font, "1. Describe what you want to create", lx+4, y+layout.promptY-10, C_PRI, false);
        boolean pFoc = promptField != null && promptField.isFocused();
        gfx.fill(lx+2, y+layout.promptY-1, lx+LP_W-2, y+layout.promptY+layout.promptH+1, pFoc ? C_CYAN : C_BDIM);
        gfx.fill(lx+3, y+layout.promptY, lx+LP_W-3, y+layout.promptY+layout.promptH, C_PANEL2);

        int chars = promptField != null ? promptField.getValue().length() : 0;
        String cntStr = chars + " / 500";
        gfx.drawString(this.font, cntStr, lx + LP_W - this.font.width(cntStr) - 6, y + layout.promptY + layout.promptH - 9, C_SEC, false);

        // B. Reference row — only Image and Build actually consume the uploaded reference;
        // Video/Avatar use the Input slot item instead, and Mix takes no image at all.
        boolean refSupported = referenceSupported(selectedService);
        gfx.drawString(this.font, referenceHeaderLabel(selectedService), lx+4, y+layout.refY-10, C_SEC, false);

        int controlW = LP_W - 8;
        if (refSupported) {
            int ubg = uploadHovered ? 0xFF1A3828 : 0xFF0F1F18;
            gfx.fill(lx+4, y+layout.refY, lx+4+controlW, y+layout.refY+18, ubg);
            String ulbl = switch (referenceUploadState) {
                case NONE -> "Upload Ref";
                case UPLOADING -> "Loading...";
                case READY -> "Ref Ready";
                case FAILED -> "Load Failed";
            };
            int ulblColor = switch (referenceUploadState) {
                case NONE -> C_SEC;
                case UPLOADING -> C_GOLD;
                case READY -> C_GREEN;
                case FAILED -> C_RED;
            };
            gfx.drawString(this.font, ulbl, lx+4+(controlW-this.font.width(ulbl))/2, y+layout.refY+5, ulblColor, false);
        } else {
            // Disabled — dimmer chrome, no hover/click reaction (guarded in mouseClicked()/renderBg()).
            gfx.fill(lx+4, y+layout.refY, lx+4+controlW, y+layout.refY+18, 0xFF0A0C12);
            String ulbl = "Not used for " + SERVICES[selectedService];
            gfx.drawString(this.font, ulbl, lx+4+(controlW-this.font.width(ulbl))/2, y+layout.refY+5, 0xFF3A3F4A, false);
        }

        // C. Slide Size row
        gfx.drawString(this.font, "3. Slide Size", lx+4, y+layout.slideY-10, C_SEC, false);
        int slideAreaW = controlW;
        int slideBtnW = (slideAreaW - 9) / 4;
        int slideStartX = lx + 4;
        for (int i = 0; i < 4; i++) {
            int bx = slideStartX + i*(slideBtnW + 3);
            boolean sel = (i == selectedSlideSize);
            boolean hov = (i == hoveredSlide && !sel);
            gfx.fill(bx, y+layout.slideY, bx+slideBtnW, y+layout.slideY+18, sel ? C_CYAN : (hov ? 0xFF1A2A3A : C_PANEL2));
            int rw = this.font.width(SLIDE_SIZE_OPTIONS[i]);
            gfx.drawString(this.font, SLIDE_SIZE_OPTIONS[i], bx+(slideBtnW-rw)/2, y+layout.slideY+5, sel ? C_PANEL2 : C_SEC, false);
        }

        // D. Token
        gfx.drawString(this.font, "4. Token", lx+4, y+layout.tokY-10, C_SEC, false);
        String lnkTxt = "Dashboard";
        gfx.drawString(this.font, lnkTxt, lx + LP_W - this.font.width(lnkTxt) - 4, y+layout.tokY-10, 0xFF4488CC, false);
        
        boolean tFoc = tokenField != null && tokenField.isFocused();
        gfx.fill(lx+2, y+layout.tokY-1, lx+LP_W-2, y+layout.tokY+19, tFoc ? C_CYAN : C_BDIM);
        gfx.fill(lx+3, y+layout.tokY, lx+LP_W-3, y+layout.tokY+18, C_PANEL2);

        // E. Generate
        gfx.fill(lx+2, y+layout.genY, lx+LP_W/2, y+layout.genY+layout.generateH, genHovered ? 0xFF5030AA : C_PURPLE);
        gfx.fill(lx+LP_W/2, y+layout.genY, lx+LP_W-2, y+layout.genY+layout.generateH, genHovered ? 0xFF7055FF : C_PLIGHT);
        String genLbl = "\u2728 GENERATE";
        gfx.drawString(this.font, genLbl, lx+(LP_W-this.font.width(genLbl))/2, y+layout.genY+(layout.generateH - 8) / 2, 0xFFFFFFFF, false);

        // F. Pro Tip
        int tipY = layout.genY + layout.generateH + 8;
        if (tipY + 18 <= PANEL_BOTTOM) {
            gfx.drawString(this.font, "Pro Tip: Be specific! Add materials,", lx+4, y+tipY, C_GOLD, false);
            gfx.drawString(this.font, "style and lighting.", lx+4, y+tipY+10, C_SEC, false);
        }
    }

    private void renderRightPanel(GuiGraphics gfx, int x, int y) {
        int rx = x + RP_X;
        gfx.fill(rx, y+CONTENT_Y, rx+RP_W, y+PANEL_BOTTOM, C_PANEL);

        // Preview
        int pby = y + CONTENT_Y + 4;
        int ph = (int)((PANEL_BOTTOM - CONTENT_Y) * 0.45); // take 45% of right panel height
        gfx.fill(rx+4, pby, rx+RP_W-4, pby+ph, C_PANEL2);

        ItemStack outputItem = menu.getSlot(XeltaTerminalMenu.SLOT_OUTPUT).getItem();
        String previewUrl = (!outputItem.isEmpty() && outputItem.getItem() instanceof PhotoSlideItem)
            ? PhotoSlideItem.getImageUrl(outputItem) : "";

        if (!previewUrl.isBlank()) {
            // Show only the generated image itself — no item icon, no placeholder chrome.
            ResourceLocation textureId = PREVIEW_TEXTURE_CACHE.get(previewUrl);
            if (textureId != null) {
                int[] size = PREVIEW_TEXTURE_SIZE.getOrDefault(previewUrl, new int[]{1, 1});
                int imgW = size[0], imgH = size[1];
                int maxW = RP_W - 16, maxH = ph - 16;
                float scale = Math.min(maxW / (float) imgW, maxH / (float) imgH);
                int boxW = Math.max(1, Math.round(imgW * scale));
                int boxH = Math.max(1, Math.round(imgH * scale));
                int boxX = rx + (RP_W - boxW) / 2;
                int boxY = pby + (ph - boxH) / 2;
                gfx.blit(textureId, boxX, boxY, boxW, boxH, 0f, 0f, imgW, imgH, imgW, imgH);
            } else {
                requestPreviewTexture(previewUrl);
                String loadingTxt = "Loading preview...";
                gfx.drawString(this.font, loadingTxt, rx + (RP_W - this.font.width(loadingTxt))/2, pby + ph/2 - 4, 0xFF556677, false);
            }
        } else if (!outputItem.isEmpty() && outputItem.getItem() instanceof ai.xelta.craft.item.SchematicItem) {
            renderSchematicGhostPreview(gfx, outputItem, rx + 4, pby, RP_W - 8, ph);
        } else if (!outputItem.isEmpty()) {
            // Non-image output (video, avatar, mix) — no decoder available, fall back to the item icon.
            int iconSize = Math.min(96, Math.min(RP_W - 16, ph - 16));
            int iconX = rx + (RP_W - iconSize) / 2;
            int iconY = pby + (ph - iconSize) / 2;
            ItemRenderer renderer = this.minecraft.getItemRenderer();
            PoseStack poseStack = new PoseStack();
            poseStack.pushPose();
            poseStack.translate(iconX, iconY, 100.0F);
            float scale = iconSize / 16.0F;
            poseStack.scale(scale, scale, 1.0F);
            renderer.renderStatic(outputItem, ItemDisplayContext.GUI, 0, 0, poseStack,
                this.minecraft.renderBuffers().bufferSource(), this.minecraft.level, 0);
            poseStack.popPose();
        } else {
            String npTxt = "No Preview Generated";
            gfx.drawString(this.font, npTxt, rx + (RP_W - this.font.width(npTxt))/2, pby + ph/2 - 4, 0xFF2A3344, false);
        }

        // Actions
        int abY = pby + ph + 6;
        String[] acts = { "Download", "Copy", "Share" };
        int abW = (RP_W - 32) / 4;
        for (int i = 0; i < acts.length; i++) {
            int abX = rx + 4 + i*(abW+4);
            gfx.fill(abX, abY, abX+abW, abY+14, C_PANEL2);
            int aw = this.font.width(acts[i]);
            gfx.drawString(this.font, acts[i], abX+(abW-aw)/2, abY+3, C_SEC, false);
        }
        int hX = rx + 4 + 3*(abW+4);
        gfx.fill(hX, abY, hX+abW, abY+14, C_PANEL2);
        gfx.drawString(this.font, "<3", hX+(abW-this.font.width("<3"))/2, abY+3, C_SEC, false);

        // Description with proper Text Wrapping
        int descY = abY + 20;
        gfx.drawString(this.font, "Description", rx+4, descY, C_PRI, false);
        
        String descText = "A cozy medieval wooden house in Minecraft style, built on a small hill with a stone pathway, lanterns, flowers and trees around. Warm sunset lighting with shaders for a realistic look.";
        List<FormattedCharSequence> wrapped = this.font.split(Component.literal(descText), RP_W - 12);
        int ty = descY + 12;
        for (FormattedCharSequence line : wrapped) {
            if (ty + 9 > y + PANEL_BOTTOM - 4) break;
            gfx.drawString(this.font, line, rx + 4, ty, C_SEC, false);
            ty += 10;
        }

        // Tags
        int tagY = ty + 6;
        String[] tags = { "Minecraft", "Medieval", "Wood", "Cozy" };
        int[] tagColors = { C_GREEN, C_CYAN, C_GOLD, C_PURPLE };
        int cx = rx + 4;
        if (tagY + 12 <= y + PANEL_BOTTOM - 4) {
            for(int i=0; i<tags.length; i++) {
                int tw = this.font.width(tags[i]) + 8;
                if(cx + tw > rx + RP_W) break; // prevent tag overflow
                gfx.fill(cx, tagY, cx+tw, tagY+12, C_TAG_BG);
                gfx.drawString(this.font, tags[i], cx+4, tagY+2, tagColors[i], false);
                cx += tw + 4;
            }
        }

        // Inventory
        int invY = tagY + 20;
        if (invY + 34 <= y + PANEL_BOTTOM - 4) {
            gfx.drawString(this.font, "Your Inventory", rx+4, invY, C_PRI, false);
            gfx.drawString(this.font, "View All ->", rx+RP_W-54, invY, C_SEC, false);
            
            int slotY = invY + 12;
            int slotSize = Math.min(22, (RP_W - 16) / 5 - 4);
            for(int i=0; i<5; i++) {
                int sx = rx + 4 + i*(slotSize+4);
                gfx.fill(sx, slotY, sx+slotSize, slotY+slotSize, C_PANEL2);
            }
        }
    }

    /**
     * Renders a slowly-rotating, translucent preview of a {@link ai.xelta.craft.item.SchematicItem}'s
     * blocks inside the Preview panel — the same ghost-block look as
     * {@link ai.xelta.craft.client.render.SchematicGhostRenderer}'s in-world preview, just drawn
     * with the panel's own PoseStack instead of the level's, following the same manual-PoseStack
     * pattern the item-icon fallback below already uses for non-image outputs.
     */
    private void renderSchematicGhostPreview(GuiGraphics gfx, ItemStack stack, int panelX, int panelY, int panelW, int panelH) {
        java.util.List<ai.xelta.craft.schematic.LitematicaSchematicReader.Placement> placements =
            ai.xelta.craft.item.SchematicItem.getPlacements(stack);
        if (placements.isEmpty()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var p : placements) {
            minX = Math.min(minX, p.pos().getX()); maxX = Math.max(maxX, p.pos().getX());
            minY = Math.min(minY, p.pos().getY()); maxY = Math.max(maxY, p.pos().getY());
            minZ = Math.min(minZ, p.pos().getZ()); maxZ = Math.max(maxZ, p.pos().getZ());
        }
        float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f, cz = (minZ + maxZ) / 2f;
        float dim = Math.max(1, Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) + 1);

        int centerX = panelX + panelW / 2, centerY = panelY + panelH / 2;
        float scale = Math.min(panelW, panelH) / (dim * 1.8f); // 1.8x fudge so the rotated box clears the panel edges
        float yaw = (System.currentTimeMillis() / 30L) % 360; // slow auto-rotate so every side is visible over time

        PoseStack poseStack = gfx.pose();
        gfx.enableScissor(panelX, panelY, panelX + panelW, panelY + panelH);
        poseStack.pushPose();
        poseStack.translate(centerX, centerY, 150.0F);
        poseStack.scale(scale, -scale, scale);
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-25f));
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));

        var bufferSource = gfx.bufferSource();
        var blockRenderer = this.minecraft.getBlockRenderer();
        int cap = Math.min(placements.size(), 4000); // perf guard — this redraws from scratch every frame

        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 0.6f);
        for (int i = 0; i < cap; i++) {
            var p = placements.get(i);
            poseStack.pushPose();
            poseStack.translate(p.pos().getX() - cx, p.pos().getY() - cy, p.pos().getZ() - cz);
            blockRenderer.renderSingleBlock(p.state(), poseStack, bufferSource, 0xF000F0,
                net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY,
                net.minecraft.client.renderer.RenderType.translucent());
            poseStack.popPose();
        }
        gfx.flush();
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        poseStack.popPose();
        gfx.disableScissor();

        if (placements.size() > cap) {
            String note = "(showing " + cap + "/" + placements.size() + " blocks)";
            gfx.drawString(this.font, note, panelX + (panelW - this.font.width(note)) / 2, panelY + panelH - 10, 0xFF556677, false);
        }
    }

    /**
     * True for the only two modes whose server-side generation call actually consumes the
     * uploaded reference image (Photo Lab embeds it directly; World Build sends it to Gemini
     * as visual context). Video/Avatar source their image from the Input slot item instead,
     * and Mix takes no image input at all — uploading a reference on those modes would silently
     * do nothing, so the control is disabled rather than implying it works everywhere.
     */
    private static boolean referenceSupported(int service) {
        return service == 0 /* Image */ || service == 4 /* Build */;
    }

    private static String referenceHeaderLabel(int service) {
        return switch (service) {
            case 0 -> "2. Reference (kept, new bg)";
            case 4 -> "2. Reference (image or text file)";
            case 1, 2 -> "2. Reference (use Input slot)";
            default -> "2. Reference (not used)";
        };
    }

    private void renderStatusStrip(GuiGraphics gfx, int x, int y) {
        int sy = y + PANEL_BOTTOM + 2;
        gfx.fill(x, sy, x+W, sy+STATUS_H, C_PANEL);
        String message = statusText == null || statusText.isBlank()
            ? "Server: Online  |  Model: Xelta Build v2.0  |  Avg: ~25s"
            : statusText;
        gfx.drawString(this.font, message, x+8, sy+6, statusIsErr ? C_RED : C_SEC, false);
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {}

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int mx = (int) mouseX, my = (int) mouseY;

            // ── Tab bar clicks ─────────────────────────────────────────────
            int tabY = this.topPos + HEADER_H;
            int tabTotalW = W - 16;
            int tabW = tabTotalW / TAB_NAMES.length;
            for (int ti = 0; ti < TAB_NAMES.length; ti++) {
                int tx = this.leftPos + 8 + ti * tabW;
                if (inside(mx, my, tx, tabY, tabW, TAB_BAR_H)) {
                    switchTab(ti);
                    return true;
                }
            }

            // ── Generate tab (selectedTab == TAB_GENERATE) only ────────────
            if (selectedTab == TAB_GENERATE) {
                if (serviceDropdownOpen) {
                    int dropX = this.leftPos + 8;
                    int dropY = this.topPos + BAR_Y + HEADER_H + TAB_BAR_H - BAR_Y + BAR_Y;
                    int dropH = SERVICES.length * 16;
                    if (inside(mx, my, dropX, dropY, 140, dropH)) {
                        selectedService = (my - dropY) / 16;
                    }
                    serviceDropdownOpen = false;
                    return true;
                }

                if (inside(mx, my, this.leftPos + 8, this.topPos + BAR_Y, 140, BAR_H)) {
                    serviceDropdownOpen = !serviceDropdownOpen;
                    return true;
                }
                if (hoveredSlide != -1) { selectedSlideSize = hoveredSlide; return true; }
                if (uploadHovered) { onUploadReferenceClicked(); return true; }
                Layout layout = createLayout();
                if (inside(mx, my, this.leftPos + LP_X + 2, this.topPos + layout.genY, LP_W - 4, layout.generateH)) {
                    onGenerateClicked(); return true;
                }
            }

            // ── Other tab clicks ───────────────────────────────────────────
            int contentX = this.leftPos + 8;
            int contentY2 = this.topPos + HEADER_H + TAB_BAR_H + 2;
            int contentW = W - 16;
            int contentH = H - HEADER_H - TAB_BAR_H - STATUS_H - 6;
            switch (selectedTab) {
                case 0 -> { int tool = dashboardTab.mouseClicked(mx, my, contentX, contentY2, contentW);
                    if (tool >= 0) { switchTab(TAB_GENERATE); selectedService = tool; } }
                case TAB_SUPER_COMPUTER -> superComputerTab.mouseClicked(mx, my, contentX, contentY2, contentW, contentH);
                case TAB_GALLERY  -> galleryTab.mouseClicked(mx, my, contentX, contentY2, contentW, contentH);
                case TAB_HISTORY  -> historyTab.mouseClicked(mx, my, contentX, contentY2, contentW, contentH, this.font);
                case TAB_CREDITS  -> creditsTab.mouseClicked(mx, my, contentX, contentY2, contentW);
                case TAB_SETTINGS -> settingsTab.mouseClicked(mx, my, contentX, contentY2, contentW);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ── Tab Widget Lifecycle ──────────────────────────────────────────────────

    /**
     * Clears ALL registered widgets then adds only the ones needed for {@code tab}.
     * Must be called every time the active tab changes (and on first init).
     * This is the single source of truth for which widgets are live at any time.
     */
    private void rebuildWidgets(int tab) {
        clearWidgets(); // remove every previously registered widget

        switch (tab) {
            case TAB_GENERATE -> {
                // ── Prompt text box ──────────────────────────────────────────
                Layout layout = createLayout();
                promptField = new EditBox(
                    this.font,
                    this.leftPos + LP_X + 4,
                    this.topPos  + layout.promptY + 4,
                    LP_W - 8,
                    layout.promptH - 8,
                    Component.literal("prompt")
                );
                promptField.setMaxLength(500);
                promptField.setBordered(false);
                promptField.setTextColor(0xFFDDEEFF);
                promptField.setHint(Component.literal("A cozy medieval wooden house...")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                this.addRenderableWidget(promptField);

                // ── Token field (below prompt, in left panel) ────────────────
                tokenField = new EditBox(
                    this.font,
                    this.leftPos + LP_X + 4,
                    this.topPos  + layout.tokY + 3,
                    LP_W - 8,
                    14,
                    Component.literal("token")
                );
                tokenField.setMaxLength(2000);
                tokenField.setBordered(false);
                tokenField.setTextColor(0xFF9AAABB);
                tokenField.setValue(ai.xelta.craft.config.XeltaCraftConfig.API_KEY.get());
                tokenField.setResponder(val -> {
                    ai.xelta.craft.config.XeltaCraftConfig.API_KEY.set(val);
                    ai.xelta.craft.config.XeltaCraftConfig.SPEC.save();
                });
                this.addRenderableWidget(tokenField);
            }
            case TAB_SUPER_COMPUTER -> {
                // ── Super Computer: prompt box ────────────────────────────────
                int contentX = this.leftPos + 8;
                int contentY = this.topPos + HEADER_H + TAB_BAR_H + 2;
                int contentW = W - 16;
                int contentH = H - HEADER_H - TAB_BAR_H - STATUS_H - 6;
                net.minecraft.client.gui.components.EditBox pb =
                    superComputerTab.buildPromptBox(this.font, contentX, contentY, contentW, contentH);
                this.addRenderableWidget(pb);
            }
            case TAB_SETTINGS -> {
                // ── Settings: token paste box ────────────────────────────────
                int contentX = this.leftPos + 8;
                int contentY = this.topPos + HEADER_H + TAB_BAR_H + 2;
                int contentW = W - 16;
                net.minecraft.client.gui.components.EditBox tb =
                    settingsTab.buildTokenBox(this.font, contentX, contentY, contentW);
                this.addRenderableWidget(tb);
            }
            // Dashboard (0), Gallery, History, Credits have no
            // Vanilla widget children — they draw everything in renderBg().
        }
    }

    private void switchTab(int tab) {
        selectedTab = tab;
        serviceDropdownOpen = false;
        rebuildWidgets(tab); // always rebuild — no stale widgets from old tab
        // Tab-specific data lifecycle hooks
        switch (tab) {
            case TAB_SUPER_COMPUTER -> superComputerTab.onShow();
            case TAB_GALLERY -> galleryTab.refresh();
            case TAB_HISTORY -> historyTab.onShow();
            case TAB_CREDITS -> creditsTab.onShow();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Settings tab — forward to Settings token box first
        if (selectedTab == TAB_SETTINGS && settingsTab.keyPressed(keyCode, scanCode, modifiers)) return true;

        // Super Computer tab — forward to its prompt box (handles Enter-to-send itself)
        if (selectedTab == TAB_SUPER_COMPUTER && superComputerTab.keyPressed(keyCode, scanCode, modifiers)) return true;

        // Generate tab — forward to prompt/token fields (only non-null on TAB_GENERATE)
        boolean promptFocused = promptField != null && promptField.isFocused();
        boolean tokenFocused  = tokenField  != null && tokenField.isFocused();
        if (promptFocused || tokenFocused) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(keyCode, scanCode, modifiers);
            if ((promptField != null && promptField.keyPressed(keyCode, scanCode, modifiers))
                    || (tokenField != null && tokenField.keyPressed(keyCode, scanCode, modifiers))) return true;
            if (this.minecraft != null && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (selectedTab == TAB_SETTINGS && settingsTab.charTyped(c, modifiers)) return true;
        if (selectedTab == TAB_SUPER_COMPUTER && superComputerTab.charTyped(c, modifiers)) return true;
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (selectedTab == TAB_SUPER_COMPUTER
                && superComputerTab.mouseScrolled((int) mouseX, (int) mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // EditBox cursor animation is handled automatically by the widget system.
        // No manual .tick() calls needed in NeoForge 1.21.1.
        if (selectedTab == TAB_SETTINGS) settingsTab.tick();
    }

    private void onUploadReferenceClicked() {
        // Build mode's Gemini call can use a text file as extra prompt context, not just an
        // image — Image-editing mode still needs a real image, so it keeps the narrower filter.
        boolean allowTextFiles = selectedService == 4;
        String[] exts = allowTextFiles
            ? new String[]{"*.png", "*.jpg", "*.jpeg", "*.webp", "*.txt", "*.json", "*.md"}
            : new String[]{"*.png", "*.jpg", "*.jpeg", "*.webp"};
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer filters = stack.mallocPointer(exts.length);
            for (String ext : exts) filters.put(stack.UTF8(ext));
            filters.flip();
            String title = allowTextFiles ? "Select Reference File" : "Select Reference Image";
            String desc = allowTextFiles
                ? "Image or Text Files (*.png, *.jpg, *.jpeg, *.webp, *.txt, *.json, *.md)"
                : "Image Files (*.png, *.jpg, *.jpeg, *.webp)";
            String path = TinyFileDialogs.tinyfd_openFileDialog(title, "", filters, desc, false);
            if (path != null && !path.isBlank()) { startReferenceUpload(path); }
        }
    }

    /**
     * Reads the picked file's bytes directly — no network call here at all. They travel to
     * the server inside {@link C2SGeneratePacket} and get embedded straight into the same
     * generate/Gemini request that already works, instead of going through a separate Xelta
     * upload endpoint (which turned out to be blocked by Cloudflare's WAF regardless of path).
     */
    private void startReferenceUpload(String localPath) {
        referenceImagePath = localPath;
        referenceImageBytes = null;
        referenceUploadState = ReferenceUploadState.UPLOADING;
        statusText = "Loading reference image...";
        statusIsErr = false;

        java.util.concurrent.CompletableFuture
            .supplyAsync(() -> {
                try {
                    return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(localPath));
                } catch (java.io.IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            })
            .thenAccept(bytes -> Minecraft.getInstance().execute(() -> {
                if (bytes.length > C2SGeneratePacket.MAX_REFERENCE_IMAGE_BYTES) {
                    referenceUploadState = ReferenceUploadState.FAILED;
                    statusText = "Reference image too large (" + (bytes.length / 1024 / 1024)
                        + " MB) — must be under " + (C2SGeneratePacket.MAX_REFERENCE_IMAGE_BYTES / 1024 / 1024) + " MB.";
                    statusIsErr = true;
                    return;
                }
                referenceImageBytes = bytes;
                referenceUploadState = ReferenceUploadState.READY;
                statusText = "Reference image ready.";
                statusIsErr = false;
            }))
            .exceptionally(ex -> {
                Minecraft.getInstance().execute(() -> {
                    referenceUploadState = ReferenceUploadState.FAILED;
                    statusText = "Could not read reference image: " + ex.getMessage();
                    statusIsErr = true;
                });
                return null;
            });
    }

    private void onGenerateClicked() {
        if (promptField.getValue().trim().isEmpty()) {
            statusText = "Add a prompt before generating.";
            statusIsErr = true;
            return;
        }
        if (referenceUploadState == ReferenceUploadState.UPLOADING) {
            statusText = "Reference image still uploading — please wait.";
            statusIsErr = true;
            return;
        }

        byte[] refToSend = referenceUploadState == ReferenceUploadState.READY ? referenceImageBytes : null;
        PacketDistributor.sendToServer(new C2SGeneratePacket(
            this.menu.getBlockPos(), promptField.getValue().trim(), UUID.randomUUID(), selectedService, SLIDE_SIZE_OPTIONS[selectedSlideSize], refToSend, "xelta-mix-v1"
        ));
        statusText = "Generation request sent.";
        statusIsErr = false;
    }

    /** Kicks off an async download + texture upload for a Preview-panel image, once per URL. */
    private static void requestPreviewTexture(String url) {
        if (PREVIEW_DOWNLOADING.putIfAbsent(url, true) != null) return;

        AssetDownloader.downloadAsset(url, AssetCacheManager.AssetType.IMAGE)
            .thenAccept(file -> Minecraft.getInstance().execute(() -> {
                try (FileInputStream in = new FileInputStream(file)) {
                    NativeImage image = NativeImage.read(in);
                    DynamicTexture texture = new DynamicTexture(image);
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                        "xeltacraft", "terminal_preview_" + Integer.toHexString(url.hashCode()));
                    Minecraft.getInstance().getTextureManager().register(id, texture);
                    PREVIEW_TEXTURE_SIZE.put(url, new int[]{image.getWidth(), image.getHeight()});
                    PREVIEW_TEXTURE_CACHE.put(url, id);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    PREVIEW_DOWNLOADING.remove(url);
                }
            }))
            .exceptionally(e -> {
                PREVIEW_DOWNLOADING.remove(url);
                return null;
            });
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x+w && my >= y && my < y+h;
    }

    private Layout createLayout() {
        int available = PANEL_BOTTOM - CONTENT_Y;
        boolean compact = available < 220;
        boolean veryCompact = available < 180;

        int promptY = CONTENT_Y + (compact ? 10 : 16);
        int promptH = veryCompact ? 28 : (compact ? 32 : 40);
        int refY = promptY + promptH + (compact ? 10 : 16);
        int slideY = refY + (compact ? 24 : ROW_GAP);
        int tokY = slideY + (compact ? 28 : TOKEN_GAP);
        int genY = tokY + (compact ? 24 : 30);
        int generateH = compact ? 24 : GENERATE_H;

        int overflow = genY + generateH + 4 - PANEL_BOTTOM;
        if (overflow > 0) {
            genY -= overflow;
            tokY = Math.min(tokY, genY - 24);
            slideY = Math.min(slideY, tokY - 28);
            refY = Math.min(refY, slideY - 24);
            promptH = Math.max(22, Math.min(promptH, refY - promptY - 10));
        }

        return new Layout(promptY, promptH, refY, slideY, tokY, genY, generateH);
    }

    private record Layout(int promptY, int promptH, int refY, int slideY, int tokY, int genY, int generateH) {}
}
