"""Build the original, editable SVG proposal assets. Standard library only."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'assets'
OUT.mkdir(exist_ok=True)

# One compound path: the playback opening is transparent, including in monochrome.
DROP = 'M64 25C64 36 78 43 78 57C78 70.255 67.255 81 54 81C40.745 81 30 70.255 30 57C30 42 48 30 64 25Z M48 47.8C48 46.4 49.5 45.6 50.7 46.3L65.5 55.3C66.8 56.1 66.8 57.9 65.5 58.7L50.7 67.7C49.5 68.4 48 67.6 48 66.2Z'
SIGNAL = 'M31 39H41V69H31Z M47 30H58C74 30 82 40 82 54S74 78 58 78H47V65H57C65 65 69 61 69 54S65 43 57 43H47Z'
BOOKMARK = 'M33 29Q33 26 36 26H72Q75 26 75 29V79L54 67L33 79Z M45 41V56H50V41Z M58 36V56H63V36Z'
MARKS = {'drop': DROP, 'signal': SIGNAL, 'bookmark': BOOKMARK}
PALETTES = {'lavender': ('#0C0F16', '#B7B9FF'), 'mint': ('#0C0F16', '#91DCC8'), 'chalk': ('#E4E8DC', '#283C34')}

def svg(path, color, background=None, viewbox='18 18 72 72', label='DistillPod listening drop'):
    bg = f'<rect width="108" height="108" fill="{background}"/>' if background else ''
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{viewbox}" role="img" aria-label="{label}">{bg}<path fill="{color}" fill-rule="evenodd" d="{path}"/></svg>\n'

for name, path in MARKS.items():
    (OUT / f'{name}.svg').write_text(svg(path, '#B7B9FF', '#0C0F16', label=f'DistillPod {name} concept'))
for name, (bg, fg) in PALETTES.items():
    (OUT / f'distillpod-{name}.svg').write_text(svg(DROP, fg, bg))
(OUT / 'distillpod-foreground.svg').write_text(svg(DROP, '#B7B9FF', viewbox='0 0 108 108'))
(OUT / 'distillpod-monochrome.svg').write_text(svg(DROP, '#000000', viewbox='0 0 108 108'))
(OUT / 'distillpod-background.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" fill="#0C0F16"/></svg>\n')
print(f'Built {len(list(OUT.glob("*.svg")))} SVG assets in {OUT}')
