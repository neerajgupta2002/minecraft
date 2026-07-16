package ai.xelta.craft.client.screen;

import ai.xelta.craft.blockentity.XeltaTerminalBlockEntity.GenerationMode;
import ai.xelta.craft.menu.XeltaTerminalMenu;
import ai.xelta.craft.network.C2SGeneratePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.util.List;
import java.util.UUID;

/**
 * XeltaTerminalScreen — Responsive 80% scale UI with Services dropdown.
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
    private String  referenceImagePath = "";
    
    private EditBox promptField;
    private EditBox tokenField;
    private String  statusText   = "Ready";
    private boolean statusIsErr  = false;
    private String  creditText   = "";

    public XeltaTerminalScreen(XeltaTerminalMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        super.init();

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

        // Widgets
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
        promptField.setHint(Component.literal("A cozy medieval wooden house...").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        this.addRenderableWidget(promptField);

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
        uploadHovered = inside(mouseX, mouseY, x + LP_X + 4, y + layout.refY, uW, 18);
        
        genHovered = inside(mouseX, mouseY, x + LP_X + 2, y + layout.genY, LP_W - 4, layout.generateH);

        serviceHovered = inside(mouseX, mouseY, x + 8, y + BAR_Y, 140, BAR_H);

        // Fill background
        gfx.fill(x, y, x+W, y+H, C_BG);

        renderHeader(gfx, x, y);
        renderServicesBar(gfx, x, y, mouseX, mouseY);
        renderLeftPanel(gfx, x, y, layout);
        renderRightPanel(gfx, x, y);
        renderStatusStrip(gfx, x, y);
        
        // Draw dropdown overlay LAST so it renders above everything
        if (serviceDropdownOpen) {
            renderDropdown(gfx, x, y, mouseX, mouseY);
        }
    }

    private void renderHeader(GuiGraphics gfx, int x, int y) {
        gfx.fill(x, y, x+W, y+HEADER_H, C_HEADER);
        gfx.fill(x, y+HEADER_H-1, x+W, y+HEADER_H, C_CYAN);

        // Logo
        int ix = x+12, iy = y+10;
        gfx.fill(ix, iy, ix+18, iy+18, C_PANEL);
        gfx.fill(ix+1, iy+1, ix+17, iy+17, C_PANEL2);
        gfx.fill(ix+3, iy+4, ix+9,  iy+9,  C_CYAN);
        gfx.fill(ix+9, iy+4, ix+15, iy+9,  0xFF2AAAD8);
        
        gfx.drawString(this.font, "XELTA AI TERMINAL", x+36, y+9, C_CYAN, false);
        gfx.drawString(this.font, "Create anything for Minecraft with AI", x+36, y+21, C_SEC, false);

        int cx = x + W - 140;
        gfx.fill(cx, y+9, cx+10, y+19, C_GREEN);
        String credStr = creditText.isEmpty() ? "1,250 Credits" : creditText;
        gfx.drawString(this.font, credStr, cx+14, y+10, C_PRI, false);

        int px = x + W - 56;
        gfx.fill(px, y+5, x+W-8, y+35, C_PANEL);
        String pName = (this.minecraft != null && this.minecraft.player != null) ? this.minecraft.player.getName().getString() : "Steve";
        gfx.drawString(this.font, pName, px+4, y+11, C_PRI, false);
        gfx.drawString(this.font, "Online", px+10, y+21, C_GREEN, false);
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

        // B. Reference row
        gfx.drawString(this.font, "2. Reference", lx+4, y+layout.refY-10, C_SEC, false);
        
        int ubg = uploadHovered ? 0xFF1A3828 : 0xFF0F1F18;
        int controlW = LP_W - 8;
        gfx.fill(lx+4, y+layout.refY, lx+4+controlW, y+layout.refY+18, ubg);
        String ulbl = referenceImagePath.isEmpty() ? "Upload Ref" : "Ref Loaded";
        gfx.drawString(this.font, ulbl, lx+4+(controlW-this.font.width(ulbl))/2, y+layout.refY+5, referenceImagePath.isEmpty() ? C_SEC : C_GREEN, false);

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
        String npTxt = "No Preview Generated";
        gfx.drawString(this.font, npTxt, rx + (RP_W - this.font.width(npTxt))/2, pby + ph/2 - 4, 0xFF2A3344, false);

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
            
            if (serviceDropdownOpen) {
                int dropX = this.leftPos + 8;
                int dropY = this.topPos + BAR_Y + BAR_H;
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

            if (hoveredSlide != -1) {
                selectedSlideSize = hoveredSlide;
                return true;
            }
            
            if (uploadHovered) {
                onUploadReferenceClicked();
                return true;
            }
            Layout layout = createLayout();
            if (inside(mx, my, this.leftPos + LP_X + 2, this.topPos + layout.genY, LP_W - 4, layout.generateH)) {
                onGenerateClicked();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.promptField.isFocused() || this.tokenField.isFocused()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(keyCode, scanCode, modifiers);
            if (this.promptField.keyPressed(keyCode, scanCode, modifiers) || this.tokenField.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (this.minecraft != null && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void onUploadReferenceClicked() {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer filters = stack.mallocPointer(4);
            filters.put(stack.UTF8("*.png")); filters.put(stack.UTF8("*.jpg")); filters.put(stack.UTF8("*.jpeg")); filters.put(stack.UTF8("*.webp")); filters.flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog("Select Reference Image", "", filters, "Image Files (*.png, *.jpg, *.jpeg, *.webp)", false);
            if (path != null && !path.isBlank()) { referenceImagePath = path; }
        }
    }

    private void onGenerateClicked() {
        if (promptField.getValue().trim().isEmpty()) {
            statusText = "Add a prompt before generating.";
            statusIsErr = true;
            return;
        }
        
        PacketDistributor.sendToServer(new C2SGeneratePacket(
            this.menu.getBlockPos(), promptField.getValue().trim(), UUID.randomUUID(), selectedService, SLIDE_SIZE_OPTIONS[selectedSlideSize], referenceImagePath, "xelta-mix-v1"
        ));
        statusText = "Generation request sent.";
        statusIsErr = false;
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
