"""
Standalone test harness for the XeltaCraft World-Build pipeline.

This is a faithful port of ai.xelta.craft.api.GeminiArchitectClient.java — same system
prompt, same generationConfig (temperature, structured-output responseSchema), same
retry-on-malformed-response logic, same 180s timeout, same image-format sniffing. Kept
in sync by hand; if you change the prompt/schema/timeout in the Java client, mirror the
change here too so this script keeps telling the truth about what the mod actually sends.

Lets you reproduce a generation (including the reference-image path) in a few seconds
of iteration instead of a full Minecraft launch.

Usage:
    python test_gemini_api.py "a small stone cottage with a wooden door"
    python test_gemini_api.py "build this house" --size 5 --image path/to/reference.jpg
    python test_gemini_api.py "a watchtower" --size 3 --api-key YOUR_KEY
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

# --- Load .env file (no extra libraries needed) -------------------------------
def load_env(path=".env"):
    if os.path.exists(path):
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k.strip(), v.strip())

load_env()

GEMINI_MODEL = "gemini-2.5-flash"

# Deliberately NOT the mod's general HTTP timeout — see GeminiArchitectClient.GEMINI_TIMEOUT_SECONDS.
# A reference-image request was being cut off at 60s (a shared, shorter timeout); this mirrors
# the fix, not the old bug.
GEMINI_TIMEOUT_SECONDS = 180
MAX_ATTEMPTS = 3

# --- System prompt — exact copy of GeminiArchitectClient.SYSTEM_PROMPT_TEMPLATE --------------
# Uses plain str.replace() (not str.format()) on purpose: the prompt's JSON schema examples are
# full of literal '{' '}' characters that would collide with .format()'s placeholder syntax.
SYSTEM_PROMPT_TEMPLATE = """You are a Master Architect for the X-DAVE AI-Native Voxel Generation Engine. You design highly aesthetic, structurally sound, and beautiful buildings.
Your task is to analyze a natural language prompt and return a Semantic Object Graph.

The structure is described as: "{prompt}"

You must apply these constraints:
- The structure footprint must fit within {block_size} x {block_size} blocks.
- The overall scene should suggest a style. If the user's inventory contains blocks, you MUST set the 'style' and 'material' fields to exact block IDs from their inventory (e.g., 'minecraft:cobblestone') to ensure you use their available resources.
- The 'scale' of the scene should be determined (e.g. small, medium, large).
- IMPORTANT: Use pre-designed architectural macros whenever possible by setting 'type' to exactly 'gothic_arch' or 'watchtower_roof'.

Output ONLY a valid JSON object matching this schema:
{
  "scene": {
    "objects": [
      {
        "type": "object_type_name_or_prefab",
        "position": "center/left/right/north/south/east/west",
        "scale": 1.0,
        "material": "optional_material_hint_or_prefab_id"
      }
    ],
    "style": "style_name",
    "scale": "overall_scale"
  },
  "house": null_or_object_below,
  "tower": null_or_object_below,
  "castle": null_or_object_below
}

Populate AT MOST ONE of 'house', 'tower', 'castle' — whichever matches the request — and leave the other two null. For every other structure type (walls, roofs, furniture, decorations, anything else) leave all three null.

If the request describes a house/building/cottage, populate 'house' with real per-wall detail:
{
  "width": blocks_along_x_within_footprint,
  "depth": blocks_along_z_within_footprint,
  "wallHeight": 3_to_5,
  "pitchHeight": 2_to_4,
  "roofType": "GABLE|HIP|FLAT",
  "walls": [
    { "facing": "NORTH|SOUTH|EAST|WEST", "openings": [ { "type": "DOOR|WINDOW", "offset": blocks_from_wall_start } ] }
  ],
  "annex": null_or_object_below
}
Include exactly one wall entry per side (NORTH, SOUTH, EAST, WEST) that has an opening — omit a side entirely if it has no door/window. Exactly one wall in the whole house should have a DOOR opening (the entrance); any wall may have WINDOW openings. 'offset' must be strictly between 0 and that wall's length (width for NORTH/SOUTH, depth for EAST/WEST) minus 1.

Choose 'roofType' to match the request: 'GABLE' for a classic two-sided pitched roof (cottages, cabins, farmhouses — this is the default when the request doesn't imply anything else), 'HIP' for a roof that slopes down on all four sides instead of having flat gable ends (bungalows, symmetric or villa-style homes), or 'FLAT' for a flat modern roof, optionally with a low parapet ledge (modern, urban, or minimalist builds).

Only populate 'annex' when the request implies an attached porch, wing, sunroom, garage, or otherwise asymmetric/L-shaped footprint (e.g. "a cottage with a small porch", "an L-shaped farmhouse") — leave it null for a plain single-rectangle house. When populated:
{
  "width": blocks_along_x, "depth": blocks_along_z, "wallHeight": 2_to_4,
  "offsetX": blocks_from_main_house_origin, "offsetZ": blocks_from_main_house_origin,
  "walls": [ same_shape_as_the_main_houses_walls_array ]
}
The annex is a second room positioned relative to the main house's (0,0) corner — pick offsetX/offsetZ so it sits FLUSH against one side of the main house rather than floating away from it (e.g. offsetX = the main house's width to attach an annex to its east side, offsetZ = 0 to align its north edge with the main house's north edge). Keep the annex smaller than the main house.

If the request describes a tower/watchtower/spire, populate 'tower':
{
  "radius": 2_to_8,
  "height": 5_to_25,
  "crenellated": true_or_false,
  "roofHeight": 2_to_8
}

If the request describes a castle/keep/fortress, populate 'castle':
{
  "width": blocks_along_x_within_footprint,
  "depth": blocks_along_z_within_footprint,
  "wallHeight": 3_to_6,
  "towerRadius": 2_to_5,
  "towerHeight": 8_to_20,
  "gateWall": "NORTH|SOUTH|EAST|WEST",
  "gateOffset": blocks_from_that_walls_start
}
A castle always gets a real cylindrical tower at each of its 4 corners automatically — do not describe the towers as separate objects.

Do NOT output blocks or coordinates. Output ONLY valid JSON. Do NOT include markdown code blocks, explanations, or any other text."""


# --- Structured-output schema — exact mirror of GeminiArchitectClient.buildResponseSchema() ---
def type_schema(t):
    return {"type": t}


def build_response_schema():
    scene_object_schema = {
        "type": "OBJECT",
        "properties": {
            "type": type_schema("STRING"),
            "position": type_schema("STRING"),
            "scale": type_schema("NUMBER"),
            "material": type_schema("STRING"),
        },
        "required": ["type", "position", "scale", "material"],
    }

    scene_schema = {
        "type": "OBJECT",
        "properties": {
            "objects": {"type": "ARRAY", "items": scene_object_schema},
            "style": type_schema("STRING"),
            "scale": type_schema("STRING"),
        },
        "required": ["objects", "style", "scale"],
    }

    opening_schema = {
        "type": "OBJECT",
        "properties": {"type": type_schema("STRING"), "offset": type_schema("INTEGER")},
        "required": ["type", "offset"],
    }
    walls_array_schema = {
        "type": "ARRAY",
        "items": {
            "type": "OBJECT",
            "properties": {
                "facing": type_schema("STRING"),
                "openings": {"type": "ARRAY", "items": opening_schema},
            },
            "required": ["facing", "openings"],
        },
    }

    annex_schema = {
        "type": "OBJECT",
        "nullable": True,
        "properties": {
            "width": type_schema("INTEGER"),
            "depth": type_schema("INTEGER"),
            "wallHeight": type_schema("INTEGER"),
            "offsetX": type_schema("INTEGER"),
            "offsetZ": type_schema("INTEGER"),
            "walls": walls_array_schema,
        },
    }

    house_schema = {
        "type": "OBJECT",
        "nullable": True,
        "properties": {
            "width": type_schema("INTEGER"),
            "depth": type_schema("INTEGER"),
            "wallHeight": type_schema("INTEGER"),
            "pitchHeight": type_schema("INTEGER"),
            "roofType": type_schema("STRING"),
            "walls": walls_array_schema,
            "annex": annex_schema,
        },
    }

    tower_schema = {
        "type": "OBJECT",
        "nullable": True,
        "properties": {
            "radius": type_schema("INTEGER"),
            "height": type_schema("INTEGER"),
            "crenellated": type_schema("BOOLEAN"),
            "roofHeight": type_schema("INTEGER"),
        },
    }

    castle_schema = {
        "type": "OBJECT",
        "nullable": True,
        "properties": {
            "width": type_schema("INTEGER"),
            "depth": type_schema("INTEGER"),
            "wallHeight": type_schema("INTEGER"),
            "towerRadius": type_schema("INTEGER"),
            "towerHeight": type_schema("INTEGER"),
            "gateWall": type_schema("STRING"),
            "gateOffset": type_schema("INTEGER"),
        },
    }

    return {
        "type": "OBJECT",
        "properties": {
            "scene": scene_schema,
            "house": house_schema,
            "tower": tower_schema,
            "castle": castle_schema,
        },
        "required": ["scene"],
    }


# --- Image mime sniffing — mirrors GeminiArchitectClient.detectImageMimeType() ---------------
def detect_image_mime_type(data: bytes) -> str:
    if len(data) >= 4 and data[0:4] == b"\x89PNG":
        return "image/png"
    if len(data) >= 3 and data[0:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if len(data) >= 12 and data[0:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return "image/png"


def build_request_body(system_prompt: str, image_bytes: bytes | None) -> dict:
    import base64

    parts = [{"text": "Generate the Semantic Object Graph now. Output ONLY the JSON object."}]
    if image_bytes:
        parts.append({
            "inline_data": {
                "mime_type": detect_image_mime_type(image_bytes),
                "data": base64.b64encode(image_bytes).decode("ascii"),
            }
        })

    return {
        "system_instruction": {"parts": [{"text": system_prompt}]},
        "generationConfig": {
            "temperature": 0.7,
            "maxOutputTokens": 65536,
            "responseMimeType": "application/json",
            "responseSchema": build_response_schema(),
        },
        "contents": [{"role": "user", "parts": parts}],
    }


def validate_scene_shape(blueprint) -> str | None:
    """Mirrors GeminiArchitectClient.validateSceneShape() — returns None if valid."""
    if not isinstance(blueprint, dict):
        return "response is not a JSON object"
    scene = blueprint.get("scene")
    if not isinstance(scene, dict):
        return "missing a 'scene' object"
    objects = scene.get("objects")
    if not isinstance(objects, list):
        return "missing a 'scene.objects' array"
    if len(objects) == 0:
        return "'scene.objects' was empty"
    return None


def call_gemini(api_key: str, prompt: str, block_size: int, image_bytes: bytes | None,
                 attempt: int = 1, previous_error: str | None = None):
    system_prompt = SYSTEM_PROMPT_TEMPLATE.replace("{prompt}", prompt).replace("{block_size}", str(block_size))
    if previous_error:
        system_prompt += (
            f"\n\nYour previous response could not be used: {previous_error}"
            "\nReturn ONLY the JSON object described above — no other text, no markdown fences."
        )

    label = "Sending to Gemini" if attempt == 1 else f"Retrying Gemini (attempt {attempt}/{MAX_ATTEMPTS})"
    print(f"[SEND] {label} ({GEMINI_MODEL})...")

    url = f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent?key={api_key}"
    body = json.dumps(build_request_body(system_prompt, image_bytes)).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")

    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=GEMINI_TIMEOUT_SECONDS) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8")
        try:
            message = json.loads(error_body).get("error", {}).get("message", error_body[:300])
        except Exception:
            message = error_body[:300]
        print(f"[FAIL] HTTP {e.code} from Gemini: {message}")
        sys.exit(1)
    except urllib.error.URLError as e:
        elapsed = time.monotonic() - start
        print(f"[FAIL] Request failed after {elapsed:.1f}s: {e.reason}")
        if elapsed >= GEMINI_TIMEOUT_SECONDS - 1:
            print(f"        This is a timeout at the {GEMINI_TIMEOUT_SECONDS}s cap - the exact failure mode")
            print(f"        this script exists to reproduce and diagnose.")
        sys.exit(1)

    elapsed = time.monotonic() - start
    response_json = json.loads(raw)
    candidates = response_json.get("candidates", [])
    if not candidates:
        print("[FAIL] No candidates in Gemini response:")
        print(json.dumps(response_json, indent=2)[:1000])
        sys.exit(1)

    finish_reason = candidates[0].get("finishReason", "UNKNOWN")
    text = candidates[0]["content"]["parts"][0]["text"].strip()
    if text.startswith("```"):
        lines = [l for l in text.split("\n")[1:] if not l.startswith("```")]
        text = "\n".join(lines).strip()

    print(f"[INFO] Responded in {elapsed:.1f}s - finishReason={finish_reason}, {len(text)} chars")

    try:
        blueprint = json.loads(text)
    except json.JSONDecodeError as e:
        shape_error = f"not valid JSON ({e})"
    else:
        shape_error = validate_scene_shape(blueprint)

    if shape_error is None:
        return blueprint

    print(f"[WARN] Attempt {attempt}/{MAX_ATTEMPTS} produced an invalid response: {shape_error}")
    if attempt >= MAX_ATTEMPTS:
        print(f"[FAIL] Gemini did not return a valid blueprint after {MAX_ATTEMPTS} attempts ({shape_error})")
        sys.exit(1)
    return call_gemini(api_key, prompt, block_size, image_bytes, attempt + 1, shape_error)


def summarize(blueprint: dict):
    print()
    print("-" * 60)
    print("RESULT: PASS")
    print()

    for key in ("house", "tower", "castle"):
        val = blueprint.get(key)
        if val:
            print(f"Routed to: '{key}' (parametric generator)")
            print(json.dumps(val, indent=2))
            print()

    objects = blueprint.get("scene", {}).get("objects", [])
    if objects:
        print(f"'scene.objects' - {len(objects)} entries (grammar-engine/prefab path):")
        for obj in objects:
            print(f"    - {obj.get('type')} (pos: {obj.get('position')}, material: {obj.get('material')})")

    print()
    print("Full response:")
    print(json.dumps(blueprint, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("prompt", nargs="?", default="a small stone cottage with a wooden door")
    parser.add_argument("--size", type=int, default=3, help="footprint (e.g. 3 for 3x3, matches the Slide Size selector)")
    parser.add_argument("--image", type=str, default=None, help="path to a reference image (png/jpg/webp) to attach")
    parser.add_argument("--api-key", type=str, default=os.environ.get("GEMINI_API_KEY", ""))
    args = parser.parse_args()

    if not args.api_key:
        print("[FAIL] No API key set! Pass --api-key or set GEMINI_API_KEY in your .env file.")
        sys.exit(1)

    image_bytes = None
    if args.image:
        if not os.path.exists(args.image):
            print(f"[FAIL] Reference image not found: {args.image}")
            sys.exit(1)
        with open(args.image, "rb") as f:
            image_bytes = f.read()
        mime = detect_image_mime_type(image_bytes)
        print(f"[INFO] Attaching reference image: {args.image} ({len(image_bytes)} bytes, sniffed as {mime})")

    print(f"[INFO] Prompt:     '{args.prompt}'")
    print(f"[INFO] Block size: {args.size}x{args.size}")
    print(f"[INFO] Timeout:    {GEMINI_TIMEOUT_SECONDS}s")
    print()

    blueprint = call_gemini(args.api_key, args.prompt, args.size, image_bytes)
    summarize(blueprint)


if __name__ == "__main__":
    main()
