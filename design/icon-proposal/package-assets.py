"""Package proposal files and unapplied Android resource candidates."""
from pathlib import Path
import runpy
import zipfile

ROOT = Path(__file__).resolve().parent
ART = runpy.run_path(str(ROOT / 'build-assets.py'))
ASSETS = ROOT / 'assets'
ANDROID = ASSETS / 'android-candidate' / 'res'
DRAWABLE = ANDROID / 'drawable'
ADAPTIVE = ANDROID / 'mipmap-anydpi-v26'
DRAWABLE.mkdir(parents=True, exist_ok=True)
ADAPTIVE.mkdir(parents=True, exist_ok=True)
for name, color in [('foreground', '#B7B9FF'), ('monochrome', '#FFFFFF')]:
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="{color}" android:fillType="evenOdd" android:pathData="{ART['DROP']}" />
</vector>
'''
    (DRAWABLE / f'ic_launcher_{name}.xml').write_text(xml)
(DRAWABLE / 'ic_launcher_background.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#0C0F16" android:pathData="M0,0H108V108H0Z" />
</vector>
''')
(ADAPTIVE / 'ic_launcher.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
''')
with zipfile.ZipFile(ASSETS / 'distillpod-icon-proposal.zip', 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(ROOT / 'README.md', 'README.md')
    for p in sorted(ASSETS.rglob('*')):
        if p.is_file() and p.suffix in ['.svg', '.png', '.xml', '.json', '.ttf', '.txt']:
            z.write(p, Path('assets') / p.relative_to(ASSETS))
print('Packaged original vectors, PNG previews, geometry receipt, and Android candidates.')
