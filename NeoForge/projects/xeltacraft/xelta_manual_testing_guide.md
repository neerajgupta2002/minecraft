# XeltaCraft — Step-by-Step Manual Testing Guide

This guide will walk you through how to manually test every single phase and generation mode of the XeltaCraft mod directly inside the Minecraft client.

---

## 🔧 Prerequisites (Do this first)
1. **Launch Minecraft**: Run the modded NeoForge 1.21.1 client.
2. **Get your API Key**:
   - Go to `https://xelta.ai` and log in.
   - Press `F12` to open Developer Tools -> Go to the **Application** tab -> **Local Storage**.
   - Copy the value for `token`.
3. **Set your Token In-Game**:
   - In Minecraft, run: `/xeltacraft settoken <your-token-here>`
   - *Expected Output*: Chat message saying `✅ Auth token saved: ...`

---

## 🧪 Test 1: Credit Balance (Phase 3)
*Goal: Ensure the mod can read your real Xelta account balance.*

**Steps:**
1. In the chat box, type: `/xeltacraft balance`
2. **Expected Output**:
   - `⏳ Fetching balance from Xelta...`
   - `💳 Xelta balance: [X] credits` (where X is a number). If under 50, it will turn red and give a warning.

---

## 🧪 Test 2: Photo Lab / Image Generation (Phase 4)
*Goal: Generate a Photo Slide image.*

**Steps:**
1. Place a **Xelta Terminal** block in the world and right-click to open it.
2. In the GUI's prompt box, type an image prompt (e.g., `"A beautiful cyberpunk city skyline at night"`).
3. Do NOT put anything in the slots. Click **GENERATE**.
4. **Expected Output**:
   - The UI will say `⏳ Sending to Xelta AI...`
   - After a few seconds, it will say `✅ Image ready! Collect your Photo Slide.`
   - A **Photo Slide** item will appear in the **OUTPUT** (right) slot.
   - Hovering over the Photo Slide in your inventory will show `✅ Image ready` and the prompt you typed.

---

## 🧪 Test 3: Motion Control / Video Generation (Phase 5)
*Goal: Animate an existing Photo Slide using a reference video.*

**Steps:**
1. First, set a reference video URL. Run this command:
   - `/xeltacraft setrefvideo https://example.com/some-video.mp4` *(Replace with a real direct MP4 URL).*
2. Open the **Xelta Terminal**.
3. Place the generated **Photo Slide** (from Test 2) into the **INPUT** (left) slot.
4. Place a blank **Film Roll** item into the **OUTPUT** (right) slot.
5. Click **GENERATE**. (The prompt box is ignored for video gen, but you can type something descriptive).
6. **Expected Output**:
   - The UI says `⏳ Uploading to Motion Control...` then cycles through progress states.
   - After a minute or two, it will say `✅ Video ready! Collect your Film Roll.`
   - The **Film Roll** in the right slot will now have the video data. Hovering over it will show `✅ Video ready`.

---

## 🧪 Test 4: GenAvatar / Talking Avatar (Phase 7a)
*Goal: Generate a talking avatar video based on a TTS voice and a skin/image.*

**Steps:**
1. First, list available voices: `/xeltacraft listvoices`
2. Pick an ID from the chat and set it: `/xeltacraft setvoice <voice-id>` (e.g. `/xeltacraft setvoice 1234-abcd`).
3. Open the **Xelta Terminal**.
4. (Optional) Put a **Photo Slide** in the INPUT slot to use that image as the avatar. If left empty, it will automatically download your Minecraft player skin.
5. Place a blank **Avatar Scroll** into the **OUTPUT** (right) slot.
6. Type the message you want the avatar to speak into the prompt box (e.g., `"Hello from XeltaCraft! This is a test."`).
7. Click **GENERATE**.
8. **Expected Output**:
   - The UI says `⏳ Uploading avatar image...` -> `⏳ Avatar generating... 10%`.
   - After generation completes, it will say `✅ Avatar ready! Collect your Avatar Scroll.`
   - Hovering over the **Avatar Scroll** will show the message text, the voice used, and `✅ Avatar video ready`.

---

## 🧪 Test 5: Xelta Mix / Style Transfer (Phase 7b)
*Goal: Generate a mixboard with multiple style variations.*

**Steps:**
1. Open the **Xelta Terminal**.
2. Place a blank **Mixboard** item into the **OUTPUT** (right) slot.
3. Type a style prompt into the box (e.g., `"Turn this into anime style"`).
4. Click **GENERATE**.
5. **Expected Output**:
   - The UI says `⏳ Submitting style board...` then starts polling progress `0-100%`.
   - When finished, it will say `✅ 8 style variations ready! Collect your Mixboard.`
   - Hovering over the **Mixboard** will show `✅ 8 variations (viewing #1)`.

---

## 🚑 Troubleshooting
- **Terminal is stuck on Generating?** Run `/xeltacraft reset` near the block to reset it.
- **Errors in Chat?** Check your Minecraft client logs. The mod outputs detailed debug info if an API endpoint fails, including exact HTTP status codes and JSON error messages.
