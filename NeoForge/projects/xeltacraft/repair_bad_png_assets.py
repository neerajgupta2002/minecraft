import os
import struct
import zlib


def png_bytes(width, height, rgba_pixel):
    def chunk(name, data):
        return struct.pack(
            ">I", len(data)
        ) + name + data + struct.pack(
            ">I", zlib.crc32(name + data) & 0xFFFFFFFF
        )

    raw = b"".join(b"\x00" + rgba_pixel(x, y) for y in range(height) for x in range(width))
    compressed = zlib.compress(raw)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(
        ">IIBBBBB", width, height, 8, 6, 0, 0, 0
    ))
    png += chunk(b"IDAT", compressed)
    png += chunk(b"IEND", b"")
    return png


files = {
    "src/main/resources/assets/xeltacraft/textures/item/film_roll.png": (64, 64, lambda x, y: b"\x2E\x8B\x57\xFF"),
    "src/main/resources/assets/xeltacraft/textures/item/photo_slide.png": (64, 64, lambda x, y: b"\x3E\xC2\xE0\xFF"),
    "src/main/resources/assets/xeltacraft/textures/item/photo_slide_error.png": (64, 64, lambda x, y: b"\xE0\x3E\x3E\xFF"),
    "src/main/resources/assets/xeltacraft/textures/item/photo_slide_loading.png": (64, 64, lambda x, y: b"\xE0\xD0\x3E\xFF"),
    "src/main/resources/assets/xeltacraft/textures/block/xelta_terminal_top.png": (64, 64, lambda x, y: b"\x70\x4E\xB5\xFF"),
    "src/main/resources/assets/xeltacraft/textures/block/xelta_terminal_side.png": (64, 64, lambda x, y: b"\x30\x30\x30\xFF"),
    "src/main/resources/assets/xeltacraft/textures/block/xelta_terminal_front.png": (64, 64, lambda x, y: b"\x2E\x57\xB1\xFF"),
    "src/main/resources/assets/xeltacraft/textures/gui/xelta_terminal_gui.png": (128, 64, lambda x, y: b"\x30\x50\x90\xFF"),
}

for path, (width, height, pixel) in files.items():
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png_bytes(width, height, pixel))
    print("wrote", path)
