# XeltaCraft & API Tester — Master Testing Guide

This single master document contains everything you need to test the complete Xelta AI service integration. It is split into two parts:
1. **Part A**: Testing the backend APIs directly using the isolated Web Frontend.
2. **Part B**: Testing the final integrated product inside the Minecraft Mod.

---

# PART A: Isolated Web Frontend Testing
*Use this part to verify that the Xelta backend APIs (Photo Lab, Motion Control, GenAvatar, Xelta Mix, Credits) are online and returning the correct data.*

### Step 1: Start the Local Server
Because the frontend communicates with a production API, you must serve it over HTTP to avoid browser CORS blocks.
1. Open your **Command Prompt** (cmd) or **PowerShell**.
2. Navigate to the frontend folder by running:
   ```bash
   cd "C:\Users\GCV\Desktop\Xelta-API-Tester"
   ```
3. Start a static server:
   - **If using Node.js**: run `npx serve .`
   - **If using Python**: run `python -m http.server 3000`
4. Open your web browser and go to: **`http://localhost:3000`**

### Step 2: Set Your Authentication Token
1. Go to [xelta.ai](https://xelta.ai) and log in.
2. Press **F12** to open Developer Tools -> go to the **Application** tab -> **Local Storage**.
3. Copy the value next to `token`.
4. In the API Tester at `http://localhost:3000`, paste this token into the **"JWT Authorization Token"** box and click **Save Token**.

### Step 3: Run the Tests
- **Credits**: Click "Fetch Balance". It should return a number in green.
- **Photo Lab**: Type a prompt, choose an image (optional), and click "Generate Image". An image should appear below.
- **Motion Control**: Upload a character image, paste an MP4 URL into the reference box, and click "Generate Video". It will poll the server and eventually display a video.
- **GenAvatar**: Click "Fetch Voices", select a voice from the dropdown, type a message, upload an avatar image, and click "Generate Avatar".
- **Xelta Mix**: Type a style command (e.g., "anime style") and click "Generate Mixboard". It will poll until 100% and then show a grid of images.

*(Tip: Press F12 -> Network tab to view the raw JSON data the backend returns for any of these requests).*

---
<br>

# PART B: Minecraft Mod Testing
*Use this part to verify that the mod correctly connects to Xelta APIs, handles NBT data, and renders the AI results in-game.*

### Step 1: Launch and Configure
1. Launch your modded Minecraft client using NeoForge 1.21.1 with the compiled `xeltacraft-1.0.0.jar`.
2. Enter a world and open the chat box (press `T`).
3. Set your token (from Part A) by running:
   `/xeltacraft settoken <your-token-here>`
4. *Expected Output*: `✅ Auth token saved: ...`

### Step 2: Test Credit Balance
1. In the chat box, type: `/xeltacraft balance`
2. **Expected Output**:
   - `⏳ Fetching balance from Xelta...`
   - `💳 Xelta balance: [X] credits`. If under 50, it warns you in chat.

### Step 3: Test Photo Lab (Image Generation)
1. Place a **Xelta Terminal** block in the world and right-click it.
2. Type a prompt (e.g., `"A beautiful cyberpunk city skyline at night"`).
3. Leave the input/output slots EMPTY. Click **GENERATE**.
4. **Expected Output**: The UI says `⏳ Sending to Xelta AI...` -> A **Photo Slide** item appears in the output slot. Hovering over it says `✅ Image ready`.

### Step 4: Test Motion Control (Video Generation)
1. Set a reference video URL in chat: 
   `/xeltacraft setrefvideo https://example.com/some-video.mp4`
2. Open the **Xelta Terminal**.
3. Place the **Photo Slide** (from Step 3) into the **INPUT** (left) slot.
4. Place a blank **Film Roll** item into the **OUTPUT** (right) slot.
5. Click **GENERATE**. 
6. **Expected Output**: The UI says `⏳ Uploading to Motion Control...` and polls. Once finished, the Film Roll updates to say `✅ Video ready`.

### Step 5: Test GenAvatar (Talking Avatar)
1. In chat, list available voices: `/xeltacraft listvoices`
2. Set a voice ID: `/xeltacraft setvoice <voice-id>` (e.g. `1234-abcd`).
3. Open the **Xelta Terminal**.
4. Place a blank **Avatar Scroll** into the **OUTPUT** (right) slot.
5. Type the message you want spoken into the prompt box (e.g., `"Hello from XeltaCraft!"`).
6. Click **GENERATE**.
7. **Expected Output**: Mod uploads your player skin (or Photo Slide if placed in input), polls the `/job` endpoint, and the Avatar Scroll updates to say `✅ Avatar video ready`.

### Step 6: Test Xelta Mix (Style Transfer)
1. Open the **Xelta Terminal**.
2. Place a blank **Mixboard** item into the **OUTPUT** (right) slot.
3. Type a style prompt into the box (e.g., `"Turn this into anime style"`).
4. Click **GENERATE**.
5. **Expected Output**: Polls `/progress` to 100%. The Mixboard item updates to say `✅ 8 variations (viewing #1)`. You can sneak+scroll to cycle through them.

---
**Troubleshooting the Mod**: If a block gets stuck on "Generating..." due to a server restart, run `/xeltacraft reset` while standing near it. Check your Minecraft log (`latest.log`) for detailed HTTP errors.
