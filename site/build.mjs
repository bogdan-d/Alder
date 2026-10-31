import { cp, mkdir, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const site = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(site, '..');
const dist = path.join(site, 'dist');
const assets = path.join(dist, 'assets');

await rm(dist, { recursive: true, force: true });
await mkdir(assets, { recursive: true });

const files = [
  ['site/index.html', 'index.html'],
  ['site/styles.css', 'styles.css'],
  ['site/tokens.css', 'tokens.css'],
  ['branding/house-lights/alder_mark.svg', 'assets/alder-mark.svg'],
  ['branding/house-lights/alder_portfolio_card_product.webp', 'assets/alder-card.webp'],
  ['docs/home-dune.png', 'assets/home.png'],
  ['docs/title.png', 'assets/title.png'],
  ['docs/sources.jpg', 'assets/sources.jpg'],
  ['docs/tracks.jpg', 'assets/tracks.jpg'],
  ['docs/picker.jpg', 'assets/profiles.jpg'],
  ['app/src/main/assets/fonts/inter.ttf', 'assets/inter.ttf'],
  ['app/src/main/assets/fonts/instrument_serif.ttf', 'assets/instrument-serif.ttf'],
  ['app/src/main/assets/fonts/INTER_LICENSE.txt', 'assets/INTER_LICENSE.txt'],
  ['app/src/main/assets/fonts/INSTRUMENT_SERIF_LICENSE.txt', 'assets/INSTRUMENT_SERIF_LICENSE.txt'],
];

for (const [source, target] of files) {
  await cp(path.join(root, source), path.join(dist, target));
}

console.log(`Built ${dist}`);
