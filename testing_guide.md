# XeltaCraft — Complete Manual Testing Guide
## From Launch to Every Feature

> **Bug fixed (2026-07-28):** `compileClientJava` was failing with  
> `cannot find symbol: variable playerInventory`  
> **Root cause:** `AbstractContainerScreen.playerInventory` is inaccessible from the  
> client sourceSet under Parchment mappings (NeoForge 1.21.1).  
> **Fix applied:** `XeltaTerminalScreen` now stores the inventory in its own `localInventory`  
> field at construction time instead of referencing the parent's field.

---

---

## PART 1 — Launch the Game

### Step 1 — Open a terminal (PowerShell)
Navigate to the project folder:
```powershell
cd "C:\Users\GCV\Desktop\minecraft\NeoForge\projects\xeltacraft"
```

### Step 2 — Build & launch Minecraft with the mod
```powershell
.\gradlew.bat runClient
```

> **First launch**: Takes 2–4 minutes (downloads Minecraft assets + sets up NeoForge).  
> **Subsequent launches**: ~30 seconds (daemon is warm).

The Minecraft launcher window will open automatically. Watch the console for:
```
[main/INFO] [minecraft/Minecraft]: Backend library on classpath: ...
[main/INFO] [FML]: Mods loaded: 2  ← must show 2 (minecraft + xeltacraft)
```

### Step 3 — Enter a world
1. Click **Singleplayer**
2. Click **Create New World**
3. Set **Game Mode** to **Creative** (easier for testing — infinite items)
4. Click **Create World**
5. Wait for world to generate and load

---

## PART 2 — Enable Cheats & Prepare

Once in the world, press **T** to open chat and run:

```
/gamemode creative
/gamerule keepInventory true
```

Press **F3** to confirm you're in the world (shows coordinates top-left). Press **F3** again to close.

---

## PART 3 — Get Your Auth Token

### Step 1 — Get JWT from xelta.ai
1. Open your browser → go to [https://xelta.ai](https://xelta.ai)
2. Log in with your account
3. Press **F12** (DevTools)
4. Click **Application** tab (top bar of DevTools)
5. In left sidebar: **Local Storage** → `https://xelta.ai`
6. Find the row with key `token`
7. Click the value → **Ctrl+A** to select all → **Ctrl+C** to copy

### Step 2 — Set the token in Minecraft
Press **T** to open chat, type:
```
/xeltacraft settoken <paste-token-here>
```
*(Right-click in the chat box to paste)*

✅ **Expected response in chat:**
```
✅ Auth token saved: eyJ••••••••xxxx
```

### Step 3 — Verify balance
```
/xeltacraft balance
```
✅ Expected: `Credits: 1,250` (your actual balance)

---

## PART 4 — Craft and Place the Terminal

### Get the Terminal block in Creative
Press **E** to open inventory → click **Search** tab (compass icon) → type:
```
xelta
```
You should see **Xelta AI Terminal** block. Click it to add to hotbar.

*OR use a command:*
```
/give @p xeltacraft:xelta_terminal
```

### Place the Terminal
1. Select the Terminal in your hotbar (number keys 1–9)
2. Look at a flat surface (ground or wall)
3. **Right-click** to place it
4. **Right-click again** on the placed block to open the GUI

---

## PART 5 — The Terminal GUI Walkthrough

When you right-click the Terminal, the **XeltaCraft Terminal GUI** opens.

### What you see:
```
┌─────────────────────────────────────────────────────┐
│ XELTA AI TERMINAL        [1,250 credits]  [Player]  │
│─────────────────────────────────────────────────────│
│ Dashboard | Generate | Gallery | History | Credits | Settings │
│─────────────────────────────────────────────────────│
│                                                     │
│  [Tab Content Area]                                 │
│                                                     │
│─────────────────────────────────────────────────────│
│ Status: IDLE                                        │
└─────────────────────────────────────────────────────┘
```

- **Top bar**: Logo + credit chip + player name chip
- **Tab bar**: 6 tabs with cyan underline on active tab
- **Content area**: Changes per tab
- **Status strip**: Shows current generation state

> **If you see a Login Screen instead**: Type your email and password and click **Sign In**. This happens when no token is set in config.

---

## PART 6 — Test Each Tab

### TAB 1 — Dashboard
**How to navigate:** Click **Dashboard** in the tab bar  
**What you see:** A grid of AI tool cards

**Test:**
- ✅ Cards appear: Image, Video, Avatar, Mix, Home Design, Giant Ads, Face Swap
- ✅ Credit chip in top-right shows your balance
- ✅ Click any card (e.g., "Image") → Terminal switches to **Generate tab** with Image pre-selected

---

### TAB 2 — Generate (Main Generation Panel)
**How to navigate:** Click **Generate** in the tab bar  
**What you see:**
- Left panel: Service dropdown + prompt text box + size selector + Upload Reference button + **Generate** button
- Right panel: Status + progress + result preview area

**Testing Image Generation (Service 0):**

1. Click the **Services** button (top-left of left panel) → dropdown opens
2. Select **Image** from the list
3. Click in the large text box and type:
   ```
   A glowing crystal cave in Minecraft style, cinematic lighting, photorealistic
   ```
4. *(Optional)* Click **Upload Reference** → file picker opens → select any PNG/JPG from your computer
5. Click the **Generate** button (large button at bottom of left panel)

**Watch the right panel status:**
```
Validating request...          ← immediate
Job submitted (ID: abc12...). Polling...
[33%] Processing image...
[66%] Almost done...
Generation complete! Fetching result...
Downloading image...
✓ Done! Image ready.
```

**After generation:**
- Status strip at bottom changes to `✓ Done`
- Close the terminal (press **Esc**)
- Navigate to **Gallery** tab to see the result

---

### TAB 3 — Gallery
**How to navigate:** Click **Gallery** in the tab bar  
**What you see:** Grid of image/video cards from your local cache

**Test:**
1. After generating an image, click **Gallery**
2. ✅ Your new image appears as a card (thumbnail + prompt text + date)
3. Click **Refresh** button → rescans `.minecraft/xeltacraft/cache/`
4. Cards should persist between sessions (files are saved to disk)

**Verify on disk:**
- Press **Win+R** → type: `%appdata%\.minecraft\xeltacraft\cache\images\`
- ✅ Your PNG file should be there

---

### TAB 4 — History
**How to navigate:** Click **History** in the tab bar  
**What you see:** List of past generations fetched from the server (MongoDB via SuperXelta API)

**Test:**
1. Click **History** tab
2. ✅ List loads from server (requires valid token)
3. Each row shows: thumbnail URL, prompt, date
4. Click **Next Page** → loads next batch
5. Click **Prev Page** → goes back

> If empty: Generate something first, or check that token is valid with `/xeltacraft balance`

---

### TAB 5 — Credits
**How to navigate:** Click **Credits** in the tab bar  
**What you see:** Your credit balance with tier colour + buy button

**Test:**
1. Click **Credits** tab
2. ✅ Large number shows your balance (e.g., `1,250`)
3. Balance chip colour:
   - 🟢 Green: > 500 credits
   - 🟡 Yellow: 100–500
   - 🔴 Red: < 100
4. Click **Refresh** → re-fetches from `devpmt.xelta.ai`
5. Click **Buy Credits** → opens xelta.ai/pricing in chat as clickable link

---

### TAB 6 — Settings
**How to navigate:** Click **Settings** in the tab bar  
**What you see:** Configuration panel

**Test:**
1. Click **Settings** tab
2. ✅ Shows:
   - Session ID (truncated, e.g. `abc12345-...`)
   - Auth status: `Online` (green) or `Guest` (red)
   - SuperXelta URL field
   - Debug mode toggle
3. Click **Reset Session** → clears `session.id` file (new session on next generation)
4. Click **Logout** → clears token → GUI closes and reopens to Login screen

---

## PART 7 — Test All 8 Generation Services

### ⚡ Quick setup for all services
Before testing video/avatar, run:
```
/xeltacraft setrefvideo https://cdn.example.com/your-reference.mp4
```
*(Replace with a real MP4 URL — used for Motion Control video generation)*

---

### SERVICE 0 — Image Generation
**Steps:** Generate tab → Services → **Image** → type prompt → Generate  
**Expected:** PNG saved to `.minecraft/xeltacraft/cache/images/`

---

### SERVICE 1 — Video Generation
**Prerequisites:**
- Reference image file on your computer (or from a previous image generation)
- Reference video URL set via `/xeltacraft setrefvideo <url>`

**Steps:**
1. Generate tab → Services → **Video**
2. Type prompt:
   ```
   Character walking through a Minecraft forest, cinematic
   ```
3. Click **Upload Reference** → select your reference image PNG
4. Click **Generate**

**Expected progress:**
```
Downloading reference video...
Submitting video generation job...
[25%] Processing...
Video ready! Downloading...
✓ Done! Video ready.
```
**Result:** MP4 in `.minecraft/xeltacraft/cache/videos/`

---

### SERVICE 2 — Avatar (Talking Avatar)
**Steps:**
1. Generate tab → Services → **Avatar**
2. Type your message (the avatar will speak this):
   ```
   Hello! Welcome to XeltaCraft, the AI-powered Minecraft mod!
   ```
3. Upload a character PNG image
4. Click **Generate**

**To set a different voice first:**
```
/xeltacraft listvoices
/xeltacraft setvoice <uuid-from-list>
```

---

### SERVICE 3 — Mix (Style Mixboard)
**Steps:**
1. Generate tab → Services → **Mix**
2. Type a style command (no image needed):
   ```
   Pixel art RPG dungeon with torches and treasure chests
   ```
3. Click **Generate**

**Expected:** Generates 4 style variations, caches first one locally.

---

### SERVICE 5 — Home Design (SuperXelta)
**Steps:**
1. Generate tab → Services → **Home Design**
2. Click **Upload Reference** → select a **room photo** (any interior JPEG/PNG)
3. Type style prompt:
   ```
   Modern Japanese minimalist bedroom with bamboo accents
   ```
4. Click **Generate**

**Expected:** Redesigned room image saved locally.  
> Requires SuperXelta backend running. Set `super_xelta_url` in config if needed.

---

### SERVICE 6 — Giant Ads (SuperXelta)
**Steps:**
1. Generate tab → Services → **Giant Ads**
2. *(Optional)* Upload a product image
3. Type ad campaign prompt:
   ```
   Minecraft diamond sword billboard in Tokyo Shibuya crossing at night
   ```
4. Click **Generate**

**Expected:** Giant advertisement campaign image.

---

### SERVICE 7 — Face Swap (SuperXelta)
**Steps:**
1. Generate tab → Services → **Face Swap**
2. Click **Upload Reference** → select **source face image** (the face to transplant)
3. In the **Reference Video URL** field → type the **local path** to the target image:
   ```
   C:\Users\YourName\Pictures\target.jpg
   ```
4. Click **Generate**

**Expected:** Face from source image placed into target image.

---

## PART 8 — Verify Results on Disk

Press **Win+E** (File Explorer) and navigate to:
```
C:\Users\<YourName>\AppData\Roaming\.minecraft\xeltacraft\
```

You should see:
```
xeltacraft/
  cache/
    images/     ← Image gen, Home Design, Giant Ads, Face Swap results
    videos/     ← Video, Avatar results
  session/
    session.id  ← SuperXelta session (auto-created)
    refresh.tok ← Auth refresh token (auto-created on login)
```

---

## PART 9 — Debug Logging

If anything fails, enable verbose logs:

**Option A — Edit config** (in `.minecraft/config/xeltacraft-client.toml`):
```toml
[behaviour]
  debug_mode = true
```

**Option B — Settings tab** in Terminal GUI → toggle Debug

Then check:
```
C:\Users\<YourName>\AppData\Roaming\.minecraft\logs\latest.log
```

Search (Ctrl+F) for:
```
[PhotoLab]       ← Image generation steps
[SuperXelta]     ← SuperComputer tool calls
[Storage]        ← File save/download steps
[Session]        ← Auth and session events
[Credits]        ← Balance fetch
[MotionControl]  ← Video generation
[GenAvatar]      ← Avatar steps
```

---

## PART 10 — Full Command Quick Reference

| Press / Type | What it does |
|---|---|
| `T` | Open chat for commands |
| `E` | Open inventory |
| `Esc` | Close GUI / go back |
| `F3` | Debug overlay (shows coords, FPS) |
| `F2` | Screenshot (saved to `.minecraft/screenshots/`) |
| `F5` | Toggle 3rd person view |
| `/xeltacraft settoken <jwt>` | Set auth token |
| `/xeltacraft balance` | Check credits |
| `/xeltacraft status` | Check terminal state |
| `/xeltacraft reset` | Reset terminal to IDLE |
| `/xeltacraft setrefvideo <url>` | Set reference video URL |
| `/xeltacraft listvoices` | List TTS voice options |
| `/xeltacraft setvoice <uuid>` | Select TTS voice |
| `/xeltacraft setgeminikey <key>` | Set Gemini key (Build mode) |
| `/xeltacraft help` | Full command list |
| `/give @p xeltacraft:xelta_terminal` | Get Terminal block |
| `/gamemode creative` | Switch to creative mode |

---

## PART 11 — Stop the Game

To stop the Gradle-run Minecraft client:
- Click the **X** button on the Minecraft window, OR
- Press `Ctrl+C` in the PowerShell terminal running `runClient`

---

## PART 12 — Common Problems

| Problem | What you see | Fix |
|---------|-------------|-----|
| Game won't start | `BUILD FAILED` in console | Run `.\gradlew.bat compileJava` first to check for errors |
| Login screen always appears | Terminal opens to email/password | Run `/xeltacraft settoken <jwt>` |
| `Token expired` error in chat | Red error message | Get new token from xelta.ai → repeat settoken |
| Generate button does nothing | Button click, no progress | Check logs for `[PhotoLab]` errors; verify balance |
| `Insufficient credits` | Red toast in chat | Top up at xelta.ai/pricing |
| Video stuck at "Downloading reference video" | Progress frozen | Set ref video: `/xeltacraft setrefvideo <url>` |
| Gallery shows nothing | Empty grid | Check `.minecraft/xeltacraft/cache/images/` folder |
| SuperXelta tools fail with 404 | Error toast | Update `super_xelta_url` in config to your FastAPI URL |
| `Cannot reach Xelta` | Network error toast | Check internet; mod needs HTTPS to `*.xelta.ai` |
| Mod not listed on startup | `Mods loaded: 1` in log | Recompile: `.\gradlew.bat compileJava` then re-run |
