"""Build Google Play artwork from Hot Mess's existing vector mark and font.

Requires Pillow and sharp. Set NODE_BINARY and SHARP_MODULE for your installation.
"""
import os
from pathlib import Path
import subprocess
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ANDROID = HERE.parent
MASTER = ANDROID.parent / 'ios/Design/AppIcon/masters/AppIcon.svg'
FONT = ANDROID / 'app/src/main/res/font/figtree.ttf'
NODE = os.environ.get('NODE_BINARY', 'node')
SHARP = os.environ.get('SHARP_MODULE', 'sharp')


def render(source, target, width, height):
    subprocess.run([NODE, '-e',
        'const sharp=require(process.argv[1]); sharp(process.argv[2]).resize(+process.argv[4],+process.argv[5]).flatten({background:"#f6eff3"}).png().toFile(process.argv[3]);',
        SHARP, str(source), str(target), str(width), str(height)], check=True)


render(MASTER, HERE / 'app-icon.png', 512, 512)
# Keep the actual brand artwork as vectors in the editable composition.
mark = MASTER.read_text().split('>', 1)[1].rsplit('</svg>', 1)[0]
background = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
<rect width="1024" height="500" fill="#f6eff3"/>
<path d="M0 474H1024V500H0Z" fill="#2fb4ff"/>
<path d="M0 488H1024V500H0Z" fill="#ff3d9a"/>
<svg x="574" y="14" width="450" height="460" viewBox="0 0 1024 1024">{mark}</svg>
</svg>'''
(HERE / 'feature-background.svg').write_text(background)
render(HERE / 'feature-background.svg', HERE / 'feature-graphic.png', 1024, 500)
img = Image.open(HERE / 'feature-graphic.png').convert('RGB')
draw = ImageDraw.Draw(img)


def text(x, y, value, size, weight=400, color='#1a1519'):
    font = ImageFont.truetype(str(FONT), size)
    try:
        font.set_variation_by_axes([weight])
    except (OSError, ValueError):
        pass
    draw.text((x, y), value, font=font, fill=color)


text(54, 48, 'QUEER NIGHTLIFE, LIVE', 19, 700)
text(50, 101, 'Hot Mess', 88, 800)
text(54, 223, 'Where your people', 40, 650)
text(54, 273, 'are tonight.', 40, 650)
text(54, 381, 'VENUES  /  EVENTS  /  DJS', 18, 650)
img.save(HERE / 'feature-graphic.png', optimize=True)
for name, expected in [('app-icon.png', (512, 512)), ('feature-graphic.png', (1024, 500))]:
    path = HERE / name
    with Image.open(path) as asset:
        assert asset.size == expected
        assert asset.mode in ('RGB', 'RGBA')
        assert path.stat().st_size < (1 if name == 'app-icon.png' else 15) * 1024 * 1024
        print(f'{name}: {asset.size}, {path.stat().st_size:,} bytes')
