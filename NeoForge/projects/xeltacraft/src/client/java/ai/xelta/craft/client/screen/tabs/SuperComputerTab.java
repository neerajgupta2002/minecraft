package ai.xelta.craft.client.screen.tabs;

import ai.xelta.craft.XeltaCraftMod;
import ai.xelta.craft.sdk.AssetCacheManager;
import ai.xelta.craft.sdk.AssetDownloader;
import ai.xelta.craft.sdk.superxelta.XeltaSessionClient;
import ai.xelta.craft.sdk.superxelta.XeltaSuperComputerClient;
import ai.xelta.craft.workflow.SuperComputerWorkflow;
import ai.xelta.craft.workflow.SuperComputerWorkflow.ApprovalRequest;
import ai.xelta.craft.workflow.SuperComputerWorkflow.SuperComputerResult;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SuperComputerTab — UI Layer
 *
 * Chat-style front end for the Nexus Deep Agent backend ("Super Computer"), as documented in
 * API.md. This is the counterpart to the existing Generate tab's Gemini-powered "Build" mode:
 * where Build sends a prompt straight to Gemini for an architectural blueprint, this tab talks
 * to the Xelta orchestrator backend for image/website/home-design/face-swap/etc. generation,
 * with the server able to pause a turn mid-flight to ask for spend approval.
 *
 * Layout: skill picker + credit chip + new-session button, a scrolling chat log, an inline
 * approval bar when the backend is waiting on a decision, and a prompt box + upload + send row.
 */
public class SuperComputerTab {

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

    // ── Skills (API.md § Skill ids) ──────────────────────────────────────────
    private static final String[] SKILL_IDS = {
        null, "bg-removal-generate", "home-design-generate", "face-swap-generate",
        "photolab-enhance", "virtual-tryon", "food-content-generate",
        "website-builder-generate", "street-ad-generate", "giant-ads-generate"
    };
    private static final String[] SKILL_LABELS = {
        "Auto (Orchestrator)", "BG Removal", "Home Design", "Face Swap",
        "Photo Enhance", "Virtual Try-On", "Food Content",
        "Website Builder", "Street Ad", "Giant Ads"
    };

    // ── Chat log ──────────────────────────────────────────────────────────────
    private static final class LogEntry {
        final String role; // "user" | "assistant" | "error" | "system"
        StringBuilder text = new StringBuilder();
        String imageUrl = "";
        LogEntry(String role, String text) { this.role = role; this.text.append(text); }
    }
    private final List<LogEntry> log = new ArrayList<>();
    private int scrollOffset = 0; // pixels scrolled up from the bottom

    // ── Pending approval ──────────────────────────────────────────────────────
    private record PendingApproval(ApprovalRequest request, CompletableFuture<Boolean> decision) {}
    private volatile PendingApproval pendingApproval = null;

    // ── State ─────────────────────────────────────────────────────────────────
    private int selectedSkill = 0;
    private boolean skillDropdownOpen = false;
    private boolean isGenerating = false;
    private int creditBalance = -1;

    private String  referenceImagePath = "";
    private enum RefState { NONE, READY, FAILED }
    private RefState refState = RefState.NONE;

    private EditBox promptBox;

    // ── Cached geometry for click handling (computed each render) ───────────
    private int skillBtnX, skillBtnY, skillBtnW, skillBtnH = 18;
    private int newSessionBtnX, newSessionBtnY, newSessionBtnW;
    private int approveBtnX, approveBtnY, rejectBtnX, rejectBtnY, approvalBtnW, approvalBtnH;
    private int uploadBtnX, uploadBtnY, uploadBtnW, sendBtnX, sendBtnY, sendBtnW, inputBtnH;
    private int logTop, logBottom, logLeft, logRight;

    private boolean skillHovered, newSessionHovered, approveHovered, rejectHovered, uploadHovered, sendHovered;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void onShow() {
        if (creditBalance < 0) refreshCredits();
    }

    private void refreshCredits() {
        XeltaSuperComputerClient.getInstance().fetchCredits()
            .thenAccept(bal -> Minecraft.getInstance().execute(() -> creditBalance = bal));
    }

    public EditBox buildPromptBox(Font font, int x, int y, int w, int h) {
        int fieldY = y + h - 22;
        int fieldW = Math.max(40, w - 8 - 128);
        promptBox = new EditBox(font, x + 4, fieldY + 2, fieldW, 18, Component.literal("sc_prompt"));
        promptBox.setMaxLength(1000);
        promptBox.setBordered(false);
        promptBox.setTextColor(0xFFDDEEFF);
        promptBox.setHint(Component.literal("Ask the Super Computer...")
            .withStyle(ChatFormatting.DARK_GRAY));
        return promptBox;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    public void render(GuiGraphics gfx, Font font, int x, int y, int w, int h, int mouseX, int mouseY) {
        // ── Top bar: skill picker · credits · new session ───────────────────
        int barH = 20;
        skillBtnX = x; skillBtnY = y; skillBtnW = 150;
        skillHovered = inside(mouseX, mouseY, skillBtnX, skillBtnY, skillBtnW, barH);
        gfx.fill(skillBtnX, skillBtnY, skillBtnX + skillBtnW, skillBtnY + barH,
            skillHovered || skillDropdownOpen ? 0xFF202A3A : 0xFF1A1D2E);
        String skillLbl = "Skill: " + SKILL_LABELS[selectedSkill] + (skillDropdownOpen ? " ▲" : " ▼");
        if (font.width(skillLbl) > skillBtnW - 12) {
            while (!skillLbl.isEmpty() && font.width(skillLbl + "…") > skillBtnW - 12) {
                skillLbl = skillLbl.substring(0, skillLbl.length() - 1);
            }
            skillLbl += "…";
        }
        gfx.drawString(font, skillLbl, skillBtnX + 6, skillBtnY + 6, C_CYAN, false);

        String creditStr = creditBalance < 0 ? "--- credits" : creditBalance + " credits";
        gfx.drawString(font, creditStr, skillBtnX + skillBtnW + 12, y + 6, C_GOLD, false);

        newSessionBtnW = 84;
        newSessionBtnX = x + w - newSessionBtnW;
        newSessionBtnY = y;
        newSessionHovered = inside(mouseX, mouseY, newSessionBtnX, newSessionBtnY, newSessionBtnW, barH);
        gfx.fill(newSessionBtnX, newSessionBtnY, newSessionBtnX + newSessionBtnW, newSessionBtnY + barH,
            newSessionHovered ? 0xFF2A0808 : C_PANEL2);
        String nsLbl = "New Session";
        gfx.drawString(font, nsLbl, newSessionBtnX + (newSessionBtnW - font.width(nsLbl)) / 2,
            newSessionBtnY + 6, newSessionHovered ? C_RED : C_SEC, false);

        gfx.fill(x, y + barH + 2, x + w, y + barH + 3, C_BDIM);

        // ── Bottom input row geometry ─────────────────────────────────────
        inputBtnH = 22;
        int inputY = y + h - inputBtnH;
        sendBtnW = 56;
        sendBtnX = x + w - sendBtnW;
        sendBtnY = inputY;
        uploadBtnW = 64;
        uploadBtnX = sendBtnX - 4 - uploadBtnW;
        uploadBtnY = inputY;

        // ── Approval bar (reserved just above the input row when pending) ──
        PendingApproval approval = pendingApproval;
        int approvalH = approval != null ? 22 : 0;
        int approvalY = inputY - approvalH - 2;

        // ── Chat log area ────────────────────────────────────────────────
        logLeft = x; logRight = x + w;
        logTop = y + barH + 6;
        logBottom = approvalY - 4;

        renderLog(gfx, font, mouseX, mouseY);

        if (approval != null) {
            renderApprovalBar(gfx, font, x, approvalY, w, approvalH, mouseX, mouseY, approval);
        }

        // ── Upload / Send buttons ───────────────────────────────────────────
        uploadHovered = inside(mouseX, mouseY, uploadBtnX, uploadBtnY, uploadBtnW, inputBtnH);
        String upLbl = switch (refState) {
            case NONE -> "Attach";
            case READY -> "Ref ✓";
            case FAILED -> "Failed";
        };
        int upColor = switch (refState) {
            case NONE -> uploadHovered ? C_CYAN : C_SEC;
            case READY -> C_GREEN;
            case FAILED -> C_RED;
        };
        gfx.fill(uploadBtnX, uploadBtnY, uploadBtnX + uploadBtnW, uploadBtnY + inputBtnH, C_PANEL2);
        gfx.drawString(font, upLbl, uploadBtnX + (uploadBtnW - font.width(upLbl)) / 2, uploadBtnY + 7, upColor, false);

        sendHovered = inside(mouseX, mouseY, sendBtnX, sendBtnY, sendBtnW, inputBtnH) && !isGenerating;
        int sendBg = isGenerating ? 0xFF3A2E60 : (sendHovered ? 0xFF7055FF : C_PURPLE);
        gfx.fill(sendBtnX, sendBtnY, sendBtnX + sendBtnW, sendBtnY + inputBtnH, sendBg);
        String sendLbl = isGenerating ? "..." : "Send";
        gfx.drawString(font, sendLbl, sendBtnX + (sendBtnW - font.width(sendLbl)) / 2, sendBtnY + 7, 0xFFFFFFFF, false);

        gfx.fill(x, inputY - 1, x + w, inputY, C_BDIM);

        if (skillDropdownOpen) renderSkillDropdown(gfx, font, mouseX, mouseY);
    }

    private void renderLog(GuiGraphics gfx, Font font, int mouseX, int mouseY) {
        if (logBottom <= logTop) return;
        gfx.fill(logLeft, logTop, logRight, logBottom, C_PANEL2);

        if (log.isEmpty()) {
            String hint = "Pick a skill (or leave it on Auto) and send a prompt.";
            gfx.drawString(font, hint, logLeft + 6, logTop + 6, C_SEC, false);
            return;
        }

        int innerW = logRight - logLeft - 12;

        // Build wrapped render lines bottom-up so we can apply scroll from the bottom.
        record RLine(String text, int color, boolean isImage, String imageUrl) {}
        List<RLine> lines = new ArrayList<>();
        for (LogEntry e : log) {
            int color = switch (e.role) {
                case "user" -> C_CYAN;
                case "error" -> C_RED;
                case "system" -> C_GOLD;
                default -> C_PRI;
            };
            String prefix = switch (e.role) {
                case "user" -> "You: ";
                case "error" -> "⚠ ";
                case "system" -> "ℹ ";
                default -> "AI: ";
            };
            String body = (prefix + e.text).stripTrailing();
            if (!body.equals(prefix.stripTrailing())) {
                for (String wline : wrapPlain(font, body, innerW)) {
                    lines.add(new RLine(wline, color, false, ""));
                }
            }
            if (!e.imageUrl.isBlank()) {
                lines.add(new RLine("[image result]", C_SEC, true, e.imageUrl));
            }
            lines.add(new RLine("", C_SEC, false, "")); // spacer
        }

        int lineH = 10;
        int imageH = 54;
        int totalH = 0;
        for (RLine l : lines) totalH += l.isImage() ? imageH : lineH;

        int visibleH = logBottom - logTop - 4;
        int maxScroll = Math.max(0, totalH - visibleH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        gfx.enableScissor(logLeft, logTop, logRight, logBottom);

        // Draw from the bottom upward so the newest content anchors to the bottom edge.
        int cursorY = logBottom - 4 + scrollOffset;
        for (int i = lines.size() - 1; i >= 0; i--) {
            RLine l = lines.get(i);
            int rowH = l.isImage() ? imageH : lineH;
            cursorY -= rowH;
            if (cursorY + rowH < logTop || cursorY > logBottom) continue;
            if (l.isImage()) {
                renderInlineImage(gfx, l.imageUrl(), logLeft + 6, cursorY, Math.min(innerW, 160), imageH - 4);
            } else if (!l.text().isEmpty()) {
                gfx.drawString(font, l.text(), logLeft + 6, cursorY, l.color(), false);
            }
        }

        gfx.disableScissor();

        // Scrollbar hint
        if (maxScroll > 0) {
            int trackH = logBottom - logTop;
            int thumbH = Math.max(10, trackH * visibleH / Math.max(1, totalH));
            int thumbY = logBottom - thumbH - (int) ((long) scrollOffset * (trackH - thumbH) / Math.max(1, maxScroll));
            gfx.fill(logRight - 3, logTop, logRight - 1, logBottom, 0x22FFFFFF);
            gfx.fill(logRight - 3, thumbY, logRight - 1, thumbY + thumbH, 0x66FFFFFF);
        }
    }

    private static List<String> wrapPlain(Font font, String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) { out.add(""); continue; }
            StringBuilder cur = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = cur.isEmpty() ? word : cur + " " + word;
                if (font.width(candidate) > maxWidth && !cur.isEmpty()) {
                    out.add(cur.toString());
                    cur = new StringBuilder(word);
                } else {
                    cur = new StringBuilder(candidate);
                }
            }
            out.add(cur.toString());
        }
        return out;
    }

    private void renderApprovalBar(GuiGraphics gfx, Font font, int x, int y, int w, int h,
                                    int mouseX, int mouseY, PendingApproval approval) {
        gfx.fill(x, y, x + w, y + h, 0xFF2A2408);
        gfx.fill(x, y, x + w, y + 1, C_GOLD);

        String info = approval.request().resourceType() + " — " + approval.request().credits()
            + " credits (balance " + approval.request().balance() + ")";
        gfx.drawString(font, info, x + 6, y + 7, C_GOLD, false);

        approvalBtnH = h - 4;
        approvalBtnW = 60;
        approveBtnX = x + w - approvalBtnW * 2 - 8;
        approveBtnY = y + 2;
        rejectBtnX = x + w - approvalBtnW - 4;
        rejectBtnY = y + 2;

        approveHovered = inside(mouseX, mouseY, approveBtnX, approveBtnY, approvalBtnW, approvalBtnH);
        rejectHovered  = inside(mouseX, mouseY, rejectBtnX, rejectBtnY, approvalBtnW, approvalBtnH);

        gfx.fill(approveBtnX, approveBtnY, approveBtnX + approvalBtnW, approveBtnY + approvalBtnH,
            approveHovered ? C_GREEN : 0xFF0D2A1A);
        gfx.drawString(font, "Approve", approveBtnX + (approvalBtnW - font.width("Approve")) / 2,
            approveBtnY + 5, approveHovered ? 0xFF000000 : C_GREEN, false);

        gfx.fill(rejectBtnX, rejectBtnY, rejectBtnX + approvalBtnW, rejectBtnY + approvalBtnH,
            rejectHovered ? C_RED : 0xFF2A0808);
        gfx.drawString(font, "Reject", rejectBtnX + (approvalBtnW - font.width("Reject")) / 2,
            rejectBtnY + 5, rejectHovered ? 0xFF000000 : C_RED, false);
    }

    private void renderSkillDropdown(GuiGraphics gfx, Font font, int mx, int my) {
        int dropX = skillBtnX;
        int dropY = skillBtnY + skillBtnH + 2;
        int dropW = skillBtnW + 60;
        int dropH = SKILL_LABELS.length * 14;

        gfx.fill(dropX - 1, dropY, dropX + dropW + 1, dropY + dropH + 1, C_CYAN);
        gfx.fill(dropX, dropY, dropX + dropW, dropY + dropH, C_PANEL2);

        for (int i = 0; i < SKILL_LABELS.length; i++) {
            int itemY = dropY + i * 14;
            boolean hov = inside(mx, my, dropX, itemY, dropW, 14);
            if (hov) gfx.fill(dropX, itemY, dropX + dropW, itemY + 14, C_PANEL);
            gfx.drawString(font, SKILL_LABELS[i], dropX + 6, itemY + 3,
                i == selectedSkill ? C_CYAN : C_PRI, false);
        }
    }

    private void renderInlineImage(GuiGraphics gfx, String url, int x, int y, int maxW, int maxH) {
        ResourceLocation tex = TEX_CACHE.get(url);
        if (tex == null) {
            requestTexture(url);
            gfx.drawString(Minecraft.getInstance().font, "Loading image...", x, y + maxH / 2 - 4, C_SEC, false);
            return;
        }
        int[] size = TEX_SIZE.getOrDefault(url, new int[]{1, 1});
        float scale = Math.min(maxW / (float) size[0], maxH / (float) size[1]);
        int bw = Math.max(1, Math.round(size[0] * scale));
        int bh = Math.max(1, Math.round(size[1] * scale));
        gfx.blit(tex, x, y, bw, bh, 0f, 0f, size[0], size[1], size[0], size[1]);
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    public void mouseClicked(int mouseX, int mouseY, int x, int y, int w, int h) {
        if (promptBox != null) promptBox.mouseClicked(mouseX, mouseY, 0);

        if (skillDropdownOpen) {
            int dropX = skillBtnX;
            int dropY = skillBtnY + skillBtnH + 2;
            int dropW = skillBtnW + 60;
            int dropH = SKILL_LABELS.length * 14;
            if (inside(mouseX, mouseY, dropX, dropY, dropW, dropH)) {
                selectedSkill = (mouseY - dropY) / 14;
            }
            skillDropdownOpen = false;
            return;
        }

        if (skillHovered) { skillDropdownOpen = true; return; }
        if (newSessionHovered) { startNewSession(); return; }

        if (pendingApproval != null) {
            if (approveHovered) { resolveApproval(true); return; }
            if (rejectHovered)  { resolveApproval(false); return; }
        }

        if (uploadHovered) { onUploadClicked(); return; }
        if (sendHovered && !isGenerating) { sendPrompt(); return; }
    }

    public boolean mouseScrolled(int mouseX, int mouseY, double delta) {
        if (!inside(mouseX, mouseY, logLeft, logTop, logRight - logLeft, logBottom - logTop)) return false;
        scrollOffset = Math.max(0, scrollOffset + (int) (delta * 14));
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (promptBox != null && promptBox.isFocused()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                if (!isGenerating) sendPrompt();
                return true;
            }
            return promptBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return false;
    }

    public boolean charTyped(char c, int modifiers) {
        return promptBox != null && promptBox.isFocused() && promptBox.charTyped(c, modifiers);
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void startNewSession() {
        if (isGenerating) return;
        XeltaSessionClient.getInstance().reset();
        log.clear();
        referenceImagePath = "";
        refState = RefState.NONE;
        scrollOffset = 0;
        log.add(new LogEntry("system", "Started a new session."));
    }

    private void resolveApproval(boolean approved) {
        PendingApproval approval = pendingApproval;
        if (approval == null) return;
        pendingApproval = null;
        approval.decision().complete(approved);
        log.add(new LogEntry("system", approved ? "Approved." : "Rejected."));
    }

    private void onUploadClicked() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(4);
            filters.put(stack.UTF8("*.png")); filters.put(stack.UTF8("*.jpg"));
            filters.put(stack.UTF8("*.jpeg")); filters.put(stack.UTF8("*.webp"));
            filters.flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                "Select Reference Image", "", filters, "Image Files", false);
            if (path != null && !path.isBlank()) {
                referenceImagePath = path;
                refState = RefState.READY;
            }
        } catch (Exception e) {
            refState = RefState.FAILED;
            XeltaCraftMod.LOGGER.warn("[SuperComputerTab] File picker failed: {}", e.getMessage());
        }
    }

    private void sendPrompt() {
        if (promptBox == null) return;
        String prompt = promptBox.getValue().trim();
        if (prompt.isBlank() || isGenerating) return;

        promptBox.setValue("");
        log.add(new LogEntry("user", prompt));
        LogEntry assistantEntry = new LogEntry("assistant", "");
        log.add(assistantEntry);
        scrollOffset = 0;
        isGenerating = true;

        String skillId = SKILL_IDS[selectedSkill];
        String refPath = refState == RefState.READY ? referenceImagePath : null;

        SuperComputerWorkflow.chat(
            prompt, skillId, refPath,
            request -> {
                CompletableFuture<Boolean> decision = new CompletableFuture<>();
                decision.completeOnTimeout(false, 230, TimeUnit.SECONDS);
                Minecraft.getInstance().execute(() -> pendingApproval = new PendingApproval(request, decision));
                return decision;
            },
            delta -> Minecraft.getInstance().execute(() -> {
                assistantEntry.text.append(delta);
                scrollOffset = 0;
            })
        ).thenAccept(result -> Minecraft.getInstance().execute(() -> {
            isGenerating = false;
            pendingApproval = null;
            applyResult(assistantEntry, result);
        })).exceptionally(e -> {
            Minecraft.getInstance().execute(() -> {
                isGenerating = false;
                pendingApproval = null;
                log.add(new LogEntry("error", e.getMessage() != null ? e.getMessage() : "Unknown error."));
            });
            return null;
        });
    }

    private void applyResult(LogEntry assistantEntry, SuperComputerResult result) {
        if (!result.success()) {
            log.add(new LogEntry("error", result.error()));
            return;
        }
        if (assistantEntry.text.isEmpty() && !result.text().isBlank()) {
            assistantEntry.text.append(result.text());
        }
        if (result.hasMedia()) {
            assistantEntry.imageUrl = result.remoteUrl();
            requestTexture(result.remoteUrl());
            refreshCredits(); // API.md: a produced result is the frontend's signal to refresh balance
        }
        scrollOffset = 0;
    }

    // ── Result image texture cache ───────────────────────────────────────────

    private static final Map<String, ResourceLocation> TEX_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, int[]> TEX_SIZE = new ConcurrentHashMap<>();
    private static final Set<String> TEX_LOADING = ConcurrentHashMap.newKeySet();

    private static void requestTexture(String url) {
        if (url == null || url.isBlank() || TEX_CACHE.containsKey(url)) return;
        if (!url.toLowerCase().matches(".*\\.(png|jpg|jpeg|webp)$")) return; // skip non-image (website) urls
        if (!TEX_LOADING.add(url)) return;

        AssetDownloader.downloadAsset(url, AssetCacheManager.AssetType.IMAGE)
            .thenAccept(file -> Minecraft.getInstance().execute(() -> {
                try (FileInputStream in = new FileInputStream(file)) {
                    NativeImage image = NativeImage.read(in);
                    DynamicTexture texture = new DynamicTexture(image);
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                        "xeltacraft", "sc_result_" + Integer.toHexString(url.hashCode()));
                    Minecraft.getInstance().getTextureManager().register(id, texture);
                    TEX_SIZE.put(url, new int[]{image.getWidth(), image.getHeight()});
                    TEX_CACHE.put(url, id);
                } catch (Exception e) {
                    XeltaCraftMod.LOGGER.warn("[SuperComputerTab] Texture load failed: {}", e.getMessage());
                } finally {
                    TEX_LOADING.remove(url);
                }
            }))
            .exceptionally(e -> { TEX_LOADING.remove(url); return null; });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
