# XeltaCraft Auth-to-Generation Analysis

## Flow

1. The client opens `XeltaTerminalScreen`, enters a prompt/token, selects a mode, and sends `C2SGeneratePacket`.
2. `C2SGeneratePacket` validates distance, prompt presence, and mode range, then calls `XeltaTerminalBlockEntity.handleGenerateRequest`.
3. `XeltaTerminalBlockEntity` validates auth and mode-specific inputs before setting `GENERATING`.
4. The selected API client submits the job and reports progress back through `S2CStatusPacket`.
5. On success, the terminal writes the generated output URL into an item:
   - `PHOTO_LAB`: `PhotoSlideItem` with `xelta_image_url`.
   - `MOTION_CONTROL`: `FilmRollItem` with `xelta_video_url`.
   - `GEN_AVATAR`: `AvatarScrollItem` with `xelta_video_url`.
   - `XELTA_MIX`: `MixboardItem` with generated image URLs.

## Scenarios

### Auth

1. Missing token: rejected before generation and the terminal shows an error.
2. Invalid token: Xelta returns auth failure; the smoke test expects HTTP 401/403.
3. Valid token: `api_tests/test_xelta_flow.ps1` checks credits when `XELTA_TOKEN` is set.

### Generation

1. Empty prompt: blocked locally before a server packet/API call.
2. Invalid token generation: Photo Lab submit is rejected with HTTP 401/403.
3. Live generation: optional `-RunLiveGeneration` submits, polls, and prints the output URL.

### Output

1. Image output: Photo Lab result URL is stored on a generated Photo Slide.
2. Video output: Motion Control result URL is stored on a Film Roll after validating a generated Photo Slide and reference video.
3. Multi-image output: Xelta Mix URLs are stored on a Mixboard with active index 0.

## Fixes Applied

1. Added server-side mode validation before async generation starts.
2. Missing API token now updates terminal status instead of only sending chat.
3. Video mode now fails clearly when the Reference slot is empty, has a blank Photo Slide, or no reference video is configured.
4. Avatar mode now rejects a blank Photo Slide reference instead of silently falling back.
5. Realigned terminal GUI/container slot positions so the visible form matches clickable slots.
6. Added `api_tests/test_xelta_flow.ps1` for repeatable auth/generation smoke coverage.
7. Updated Photo Lab result parsing for completed-task envelopes like `{"result":{"image_url":"..."}}`.
8. Made credit balance failures explicit: `401/403` from `/users/credits` now reports token rejection, not a generic internet/API-key message.

## Commands

Compile:

```powershell
.\gradlew.bat compileJava compileClientJava
```

Project tests:

```powershell
.\gradlew.bat test
```

Smoke test without spending credits:

```powershell
powershell -ExecutionPolicy Bypass -File .\api_tests\test_xelta_flow.ps1
```

Live Photo Lab generation, polls to final output URL:

```powershell
$env:XELTA_TOKEN = "<jwt>"
powershell -ExecutionPolicy Bypass -File .\api_tests\test_xelta_flow.ps1 -RunLiveGeneration
```
