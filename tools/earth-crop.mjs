// Writes earthCrop (lon0, lon1, lat0, lat1) into app/src/main/assets/config/tuning.json: every surface point the
// screen can show, plus cloud drift and a 1° pad. Rerun, then earth-textures.py, after changing
// the camera, impactQ, impactLatLon or northHeading.
import { readFileSync, writeFileSync } from 'node:fs';
import { visibleBounds } from './earth-view.js';

const path = new URL('../app/src/main/assets/config/tuning.json', import.meta.url);
const tuning = JSON.parse(readFileSync(path, 'utf8'));
const b = visibleBounds(tuning);
const m = (tuning.cloudWind * tuning.cloudCycle) / 2 + 1;
tuning.earthCrop = [Math.floor(b.lon0 - m), Math.ceil(b.lon1 + m), Math.floor(b.lat0 - 1), Math.ceil(b.lat1 + 1)];
const lines = Object.entries(tuning).map(([k, v]) => `  ${JSON.stringify(k)}: ${JSON.stringify(v)}`);
writeFileSync(path, `{\n${lines.join(',\n')}\n}\n`);
console.log('earthCrop', tuning.earthCrop);
