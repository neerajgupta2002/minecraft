# XeltaCraft — Final Implementation Documentation

This document serves as the complete technical implementation guide for the XeltaCraft Minecraft Mod (NeoForge 1.21.1), integrating all requested phases of the Xelta AI platform APIs.

## 1. Project Architecture

The project bridges Minecraft's server-thread state machine with asynchronous Java `HttpClient` operations executed on virtual threads.

- **Mod Base**: NeoForge 1.21.1
- **Networking**: `java.net.http.HttpClient` with `VirtualThreadPerTaskExecutor`
- **Data Persistence**: Minecraft 1.21 `DataComponents` API
- **GUI Generation Cycle**: Handled by `XeltaTerminalBlockEntity` state machine (`IDLE` -> `GENERATING` -> `DONE`/`ERROR`).
- **Mode Routing**: Dynamic mode detection based on the items placed in the Terminal's Input/Output slots.

## 2. Integrated Services & Phases

All services connect to their designated staging/production endpoints using JWT Bearer Token authentication.

### Phase 4: Photo Lab (Image Gen & Background AI)
- **Endpoint**: `https://bgremover.xelta.ai/api/background/remove`
- **Client**: `XeltaApiClient`
- **Mode Trigger**: Default mode. (Empty or non-specific items in output slot).
- **Flow**: User enters prompt -> Poller waits 3s -> `PhotoSlideItem` generated with returned image URL.

### Phase 5: Motion Control (AI Video Generation)
- **Endpoint**: `https://advavatar.stg.xelta.ai/api/v1/motion-control`
- **Client**: `XeltaMotionClient`
- **Mode Trigger**: `PhotoSlideItem` in Input Slot + `FilmRollItem` in Output Slot.
- **Flow**: Mod downloads source image (from PhotoSlide) and Reference Video (configured) -> Uploads multipart to API -> Polls 5s intervals -> `FilmRollItem` updated with result video URL.

### Phase 3: Credit Balance
- **Endpoint**: `https://devpmt.xelta.ai/api/users/credits`
- **Client**: `XeltaCreditClient`
- **Trigger**: Opening the Xelta Terminal GUI or running `/xeltacraft balance`.
- **Flow**: Fetches live integer balance. Updates UI visually and warns in chat if balance < 50.

### Phase 7a: GenAvatar (Talking Avatars) (Optional Phase Completed)
- **Endpoint**: `https://stgavatar.xelta.ai/api/v1`
- **Client**: `XeltaGenAvatarClient`
- **Mode Trigger**: `AvatarScrollItem` in Output Slot.
- **Flow**: Mod downloads player skin (or Photo Slide image if provided in input) -> Uploads with text prompt and selected TTS voice to `/avatar/talking-avatar-upload` -> Polls `/job/{id}` -> Updates `AvatarScrollItem` with generated video.

### Phase 7b: Xelta Mix (Style Transfer) (Optional Phase Completed)
- **Endpoint**: `https://mxboard.stg.xelta.ai/api/mixboards`
- **Client**: `XeltaMixClient`
- **Mode Trigger**: `MixboardItem` in Output Slot.
- **Flow**: Submits style prompt and requests 8 images -> Polls `/progress` -> Fetches final board array -> Updates `MixboardItem` with multiple image URLs (viewable via scrolling in-game).

## 3. Configuration & Commands

The mod is fully configurable dynamically via in-game commands without needing a restart.
Config is stored in `xeltacraft-common.toml`.

**Commands:**
- `/xeltacraft settoken <jwt>` — Sets the Xelta authentication token.
- `/xeltacraft balance` — Queries and displays the live credit balance.
- `/xeltacraft setrefvideo <url>` — Sets the base reference MP4 used for Motion Control.
- `/xeltacraft listvoices` — Lists all available English TTS voices for GenAvatar.
- `/xeltacraft setvoice <uuid>` — Sets the voice ID for GenAvatar speech.
- `/xeltacraft status` — Shows the raw state of a nearby terminal.
- `/xeltacraft reset` — Fixes a terminal stuck in `GENERATING` state.
- `/xeltacraft help` — Prints the quick-start guide.

## 4. Polling & Error Handling Best Practices Implemented

1. **Robust JSON Parsing**: API sometimes returns single-quoted JSON errors (Python `str(dict)` output). The mod sanitizes these via `.replace("'", "\"")` before GSON parsing.
2. **Exponential/Fixed Polling**: Polling logic uses Virtual Threads to avoid hanging the server tick loop, waiting 3s to 5s between requests depending on the heaviness of the service (Images vs Video).
3. **Task Deletion Warning**: The result endpoints (e.g. `/api/tasks/result/{task_id}`) often delete the task upon returning data. The mod ensures results are fetched exactly once and stored permanently in NBT.
4. **Thread Marshalling**: All responses arriving from HTTP Virtual Threads are carefully marshalled back to the main server thread via `server.execute(...)` before modifying block entities, items, or sending packets, ensuring Minecraft world-state thread safety.

## 5. Result Verification
All integration code compiling successfully and `.jar` output provided in the build. Follow the manual Testing Guide to see the live results in a local Minecraft client.
