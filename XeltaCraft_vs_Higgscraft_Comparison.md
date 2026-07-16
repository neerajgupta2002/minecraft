# XeltaCraft vs Higgscraft — Complete Feature & Architecture Comparison

> **Document Purpose**: A deep-dive comparison between our **XeltaCraft** (Xelta AI × Minecraft) mod and **Higgscraft** (Higgsfield AI × Minecraft), identifying what they have that we don't, what we have that they don't, and a concrete roadmap for closing the gap.

---

## 1. Executive Summary

| Dimension | XeltaCraft (Ours) | Higgscraft (Theirs) |
|---|---|---|
| **AI Company** | Xelta AI | Higgsfield AI |
| **Mod Version** | In active dev | v1.4.0 (released) |
| **Mod ID** | `xeltacraft` | `higgsfield` |
| **Minecraft Version** | 1.21.x (NeoForge) | 1.21.1 (NeoForge ≥ 21.1.233) |
| **Auth Method** | JWT token (`/xeltacraft settoken`) | OAuth2 Device Flow (browser login) |
| **AI Services Covered** | Photo Lab, Mix (Style Transfer), GenAvatar, Motion | Image generation only |
| **Block Variety** | 1 block (Xelta Terminal) | 4 blocks (Image Generator, Video Projector, Video Screen, Generation Archive) |
| **Item Variety** | 4 items (Photo Slide, Film Roll, Avatar Scroll, Mixboard) | 4 items (Camera, Film Roll, Photo Slide, Structure) |
| **Video Playback** | Planned (Film Roll item exists) | ✅ Live (Video Projector + Video Screen blocks) |
| **In-game Camera** | ❌ Not yet | ✅ Live — screenshot as AI input |
| **Structure/Voxel Generation** | ❌ Not started | ✅ Live — AI-built houses placed block-by-block |
| **Generation Archive** | ❌ Not started | ✅ Browse past generations in-game |
| **Redstone Integration** | ❌ Not started | ✅ Trigger image generation via redstone |
| **Update Checker** | ❌ Not started | ✅ Built-in auto-update checker |
| **OpenRouter LLM** | ❌ Not started | ✅ Uses OpenRouter for prompt enhancement |
| **Source Available** | ✅ Full Java source | ❌ Compiled `.class` only |
| **Multi-service API** | ✅ 5 separate API clients | ❌ Single Higgsfield API |

---

## 2. What They Have That We Don't (Gap Analysis)

### 2.1 🎥 Video Projector + Video Screen Blocks

**What it is**: Two placeable in-game blocks:
- `VideoProjectorBlock` — Projects a video onto surrounding blocks (like a cinema projector)
- `VideoScreenBlock` — A display surface for video frames

**How it works**:
- `VideoCache` manages remote video frames fetched async from Higgsfield CDN
- `VideoProjectorBlockRenderer` / `VideoScreenBlockRenderer` render frames each game tick
- `FilmRollData` holds the video metadata (frame count, frame URLs, FPS)

**How we can do it** (using our `FilmRollItem`):
```
FilmRollItem (already exists) → holds video URL in NBT
  └─→ Add XeltaVideoBlock (new block)
  └─→ Add XeltaVideoBlockEntity (stores frame cache)
  └─→ Add XeltaVideoBlockRenderer (renders frames)
  └─→ Leverage existing XeltaMotionClient for video frames
```

**Priority**: HIGH — Film Roll item already exists, just needs the block counterpart.

---

### 2.2 📸 In-Game Camera Item

**What it is**: A `CameraItem` that:
1. Right-click → enters "camera mode" (screenshot overlay)
2. Takes an in-game screenshot
3. Exposes it to AI generation as the source image reference

**How it works** (Higgscraft):
- `CameraItem` → triggers `OpenPromptScreenPayload` network packet
- `CameraOverlay` → renders a viewfinder HUD
- `CameraCapture` → captures the GL framebuffer as PNG bytes
- `CameraClientEvents` → hooks into keyboard/mouse events during camera mode
- Captured image bytes go directly into the multipart POST for image generation

**How we can do it**:
```
XeltaCameraItem (new item)
  └─→ Right-click → C2S packet → server triggers S2C open-camera-overlay
  └─→ XeltaCameraOverlay (new screen) → captures screenshot bytes
  └─→ Feed screenshot bytes to XeltaApiClient.submitImageGeneration()
  └─→ Wire into PhotoSlideItem to display result
```

Our `XeltaApiClient` already accepts `byte[] sourceImage` in `submitImageGeneration()` — **the hardest part is already done**.

---

### 2.3 🏠 AI Structure / Voxel Generation

**What it is**: The most technically impressive feature — Higgscraft calls a Higgsfield endpoint that returns a 3D voxel model, then places it block-by-block in the world.

**How it works** (Higgscraft):
- `StructureItem` → player types a text prompt ("build me a medieval house")
- Server sends request to Higgsfield → returns a GLB/voxel payload
- `GLBParser` → parses the 3D geometry
- `Voxelizer` / `RayCastVoxelizer` → converts 3D mesh to Minecraft block grid
- `VoxelPlacer` + `VoxelPlacerRegistry` → places blocks safely, respecting Y limits
- `StructurePreviewClient` → shows a ghost preview before placement
- `GhostBlockRenderer` → renders semi-transparent preview
- `BlockMapper` → maps 3D colors to nearest Minecraft block type
- `PlacementPreview` → shows where structure will land

**Key classes in Higgscraft voxel pipeline**:
```
StructureItem → AiJobs.generateStructure() → HiggsfieldClient → GLBParser
    → Voxelizer → VoxelPayload → S2C packet → VoxelPlacer (server-side)
    → GhostBlockRenderer (client-side preview)
```

**How we can do it** (Xelta has no structure API currently, but we can design our own):
- Short-term: Use Higgsfield's voxel approach as reference, build our own `XeltaStructureItem`
- OR: Wait for Xelta to expose a 3D generation endpoint
- Long-term: Implement `XeltaVoxelizer` using same GLB → block-mapping approach

**Priority**: MEDIUM-LOW — Complex to build. No Xelta endpoint exists yet.

---

### 2.4 🗂️ Generation Archive Block

**What it is**: A `GenerationArchiveBlock` that stores all past AI generations and lets players browse them in a rich GUI (`GenerationArchiveScreen`).

**How it works**:
- `GenerationArchiveBlock` / `GenerationArchiveBlock$ArchiveFeed` — server-side storage
- `GenerationArchiveScreen` — a paginated gallery UI with thumbnails
- Players can "dump" generation archives (clear storage), preview generations, and print them as map photos

**How we can do it**:
```
XeltaArchiveBlock (new block)
  └─→ Stores list of { task_id, prompt, result_url, timestamp } in NBT
  └─→ XeltaArchiveScreen — gallery UI showing thumbnail images
  └─→ "Print" button → creates PhotoSlideItem with selected image
```

**Priority**: MEDIUM — Good user experience feature.

---

### 2.5 ⚡ Redstone Integration

**What it is**: The `ImageGeneratorBlock` responds to redstone power signals — when powered, it auto-triggers a generation with the stored prompt.

**How we can do it**:
- Override `neighborChanged()` in `XeltaTerminalBlock` to check for redstone signal
- When powered: read stored prompt from block NBT → call API → display result

**Priority**: MEDIUM — Very cool for Minecraft-native automation.

---

### 2.6 🔐 OAuth2 Device-Flow Authentication

**What it is**: Instead of manual token paste (`/settoken`), Higgscraft uses proper OAuth2:
1. `HiggsfieldAuth.startDeviceFlow()` → calls Higgsfield API for a device code
2. Prints a short URL + user code in chat → player opens in browser
3. Player authorizes → polls for token → stores securely in `~/.higgscraft/token.json`
4. Auto-refreshes tokens without re-login

**How we can do it** (for Xelta):
```java
// In XeltaCraftCommand.java — add /xeltacraft login
XeltaOAuthFlow.startDeviceFlow()
  → POST https://auth.xelta.ai/device/code
  → Print: "Visit https://xelta.ai/activate and enter: ABCD-EFGH"
  → Poll token endpoint until authorized
  → Store in config securely
```

**Priority**: HIGH — Better UX than copy-pasting JWT tokens.

---

### 2.7 🤖 LLM Prompt Enhancement (OpenRouter)

**What it is**: Higgscraft uses `OpenRouterClient` to optionally enhance/expand user prompts before sending to AI generation. A short prompt like "house" gets expanded to a detailed, high-quality generation prompt.

**How we can do it**:
```java
// XeltaPromptEnhancer (new class)
// Call OpenRouter API (gpt-4o-mini or claude-haiku) to expand prompt
// Config option: ENHANCE_PROMPTS = true/false
```

**Priority**: LOW — Nice-to-have, not core.

---

### 2.8 🔄 Update Checker

**What it is**: `UpdateChecker` class pings Higgsfield's update JSON at mod startup and notifies the player if a new version is available.

**How we can do it**:
```java
// XeltaUpdateChecker.java
// GET https://xelta.ai/minecraft/updates.json on mod init
// Compare semantic version with current MOD_VERSION
// Show chat notification if behind
```

**Priority**: LOW — Good for production, not blocking.

---

## 3. What We Have That They Don't (Our Advantages)

### 3.1 ✅ Multi-Service AI Architecture
We support **5 distinct Xelta AI services** via separate clients:
- `XeltaApiClient` — Photo Lab (image gen + background removal)
- `XeltaMixClient` — Style Transfer with 8-variation Mixboard
- `XeltaGenAvatarClient` — Talking avatar video generation
- `XeltaMotionClient` — Motion/video generation
- `XeltaCreditClient` — Real-time credit balance checking

Higgscraft connects to **only one** AI service (Higgsfield image+video generation).

### 3.2 ✅ Avatar Scroll — Talking Avatars in Minecraft
Our `AvatarScrollItem` + `XeltaGenAvatarClient` enables talking-avatar video generation — **no equivalent in Higgscraft**.

### 3.3 ✅ Mixboard — 8-Variation Style Transfer
`MixboardItem` + `XeltaMixClient` generates up to 8 style-transfer variations simultaneously, with optional refinement per image. **No equivalent in Higgscraft**.

### 3.4 ✅ Full Source Code — Extensible
We have full Java source → easy to extend, debug, and contribute. Higgscraft is compiled-only (`.class` files only).

### 3.5 ✅ Credit Balance System
`XeltaCreditClient` lets players check their Xelta AI credit balance in-game. Higgscraft has no credit visibility.

### 3.6 ✅ Robust Error Handling
Our `XeltaApiClient` has typed exceptions: `XeltaAuthException`, `XeltaRateLimitException`, `XeltaCreditsException`, `XeltaServerException` — each with meaningful user-facing messages.

---

## 4. Architecture Comparison

### 4.1 Authentication

| | XeltaCraft | Higgscraft |
|---|---|---|
| **Method** | Manual JWT token via `/xeltacraft settoken` | OAuth2 Device Flow (browser-based) |
| **Storage** | Config file (`xeltacraft.toml`) | `~/.higgscraft/token.json` |
| **Refresh** | Manual re-run command | Automatic token refresh |
| **UX** | Developer-friendly | User-friendly |

### 4.2 Network Layer

| | XeltaCraft | Higgscraft |
|---|---|---|
| **HTTP Client** | Java 21 `HttpClient` + Virtual Threads | Likely OkHttp or Apache HttpClient |
| **Async Model** | `CompletableFuture` chains | Unknown (compiled) |
| **Packet Types** | `C2SGeneratePacket`, `S2CStatusPacket` | 15+ typed network payloads |
| **Job Polling** | `XeltaJobPoller` (dedicated class) | Built into `AiJobs` class |

### 4.3 Block/Item Registry

| Category | XeltaCraft | Higgscraft |
|---|---|---|
| **Blocks** | 1 (XeltaTerminal) | 4 (ImageGenerator, VideoProjector, VideoScreen, GenerationArchive) |
| **Items** | 4 (PhotoSlide, FilmRoll, AvatarScroll, Mixboard) + 1 BlockItem | 4 (Camera, FilmRoll, PhotoSlide, Structure) |
| **Block Entities** | 1 (TerminalBlockEntity) | Multiple (one per block) |
| **Menus/Screens** | 1 (XeltaTerminalMenu) | 2+ (ImageGeneratorScreen, GenerationArchiveScreen, PromptScreen) |
| **Creative Tab** | ✅ | ✅ |
| **Data Components** | TBD | ✅ (`ModDataComponents`) |
| **Crafting Recipes** | TBD | ✅ (7 recipes defined) |

### 4.4 Client-Side Rendering

| Feature | XeltaCraft | Higgscraft |
|---|---|---|
| **Video Playback** | ❌ | ✅ VideoProjectorBlockRenderer + VideoScreenBlockRenderer |
| **Camera Overlay** | ❌ | ✅ CameraOverlay |
| **Photo Rendering** | ❌ (PhotoSlideItem exists, renderer TBD) | ✅ PhotoSlideItemRenderer with UV mapping |
| **Ghost Preview** | ❌ | ✅ GhostBlockRenderer for structure placement |
| **Generation Screen** | ✅ (XeltaTerminalMenu) | ✅ ImageGeneratorScreen + PromptScreen |
| **Archive Screen** | ❌ | ✅ GenerationArchiveScreen (paginated gallery) |

---

## 5. Higgscraft Data — Recipes Breakdown

Higgscraft ships with 7 crafting recipes (ours: none yet):

| Item/Block | Recipe |
|---|---|
| `higgsfield:camera` | Iron ×7 + Ender Eye + Redstone |
| `higgsfield:film_roll` | (unknown) |
| `higgsfield:generation_archive` | (unknown) |
| `higgsfield:image_generator` | (unknown) |
| `higgsfield:photo_slide` | (unknown) |
| `higgsfield:structure` | (unknown) |
| `higgsfield:video_projector` | (unknown) |

**Action item**: Define crafting recipes for all XeltaCraft items and blocks.

---

## 6. Roadmap — How To Close The Gap

### Phase A — Foundation Improvements (Next 2 Weeks)

| Task | Priority | Effort | File(s) to Create/Modify |
|---|---|---|---|
| Add crafting recipes for all XeltaCraft items/blocks | 🔴 HIGH | Small | `src/main/resources/data/xeltacraft/recipe/*.json` |
| Implement `PhotoSlideItemRenderer` (render image on item frame) | 🔴 HIGH | Medium | `item/PhotoSlideItemRenderer.java` |
| Add `XeltaArchiveBlock` — store past generations | 🔴 HIGH | Medium | `block/XeltaArchiveBlock.java` |
| Add `XeltaArchiveScreen` — gallery browser UI | 🔴 HIGH | Large | `client/XeltaArchiveScreen.java` |

### Phase B — Camera & Video (Month 1)

| Task | Priority | Effort | File(s) to Create/Modify |
|---|---|---|---|
| Implement `XeltaCameraItem` | 🟠 HIGH | Medium | `item/XeltaCameraItem.java` |
| Implement `XeltaCameraOverlay` (screenshot capture) | 🟠 HIGH | Medium | `client/XeltaCameraOverlay.java` |
| Add `XeltaVideoBlock` + `XeltaVideoBlockEntity` | 🟠 HIGH | Large | `block/XeltaVideoBlock.java` |
| Add `XeltaVideoBlockRenderer` (frame-by-frame playback) | 🟠 HIGH | Large | `client/XeltaVideoBlockRenderer.java` |

### Phase C — Polish & UX (Month 2)

| Task | Priority | Effort | File(s) to Create/Modify |
|---|---|---|---|
| OAuth2 Device-Flow authentication | 🟡 MEDIUM | Large | `api/XeltaOAuthFlow.java` |
| Redstone trigger on XeltaTerminalBlock | 🟡 MEDIUM | Small | `block/XeltaTerminalBlock.java` |
| Update checker at mod startup | 🟢 LOW | Small | `XeltaUpdateChecker.java` |
| OpenRouter prompt enhancement | 🟢 LOW | Medium | `api/XeltaPromptEnhancer.java` |

### Phase D — Advanced (Month 3+)

| Task | Priority | Effort | Notes |
|---|---|---|---|
| AI Structure / Voxel generation | 🟡 MEDIUM | Very Large | Needs Xelta 3D endpoint first |
| GLB Parser + Voxelizer | 🟡 MEDIUM | Very Large | Can adapt Higgscraft's approach |

---

## 7. Immediate Action: Add Crafting Recipes

The fastest win is adding JSON crafting recipes. Here are suggested recipes:

```json
// photo_slide.json — 1 Paper + 1 Glass Pane + 1 Feather
{
  "type": "minecraft:crafting_shaped",
  "pattern": ["GPG", " F "],
  "key": {
    "G": {"item": "minecraft:glass_pane"},
    "P": {"item": "minecraft:paper"},
    "F": {"item": "minecraft:feather"}
  },
  "result": {"id": "xeltacraft:photo_slide", "count": 1}
}

// film_roll.json — Iron + Paper + Gold Nugget
{
  "type": "minecraft:crafting_shaped",
  "pattern": ["GIG", "PIP", "GIG"],
  "key": {
    "G": {"item": "minecraft:gold_nugget"},
    "I": {"item": "minecraft:iron_ingot"},
    "P": {"item": "minecraft:paper"}
  },
  "result": {"id": "xeltacraft:film_roll", "count": 1}
}

// xelta_terminal.json — Iron + Gold + Ender Pearl + Quartz
{
  "type": "minecraft:crafting_shaped",
  "pattern": ["IQI", "GEG", "III"],
  "key": {
    "I": {"item": "minecraft:iron_ingot"},
    "Q": {"item": "minecraft:quartz"},
    "G": {"item": "minecraft:gold_ingot"},
    "E": {"item": "minecraft:ender_pearl"}
  },
  "result": {"id": "xeltacraft:xelta_terminal", "count": 1}
}
```

---

## 8. Key Technical Differences — Deep Dive

### Authentication Gap: Token vs OAuth
```
Current (XeltaCraft):                    Target (like Higgscraft):
/xeltacraft settoken <paste_jwt>    →    /xeltacraft login
                                           → "Visit https://xelta.ai/activate"
                                           → "Enter code: XELT-A123"
                                           → [Player logs in browser]
                                           → Token stored automatically
                                           → Auto-refresh when expired
```

### Video Rendering Gap
```
Current (XeltaCraft):                    Target:
FilmRollItem exists (NBT URL stored) →  XeltaVideoBlock placed in world
No playback renderer                →   Fetch video frames from URL async
                                    →   Render frames each game tick
                                    →   Sync across server/client via packets
```

### Structure Generation Gap (Higgscraft's Biggest Feature)
```
Higgscraft Pipeline:
  "Build a medieval castle" 
      → HiggsfieldClient.generateStructure()
      → Returns GLB (3D geometry file)
      → GLBParser extracts mesh vertices + colors
      → RayCastVoxelizer converts mesh to voxel grid
      → BlockMapper maps each color to nearest Minecraft block
      → Client shows GhostBlockRenderer (transparent preview)
      → Player confirms → VoxelPlacer places blocks server-side
      → Uses chunk-safe placement with rate limiting
```

This is the most complex system in Higgscraft and represents **weeks** of engineering work. We should plan it for Phase D once Xelta has a 3D generation endpoint.

---

## 9. Summary — Where We Stand

```
╔════════════════════════════════════════════════════════════════╗
║            FEATURE PARITY SCORECARD                           ║
╠════════════════════════════════════════════════════════════════╣
║  Feature                      XeltaCraft    Higgscraft        ║
║ ─────────────────────────────────────────────────────────────  ║
║  Image Generation             ✅ DONE        ✅ DONE           ║
║  Style Transfer (Mixboard)    ✅ DONE        ❌ N/A            ║
║  Talking Avatar (GenAvatar)   ✅ DONE        ❌ N/A            ║
║  Motion/Video Generation      ✅ DONE        ✅ DONE           ║
║  Credit Balance API           ✅ DONE        ❌ N/A            ║
║  Photo Slide Item             ✅ DONE        ✅ DONE           ║
║  Film Roll Item               ✅ DONE        ✅ DONE           ║
║  In-Game Camera               ❌ TODO        ✅ DONE           ║
║  Video Playback Block         ❌ TODO        ✅ DONE           ║
║  Generation Archive           ❌ TODO        ✅ DONE           ║
║  AI Structure Generation      ❌ TODO        ✅ DONE           ║
║  Redstone Triggering          ❌ TODO        ✅ DONE           ║
║  OAuth2 Login Flow            ❌ TODO        ✅ DONE           ║
║  Crafting Recipes             ❌ TODO        ✅ DONE (7)       ║
║  Update Checker               ❌ TODO        ✅ DONE           ║
║  OpenRouter LLM               ❌ TODO        ✅ DONE           ║
╚════════════════════════════════════════════════════════════════╝

  ✅ We lead in: AI service variety (5 vs 1), source code availability
  🎯 Priority gaps: Camera, Video Block, Archive, Recipes, OAuth
  🔬 Advanced gap: Voxel/Structure generation (complex, needs API)
```

---

*Generated: 2026-07-14 | XeltaCraft Project | Compared against Higgscraft v1.4.0*
