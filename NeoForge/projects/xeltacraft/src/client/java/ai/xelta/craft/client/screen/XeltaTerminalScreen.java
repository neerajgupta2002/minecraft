package ai.xelta.craft.client.screen;

import ai.xelta.craft.blockentity.XeltaTerminalBlockEntity.GenerationStatus;
import ai.xelta.craft.config.XeltaCraftConfig;
import ai.xelta.craft.menu.XeltaTerminalMenu;
import ai.xelta.craft.network.C2SGeneratePacket;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * XeltaTerminalScreen — the in-game GUI for the Xelta Terminal block.
 *
 * Layout (176 × 166 default GUI):
 *   ┌───────────────────────────────────────────────────┐
 *   │ ⚡ Xelta Terminal         💳 250 credits          │
 *   │                                                   │
 *   │  [Input Slot]    ──────────   [Output Slot]       │
 *   │                                                   │
 *   │  Prompt: [_________________________________]       │
 *   │                                                   │
 *   │              [    Generate    ]                   │
 *   │                                                   │
 *   │  Status: ✅ Image ready! Collect your Photo Slide │
 *   ├───────────────────────────────────────────────────┤
 *   │  Player Inventory (3 rows + hotbar)               │
 *   └───────────────────────────────────────────────────┘
 */
public class XeltaTerminalScreen extends AbstractContainerScreen<XeltaTerminalMenu> {

    private static final ResourceLocation TEXTURE =
        ResourceLocation.fromNamespaceAndPath("xeltacraft", "textures/gui/xelta_terminal.png");

    // GUI dimensions (the background texture size)
    private static final int GUI_WIDTH  = 176;
    private static final int GUI_HEIGHT = 200;

    // Widget references
    private EditBox   promptBox;
    private Button    generateButton;

    // Dynamic state (updated by S2CStatusPacket)
    private GenerationStatus currentStatus  = GenerationStatus.IDLE;
    private String           statusMessage  = "";
    private int              creditBalance  = -1;
    private boolean          isGenerating   = false;

    // Spinner animation
    private int spinnerTick = 0;
    private static final String[] SPINNER = {"⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏"};

    public XeltaTerminalScreen(XeltaTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth  = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    public BlockPos getBlockPos() {
        return menu.getBlockPos();
    }

    // =========================================================
    // Init — build widgets
    // =========================================================

    @Override
    protected void init() {
        super.init();

        int x = this.leftPos;
        int y = this.topPos;

        // Prompt input box
        promptBox = new EditBox(this.font,
            x + 8, y + 70, GUI_WIDTH - 16, 16,
            Component.translatable("gui.xeltacraft.prompt_hint"));
        promptBox.setMaxLength(255);
        promptBox.setHint(Component.literal("Describe your image...").withStyle(ChatFormatting.DARK_GRAY));
        promptBox.setBordered(true);
        addWidget(promptBox);
        setInitialFocus(promptBox);

        // Generate button
        generateButton = Button.builder(
            Component.translatable("gui.xeltacraft.generate"),
            btn -> onGenerateClick()
        ).bounds(x + GUI_WIDTH / 2 - 40, y + 92, 80, 20).build();
        addRenderableWidget(generateButton);

        updateButtonState();
    }

    // =========================================================
    // Tick — update button state each frame
    // =========================================================

    @Override
    protected void containerTick() {
        super.containerTick();
        // EditBox handles its own cursor blink via render() in MC 1.21
        if (isGenerating) spinnerTick = (spinnerTick + 1) % (SPINNER.length * 3);
        updateButtonState();
    }

    private void updateButtonState() {
        if (generateButton == null) return;

        boolean apiKeySet  = !XeltaCraftConfig.API_KEY.get().isEmpty();
        boolean hasPrompt  = promptBox != null && !promptBox.getValue().isBlank();
        boolean notBusy    = !isGenerating;

        generateButton.active = apiKeySet && hasPrompt && notBusy;

        if (!apiKeySet) {
            generateButton.setTooltip(Tooltip.create(
                Component.literal("No API key set. Run: /xeltacraft setkey <key>")));
        } else if (!hasPrompt) {
            generateButton.setTooltip(Tooltip.create(
                Component.literal("Enter a prompt first.")));
        } else if (isGenerating) {
            generateButton.setTooltip(Tooltip.create(
                Component.literal("Generation in progress...")));
        } else {
            generateButton.setTooltip(null);
        }
    }

    // =========================================================
    // Generate button click
    // =========================================================

    private void onGenerateClick() {
        if (promptBox == null || promptBox.getValue().isBlank()) return;

        isGenerating = true;
        currentStatus = GenerationStatus.GENERATING;
        statusMessage = "Sending to Xelta AI...";
        generateButton.active = false;

        // Send packet to server with unique request ID (prevents double-send)
        PacketDistributor.sendToServer(new C2SGeneratePacket(
            menu.getBlockPos(),
            promptBox.getValue().trim(),
            UUID.randomUUID()
        ));
    }

    // =========================================================
    // Status Update (called by S2CStatusPacket handler)
    // =========================================================

    public void onStatusUpdate(GenerationStatus status, String message, int balance) {
        this.currentStatus  = status;
        this.statusMessage  = message;
        this.creditBalance  = balance;
        this.isGenerating   = status == GenerationStatus.GENERATING;
        updateButtonState();
    }

    // =========================================================
    // Render
    // =========================================================

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Draw GUI background texture
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);

        // Draw prompt box background
        if (promptBox != null) promptBox.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Title
        graphics.drawString(font,
            Component.literal("⚡ Xelta Terminal").withStyle(ChatFormatting.AQUA),
            6, 6, 0xFFFFFF, false);

        // Credit balance
        if (creditBalance >= 0 && XeltaCraftConfig.SHOW_CREDIT_BALANCE.get()) {
            String balanceStr = "💳 " + creditBalance + " credits";
            int balanceColor  = creditBalance < 50 ? 0xFF5555 : 0x55FF55;
            graphics.drawString(font, balanceStr,
                GUI_WIDTH - font.width(balanceStr) - 6, 6, balanceColor, false);
        }

        // Slot labels
        graphics.drawString(font, "Input",  35, 25, 0xAAAAAA, false);
        graphics.drawString(font, "Output", 107, 25, 0xAAAAAA, false);

        // Prompt label
        graphics.drawString(font, "Prompt:", 8, 60, 0xAAAAAA, false);

        // Status line
        renderStatus(graphics);

        // Player inventory label
        graphics.drawString(font, this.playerInventoryTitle, 8, this.inventoryLabelY, 0x404040, false);
    }

    private void renderStatus(GuiGraphics graphics) {
        if (statusMessage.isBlank() && currentStatus == GenerationStatus.IDLE) return;

        String display;
        int    color;

        switch (currentStatus) {
            case GENERATING -> {
                // Animated spinner
                String spinner = SPINNER[(spinnerTick / 3) % SPINNER.length];
                display = spinner + " " + statusMessage;
                color = 0xFFFF55; // Yellow
            }
            case DONE -> {
                display = "✅ " + statusMessage;
                color = 0x55FF55; // Green
            }
            case ERROR -> {
                display = "❌ " + statusMessage;
                color = 0xFF5555; // Red
            }
            default -> {
                display = statusMessage;
                color = 0xAAAAAA; // Grey
            }
        }

        // Word wrap if the message is too long
        int maxWidth = GUI_WIDTH - 16;
        if (font.width(display) > maxWidth) {
            display = font.plainSubstrByWidth(display, maxWidth - font.width("…")) + "…";
        }

        graphics.drawString(font, display, 8, 116, color, false);
    }

    // =========================================================
    // Key / Mouse handling
    // =========================================================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter key in prompt box → click Generate
        if (keyCode == 257 /* GLFW_KEY_ENTER */ && promptBox != null && promptBox.isFocused()) {
            if (generateButton != null && generateButton.active) {
                onGenerateClick();
                return true;
            }
        }
        if (promptBox != null && promptBox.isFocused()) {
            return promptBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (promptBox != null && promptBox.isFocused()) {
            return promptBox.charTyped(c, modifiers);
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (promptBox != null) {
            promptBox.mouseClicked(mouseX, mouseY, button);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
