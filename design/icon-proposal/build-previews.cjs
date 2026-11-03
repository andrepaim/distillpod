// Rasterize the original vectors using the repository's existing browser tooling.
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('@playwright/test');

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    for (const palette of ['lavender', 'mint', 'chalk']) {
      const svg = fs.readFileSync(path.join(__dirname, 'assets', `distillpod-${palette}.svg`), 'utf8');
      for (const size of [16, 24, 32, 48, 64, 96, 192, 512, 1024]) {
        const png = await page.evaluate(async ({ svg, size }) => {
          const image = new Image();
          image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
          await image.decode();
          const canvas = document.createElement('canvas');
          canvas.width = canvas.height = size;
          canvas.getContext('2d').drawImage(image, 0, 0, size, size);
          return canvas.toDataURL('image/png').split(',')[1];
        }, { svg, size });
        fs.writeFileSync(path.join(__dirname, 'assets', `distillpod-${palette}-${size}.png`), Buffer.from(png, 'base64'));
      }
    }
    const foreground = fs.readFileSync(path.join(__dirname, 'assets', 'distillpod-foreground.svg'), 'utf8');
    const geometry = await page.evaluate(async svg => {
      const image = new Image();
      image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
      await image.decode();
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 1080;
      const ctx = canvas.getContext('2d');
      ctx.drawImage(image, 0, 0, 1080, 1080);
      const pixels = ctx.getImageData(0, 0, 1080, 1080).data;
      let radius = 0;
      for (let y = 0; y < 1080; y++) for (let x = 0; x < 1080; x++) {
        if (pixels[(y * 1080 + x) * 4 + 3] > 128) radius = Math.max(radius, Math.hypot((x + .5) / 10 - 54, (y + .5) / 10 - 54));
      }
      return { maximumRadiusDp: +radius.toFixed(3), safeCircleRadiusDp: 33, playOpeningAlpha: pixels[(570 * 1080 + 540) * 4 + 3] };
    }, foreground);
    if (geometry.maximumRadiusDp > 33 || geometry.playOpeningAlpha !== 0) throw Error(JSON.stringify(geometry));
    fs.writeFileSync(path.join(__dirname, 'assets', 'geometry-check.json'), JSON.stringify(geometry, null, 2) + '\n');
    console.log('27 PNG previews exported. Geometry:', geometry);
  } finally {
    await browser.close();
  }
})().catch(e => { console.error(e); process.exit(1); });
