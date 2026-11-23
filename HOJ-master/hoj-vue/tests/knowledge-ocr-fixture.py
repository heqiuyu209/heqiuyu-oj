"""Original synthetic scans for local OCR verification. No user documents.

The font path is an operator-supplied test-only local font, never copied into
the image or served by the product. Requires Pillow/PyMuPDF on the test host.
"""
import base64
import io
import json
import sys

from PIL import Image, ImageDraw, ImageFont
from pypdf import PdfReader, PdfWriter
import fitz


def picture(text):
    image = Image.new("RGB", (1500, 1400), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(sys.argv[1], 64)
    draw.multiline_text((80, 170), text, font=font, fill="black", spacing=35)
    return image


first = picture("前缀和算法\nPrefix sum\n区间求和需要两个前缀值相减。\nS[r] - S[l-1]")
last = picture("最短路径算法\nDijkstra requires\nnonnegative edge weights.")
output = io.BytesIO()
doc = fitz.open()
def scan_page(image, rectangle):
    buffer = io.BytesIO(); image.save(buffer, "PNG")
    page = doc.new_page(width=595, height=842)
    page.insert_image(fitz.Rect(*rectangle), stream=buffer.getvalue())
    return page
scan_page(first, (0, 0, 595, 842))
doc.new_page(width=595, height=842)  # Intentionally empty physical page 2.
mixed = scan_page(last, (0, 112, 595, 812))
mixed.insert_text((45, 52), "MixedHeader", fontsize=24)  # Native text must not skip the image body.
raw = doc.tobytes(garbage=4, deflate=True)
encrypted = PdfWriter(); encrypted.append(PdfReader(io.BytesIO(raw))); encrypted.encrypt("synthetic-password")
encrypted_bytes = io.BytesIO(); encrypted.write(encrypted_bytes)
blank = PdfWriter(); blank.add_blank_page(width=595, height=842)
blank_bytes = io.BytesIO(); blank.write(blank_bytes)
only = io.BytesIO(); first.save(only, "PDF", resolution=200)
encode = lambda data: base64.b64encode(data).decode("ascii")
print(json.dumps({"pdf": encode(raw), "encrypted": encode(encrypted_bytes.getvalue()),
                  "blank": encode(blank_bytes.getvalue()), "imageOnly": encode(only.getvalue())}))
