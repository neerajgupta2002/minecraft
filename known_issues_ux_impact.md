# Known Service-Layer Issues — Player Experience Impact

Findings from a code review of the XeltaCraft API/HTTP service layer
(`ai.xelta.craft.api`, `ai.xelta.craft.infra.http`). Each issue below is
framed by what the player actually sees or experiences in-game, not just
the technical defect.

---

## 1. Silent session death after a network hiccup
**Where:** `XeltaSessionManager.java:172`

**What happens technically:** The background token-refresh timer is
one-shot. If a single refresh attempt fails (even a brief network blip
or a transient server error), the timer is never re-armed and the app
never retries.

**What the player experiences:**
- Everything looks normal — the player is still shown as "logged in."
- But every subsequent action that needs the AI service (building a
  structure, spending credits, syncing progress) starts silently
  failing with auth errors.
- There's no prompt to log back in and no error explaining why nothing
  is working. The player has to figure out on their own that
  restarting the game fixes it.
- **Net effect:** intermittent, unexplained "the mod just stopped
  working" reports that only a restart resolves — a classic source of
  frustrated bug reports and bad reviews, because the actual cause
  (a transient network error minutes or hours earlier) is invisible by
  the time the player notices.

---

## 2. AI-built structures can come out broken or incomplete
**Where:** `GeminiArchitectClient.java:422-458`

**What happens technically:** The schema sent to the AI model doesn't
require houses/towers/castles to include their core fields (walls,
roof type, etc.), and the app's own validation doesn't check for this
either. A response can pass validation while being effectively empty.

**What the player experiences:**
- The player asks the in-game AI to build a house/tower/castle.
- Occasionally, what gets placed in the world is a broken, partial, or
  missing structure — floating walls, no roof, an empty footprint —
  instead of the requested building.
- Because this passes validation, there's no retry and no error
  message; the player just sees a bad result and has no idea whether
  it was their prompt, a bug, or bad luck.
- **Net effect:** inconsistent build quality that erodes trust in the
  core "AI builds things for you" feature — the mod's main selling
  point.

---

## 3. Login can fail with a confusing error for some passwords/emails
**Where:** `XeltaAuthClient.java:162`

**What happens technically:** The login/refresh request body is
hand-assembled instead of using a proper JSON library, and it doesn't
escape all characters JSON requires. Certain rare characters in a
password or email (e.g. from password managers) produce an invalid
request.

**What the player experiences:**
- A small subset of players — those whose password happens to contain
  an unusual character — simply cannot log in, or get logged out and
  can't refresh their session.
- The error they see (if any) won't explain the real cause; it'll look
  like a generic login failure, leading them to think their account or
  credentials are broken.
- **Net effect:** a handful of players are silently locked out of a
  paid/gated feature for a reason support can't easily diagnose from
  their side.

---

## 4. Session tokens can leak into log files
**Where:** `XeltaHttpClient.java:153`

**What happens technically:** Debug mode logs raw response bodies,
including the login/refresh responses that contain the player's live
access and refresh tokens, with no redaction.

**What the player experiences:**
- Not a visible in-game symptom, but a real risk: if a player enables
  debug mode to help troubleshoot an issue (e.g. at a modder's
  request) and shares their log file (Discord, a bug report, a
  support ticket), they may be unknowingly handing over live
  credentials to their account.
- **Net effect:** account-takeover risk for players who are just
  trying to be helpful by sharing diagnostic logs.

---

## 5. Rare "which login won" race condition
**Where:** `XeltaSessionManager.java:41` (also `XeltaAuthClient.java:31`,
`XeltaHttpClient.java:30`)

**What happens technically:** The session manager singleton isn't
thread-safe to initialize. Two near-simultaneous first-time calls
(e.g. game UI vs. a background network callback) can create two
separate session objects instead of one shared one.

**What the player experiences:**
- In rare startup timing scenarios, part of the mod may think the
  player is logged in while another part doesn't — e.g. the settings
  screen shows "logged in" but an action fails as if logged out, or
  vice versa.
- This is intermittent and timing-dependent, so it's the kind of bug
  that's very hard for players to reproduce or describe, and hard for
  support to reproduce too.
- **Net effect:** occasional, hard-to-explain "it says I'm logged in
  but it's not working" reports.

---

## 6. Extra hitching/stutter under heavy use (performance only)
**Where:** `XeltaHttpClient.java:189`

**What happens technically:** Every single network call spins up a
brand-new thread pool just to make one request, instead of reusing an
existing one.

**What the player experiences:**
- On its own, this is a small, mostly invisible overhead per request.
- Under bursts of activity — e.g. rapid retries during a shaky
  connection, or several AI requests queued up quickly — the extra
  allocation/cleanup work can contribute to brief stutters or
  slowdowns, especially on lower-end machines.
- **Net effect:** a minor, hard-to-notice performance drag that's more
  likely to show up as "the game stutters a bit when I use the AI
  builder" than as anything players would think to report directly.

---

## Priority read for fixing

| # | Issue | Player-facing severity | Frequency |
|---|-------|------------------------|-----------|
| 1 | Silent session death | High — breaks core feature silently | Occasional (any transient network error) |
| 2 | Incomplete AI structures | High — undermines main feature's reliability | Occasional (schema-dependent) |
| 4 | Token leak in logs | High — security/account risk | Rare (only if debug mode + log sharing) |
| 3 | Login failure for some users | Medium — blocks a subset of players entirely | Rare (specific character sets) |
| 5 | Auth race condition | Medium — confusing, hard to reproduce | Very rare (startup timing) |
| 6 | Executor churn | Low — minor performance only | Continuous but subtle |

Issues 1 and 2 most directly damage the day-to-day experience of using
the mod's core AI-building feature and should be prioritized first.
Issue 4 is lower frequency but highest risk per occurrence (account
compromise) and is cheap to fix, so it's worth fixing alongside them.
