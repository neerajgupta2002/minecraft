import json
import sys
import os
import urllib.request
import urllib.error

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

# --- CONFIGURATION ------------------------------------------------------------
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")
GEMINI_MODEL   = "gemini-2.5-flash"

# Test parameters — use small 3x3 to keep token count low for testing
TEST_PROMPT   = "a small stone cottage with a wooden door"
BLOCK_SIZE    = 3   # 3x3 footprint = fewer blocks = smaller JSON
MAX_COLORS    = 6

# --- DYNAMIC SYSTEM PROMPT (exact copy from GeminiArchitectClient.java) ------
SYSTEM_PROMPT_TEMPLATE = """\
You are the intent understanding engine for the X-DAVE AI-Native Voxel Generation Engine.
Your task is to analyze a natural language prompt and return a Semantic Object Graph.

The structure is described as: "{prompt}"

You must apply these constraints:
- The structure footprint must fit within {block_size} x {block_size} blocks.
- The overall scene should suggest a style.
- The 'scale' of the scene should be determined (e.g. small, medium, large).

Output ONLY a valid JSON object matching this schema:
{{
  "scene": {{
    "objects": [
      {{
        "type": "object_type_name",
        "position": "center/left/right/etc",
        "scale": 1.0,
        "material": "optional_material_hint"
      }}
    ],
    "style": "style_name",
    "scale": "overall_scale"
  }}
}}

Do NOT output blocks or coordinates. Output ONLY valid JSON. Do NOT include markdown code blocks, explanations, or any other text."""

# --- TEST LOGIC ---------------------------------------------------------------

def build_request_body(system_prompt: str) -> dict:
    return {
        "system_instruction": {
            "parts": [{"text": system_prompt}]
        },
        "generationConfig": {
            "temperature": 0.1,
            "maxOutputTokens": 65536   # Gemini 2.5-flash supports up to 64K output tokens
        },
        "contents": [
            {
                "role": "user",
                "parts": [
                    {"text": "Generate the Minecraft block placement blueprint now. Output ONLY the JSON array."}
                ]
            }
        ]
    }

def clean_response(text: str) -> str:
    text = text.strip()
    if text.startswith("```"):
        lines = text.split("\n")
        cleaned = []
        for line in lines[1:]:
            if line.startswith("```"):
                break
            cleaned.append(line)
        text = "\n".join(cleaned).strip()
    return text

def run_test():
    if not GEMINI_API_KEY:
        print("[FAIL] No API key set! Check your .env file.")
        sys.exit(1)

    system_prompt = SYSTEM_PROMPT_TEMPLATE.format(
        prompt=TEST_PROMPT,
        block_size=BLOCK_SIZE,
        max_colors=MAX_COLORS
    )

    print(f"[SEND] Sending to Gemini ({GEMINI_MODEL})...")
    print(f"    Prompt:     '{TEST_PROMPT}'")
    print(f"    Block size: {BLOCK_SIZE}x{BLOCK_SIZE}")
    print(f"    Max colors: {MAX_COLORS}")
    print(f"    maxOutputTokens: 65536")
    print()

    url = f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent?key={GEMINI_API_KEY}"
    body = json.dumps(build_request_body(system_prompt)).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST"
    )

    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8")
        print(f"[FAIL] HTTP {e.code} error from Gemini:")
        try:
            err_json = json.loads(error_body)
            print(f"    {err_json.get('error', {}).get('message', error_body[:300])}")
        except Exception:
            print(f"    {error_body[:300]}")
        sys.exit(1)

    # Parse response
    response_json = json.loads(raw)
    candidates = response_json.get("candidates", [])
    if not candidates:
        print("[FAIL] No candidates in Gemini response:")
        print(json.dumps(response_json, indent=2)[:1000])
        sys.exit(1)

    # Check finish reason
    finish_reason = candidates[0].get("finishReason", "UNKNOWN")
    print(f"[INFO] finishReason: {finish_reason}")
    if finish_reason == "MAX_TOKENS":
        print("[WARN] Response was truncated by token limit — increasing maxOutputTokens might help further.")

    text = candidates[0]["content"]["parts"][0]["text"]
    blueprint_text = clean_response(text)

    print(f"[INFO] Response length: {len(blueprint_text)} chars")
    print()

    # Validate JSON
    try:
        blueprint = json.loads(blueprint_text)
    except json.JSONDecodeError as e:
        print(f"[FAIL] Gemini returned invalid JSON: {e}")
        print(f"    Raw response (last 300 chars to see truncation point):")
        print(f"    ...{blueprint_text[-300:]}")
        sys.exit(1)

    if not isinstance(blueprint, dict) or "scene" not in blueprint:
        print(f"[FAIL] Expected a JSON object with 'scene', got: {type(blueprint).__name__}")
        sys.exit(1)

    # Report results
    print(f"[PASS] Parsed Semantic Object Graph")
    print()

    objects = blueprint["scene"].get("objects", [])
    print(f"Found {len(objects)} semantic objects:")
    for obj in objects:
        print(f"    - {obj.get('type')} (pos: {obj.get('position')}, material: {obj.get('material')})")

    print()
    print("-" * 60)
    print("RESULT: PASS — Gemini API is working correctly!")
    print("        The mod will use this same call to place blocks in Minecraft.")
    print()
    print("Sample Output:")
    print(json.dumps(blueprint, indent=2))

if __name__ == "__main__":
    run_test()
