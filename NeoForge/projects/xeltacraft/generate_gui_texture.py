"""
generate_gui_texture.py
Run this once to create the xelta_terminal.png GUI background texture.
Place the output in:
  src/main/resources/assets/xeltacraft/textures/gui/xelta_terminal.png

Requirements: Pillow  (pip install Pillow)
"""
from PIL import Image, ImageDraw

W, H = 176, 200

img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# ── Background ───────────────────────────────────────────────
BG      = (20,  20,  40,  255)   # dark navy
PANEL   = (30,  30,  55,  255)   # slightly lighter panel
BORDER  = (0,   200, 220, 200)   # cyan border
SLOT_BG = (10,  10,  25,  255)   # slot interior
ARROW   = (0,   220, 200, 255)   # cyan arrow
INV_BG  = (44,  44,  44,  255)   # inventory section

draw.rectangle([0, 0, W-1, H-1], fill=BG)

# Main panel
draw.rectangle([4, 4, W-5, 162], fill=PANEL, outline=BORDER, width=1)

# Inventory section
draw.rectangle([4, 163, W-5, H-5], fill=INV_BG, outline=(60,60,60,255), width=1)

# ── Slots ─────────────────────────────────────────────────────
def draw_slot(x, y, size=18):
    draw.rectangle([x, y, x+size-1, y+size-1], fill=SLOT_BG, outline=BORDER, width=1)

# Input slot  (position matches XeltaTerminalMenu: 44, 35)
draw_slot(44, 35)

# Output slot (position: 116, 35)
draw_slot(116, 35)

# ── Arrow  44+18+5 = 67 to 116 ──────────────────────────────
ax1, ay = 66, 43
ax2     = 114
draw.line([ax1, ay, ax2, ay], fill=ARROW, width=2)
# arrowhead
draw.polygon([(ax2, ay-4), (ax2, ay+4), (ax2+6, ay)], fill=ARROW)

# ── Subtle corner circuits ────────────────────────────────────
CC = (0, 80, 90, 120)
draw.line([6, 6, 20, 6],   fill=CC, width=1)
draw.line([6, 6, 6, 20],   fill=CC, width=1)
draw.line([W-7, 6, W-21, 6],  fill=CC, width=1)
draw.line([W-7, 6, W-7, 20],  fill=CC, width=1)

# ── Prompt box area (8, 70, 168, 86) ─────────────────────────
draw.rectangle([8, 68, W-9, 86], fill=SLOT_BG, outline=BORDER, width=1)

# ── Generate button area (68, 92, 108, 112) ──────────────────
BTN = (0, 120, 140, 255)
BTN_BORDER = (0, 200, 220, 255)
draw.rectangle([68, 92, 108, 112], fill=BTN, outline=BTN_BORDER, width=1)

img.save("xelta_terminal.png")
print("Saved xelta_terminal.png — copy to assets/xeltacraft/textures/gui/")
