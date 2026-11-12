# PDF de teste com Times New Roman e Arial citadas mas não embutidas (como os gerados pelo Word).
import sys
try:
    from fontTools.ttLib import TTFont
    def widths(path):
        f = TTFont(path); cmap = f.getBestCmap(); hm = f["hmtx"]; upm = f["head"].unitsPerEm
        return [round(hm[cmap[c]][0] * 1000 / upm) if c in cmap else 0 for c in range(32, 256)]
except ImportError:
    from PIL import ImageFont
    def widths(path):
        f = ImageFont.truetype(path, 1000)
        return [round(f.getlength(bytes([c]).decode("cp1252", "replace"))) for c in range(32, 256)]
L = "/usr/share/fonts/truetype/liberation/"
objs = []
def add(b): objs.append(b); return len(objs)
def font(name, flags, path):
    desc = add(b"<< /Type /FontDescriptor /FontName /%s /Flags %d /FontBBox [-600 -300 2000 1000] /ItalicAngle 0 /Ascent 891 /Descent -216 /CapHeight 662 /StemV 80 >>" % (name.encode(), flags))
    w = " ".join(map(str, widths(path))).encode()
    return add(b"<< /Type /Font /Subtype /TrueType /BaseFont /%s /FirstChar 32 /LastChar 255 /Widths [%s] /Encoding /WinAnsiEncoding /FontDescriptor %d 0 R >>" % (name.encode(), w, desc))
times = font("TimesNewRomanPSMT", 34, L + "LiberationSerif-Regular.ttf")
arial = font("ArialMT", 32, L + "LiberationSans-Regular.ttf")
text = "Reda\xe7\xe3o sem fonte embutida.".encode("latin-1")
content = b"BT /F1 28 Tf 72 700 Td (" + text + b") Tj ET BT /F2 28 Tf 72 600 Td (Arial sem fonte tambem.) Tj ET"
stream = add(b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream")
page = add(b"PAGE")
pages = add(b"<< /Type /Pages /Kids [%d 0 R] /Count 1 >>" % page)
objs[page - 1] = b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 %d 0 R /F2 %d 0 R >> >> /Contents %d 0 R >>" % (pages, times, arial, stream)
cat = add(b"<< /Type /Catalog /Pages %d 0 R >>" % pages)
out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"); offs = []
for n, o in enumerate(objs, 1):
    offs.append(len(out)); out += b"%d 0 obj\n" % n + o + b"\nendobj\n"
x = len(out)
out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1) + b"".join(b"%010d 00000 n \n" % o for o in offs)
out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, cat, x)
open(sys.argv[1], "wb").write(out)
