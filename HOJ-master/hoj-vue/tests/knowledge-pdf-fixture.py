"""Local integration fixtures only; no user document/font is stored in Git.

Requires the operator's existing PyMuPDF/pypdf and a local CJK font. Font
subsetting keeps the synthetic PDF under the production 8 MiB limit.
"""
import base64
import io
import json
import os
import sys

import fitz
from pypdf import PdfReader, PdfWriter

font = os.environ.get("HOJ_PDF_TEST_FONT", "C:/Windows/Fonts/simsun.ttc")
book = fitz.open()
first = book.new_page()
first.insert_text((60, 80), "前缀和讲义\n静态数组先计算 S[i]=S[i-1]+a[i]，S[0]=0。", fontname="FixtureChinese", fontfile=font, fontsize=14)
book.new_page()
last = book.new_page()
last.insert_text((60, 80), "# 原始文字\n前缀和的区间和公式：S[r]-S[l-1]。\n预处理 O(n)，每次查询 O(1)。", fontname="FixtureChinese", fontfile=font, fontsize=14)
book.subset_fonts()
raw = book.tobytes(garbage=4, deflate=True)
reader = PdfReader(io.BytesIO(raw), strict=True)
texts = [page.extract_text().strip() for page in reader.pages]
assert len(raw) < 8388608 and len(texts) == 3 and texts[1] == ""
assert "前缀和讲义" in texts[0] and "S[r]-S[l-1]" in texts[2]
encrypted = PdfWriter(); encrypted.append(reader); encrypted.encrypt("synthetic-test-password")
encrypted_data = io.BytesIO(); encrypted.write(encrypted_data)
blank = PdfWriter(); blank.add_blank_page(width=595, height=842)
blank_data = io.BytesIO(); blank.write(blank_data)
if len(sys.argv) > 1:
    os.makedirs(sys.argv[1], exist_ok=True)
    for page in (0, 2):
        book[page].get_pixmap(matrix=fitz.Matrix(1.4, 1.4)).save(os.path.join(sys.argv[1], "pdf-fixture-page-%s.png" % (page + 1)))
json.dump({"pdf": base64.b64encode(raw).decode("ascii"), "encrypted": base64.b64encode(encrypted_data.getvalue()).decode("ascii"),
           "blank": base64.b64encode(blank_data.getvalue()).decode("ascii"), "texts": texts}, sys.stdout, ensure_ascii=True)
