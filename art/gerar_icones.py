"""
Gera os ícones do app a partir de art/icone-original.jpg.

Recorta o farol dourado, separa os traços do fundo creme (vira PNG transparente) e monta:
  - ícone adaptativo (Android 8+): fundo creme + farol + camada monocromática (ícones temáticos, Android 13+);
  - ícones quadrado arredondado e redondo para Android 6 e 7.
Uso (na raiz do projeto): python3 art/gerar_icones.py   — requer Pillow.
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "art" / "icone-original.jpg"
RES = ROOT / "app" / "src" / "main" / "res"
ART_BOX = (1175 - 8, 345 - 8, 1642 + 8, 981 + 8)  # área do farol + livros na imagem original
CREAM = (247, 240, 222)
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def extract_art() -> Image.Image:
    """Traços dourados com transparência: o canal azul separa bem o dourado do creme."""
    crop = Image.open(SOURCE).convert("RGB").crop(ART_BOX)
    out = Image.new("RGBA", crop.size)
    src, dst = crop.load(), out.load()
    for y in range(crop.height):
        for x in range(crop.width):
            r, g, b = src[x, y]
            a = max(0.0, min(1.0, (212 - b) / 95))
            if a <= 0.02:
                dst[x, y] = (0, 0, 0, 0)
                continue
            # Tira a mistura com o fundo creme para a borda não ficar "suja" em outro fundo.
            rgb = tuple(max(0, min(255, round((c - (1 - a) * bg) / a))) for c, bg in zip((r, g, b), CREAM))
            dst[x, y] = (*rgb, round(a * 255))
    return out


def place(art: Image.Image, size: int, art_height: float, color=None, background=None, shape=None) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    if background:
        mask = Image.new("L", (size, size), 0)
        draw = ImageDraw.Draw(mask)
        if shape == "circle":
            draw.ellipse((0, 0, size - 1, size - 1), fill=255)
        else:
            draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=round(size * 0.22), fill=255)
        canvas.paste(Image.new("RGBA", (size, size), background + (255,)), (0, 0), mask)
    h = round(size * art_height)
    w = round(art.width * h / art.height)
    scaled = art.resize((w, h), Image.LANCZOS)
    if color:
        alpha = scaled.getchannel("A")
        scaled = Image.new("RGBA", scaled.size, color + (255,))
        scaled.putalpha(alpha)
    canvas.alpha_composite(scaled, ((size - w) // 2, (size - h) // 2))
    return canvas


def main():
    art = extract_art()
    art.save(ROOT / "art" / "farol.png")
    for name, scale in DENSITIES.items():
        folder = RES / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        legacy, adaptive = round(48 * scale), round(108 * scale)
        # Camada da frente do adaptativo: 108dp, com o desenho dentro da zona segura central.
        place(art, adaptive, 56 / 108).save(folder / "ic_launcher_foreground.png")
        place(art, adaptive, 56 / 108, color=(255, 255, 255)).save(folder / "ic_launcher_monochrome.png")
        place(art, legacy, 0.70, background=CREAM).save(folder / "ic_launcher.png")
        place(art, legacy, 0.62, background=CREAM, shape="circle").save(folder / "ic_launcher_round.png")
    place(art, 512, 0.70, background=CREAM).save(ROOT / "art" / "icone-512.png")


if __name__ == "__main__":
    main()
