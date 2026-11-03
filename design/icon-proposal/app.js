'use strict';
const drop = 'M64 25C64 36 78 43 78 57C78 70.255 67.255 81 54 81C40.745 81 30 70.255 30 57C30 42 48 30 64 25Z M48 47.8C48 46.4 49.5 45.6 50.7 46.3L65.5 55.3C66.8 56.1 66.8 57.9 65.5 58.7L50.7 67.7C49.5 68.4 48 67.6 48 66.2Z';
const marks = {
  drop,
  signal: 'M31 39H41V69H31Z M47 30H58C74 30 82 40 82 54S74 78 58 78H47V65H57C65 65 69 61 69 54S65 43 57 43H47Z',
  bookmark: 'M33 29Q33 26 36 26H72Q75 26 75 29V79L54 67L33 79Z M45 41V56H50V41Z M58 36V56H63V36Z'
};
const glyph = (path, viewBox = '18 18 72 72') => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${viewBox}" aria-hidden="true" focusable="false"><path d="${path}" fill="currentColor" fill-rule="evenodd"/></svg>`;
document.querySelectorAll('[data-icon]').forEach(el => { el.innerHTML = glyph(marks[el.dataset.icon]); });
document.querySelectorAll('[data-symbol]').forEach(el => { el.innerHTML = glyph(drop); });
document.querySelector('[data-part="drop"]').innerHTML = glyph(drop.split(' M48')[0]);
document.querySelector('[data-part="play"]').innerHTML = glyph('M36 29C36 26.6 38.7 25.3 40.7 26.5L80 50.5C82.6 52.1 82.6 55.9 80 57.5L40.7 81.5C38.7 82.7 36 81.4 36 79.1Z');

let color = 'lavender';
let shape = 'squircle';
const colors = { lavender: ['#0C0F16', '#B7B9FF'], mint: ['#0C0F16', '#91DCC8'], chalk: ['#E4E8DC', '#283C34'] };
const labels = { lavender: 'Lavender', mint: 'Mint', chalk: 'Themed', squircle: 'Squircle', circle: 'Circle', rounded: 'Rounded' };
const radii = { squircle: '26%', circle: '50%', rounded: '16%' };
// The hero and comparison stay on their recommended colors. Only review samples change.
function update() {
  document.querySelectorAll('.preview-icon, .launcher-icon, .size-samples .icon').forEach(el => {
    el.style.setProperty('--icon-bg', colors[color][0]);
    el.style.setProperty('--icon-fg', colors[color][1]);
    el.style.borderRadius = radii[shape];
  });
  document.querySelectorAll('[data-color]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.color === color)));
  document.querySelectorAll('[data-shape]').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.shape === shape)));
  const name = `${labels[color]} / ${labels[shape]}`;
  document.getElementById('preview-name').textContent = name;
  document.getElementById('preview-status').textContent = `${name} applied to the preview, launcher, and small-size samples.`;
}
document.getElementById('colors').addEventListener('click', e => {
  const button = e.target.closest('[data-color]');
  if (button) { color = button.dataset.color; update(); }
});
document.getElementById('shapes').addEventListener('click', e => {
  const button = e.target.closest('[data-shape]');
  if (button) { shape = button.dataset.shape; update(); }
});
document.getElementById('safe-zone').addEventListener('change', e => {
  document.querySelector('.workbench').classList.toggle('show-safe', e.target.checked);
  document.getElementById('safe-note').textContent = e.target.checked
    ? 'The dashed circle is the central 66 dp safe area in a 108 dp layer. The complete mark sits inside it.'
    : 'Launcher masks are illustrative. Android applies the final device shape.';
});
