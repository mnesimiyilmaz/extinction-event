# Credits

## Earth imagery

The Earth textures in `app/src/main/assets/earth/` are cropped from NASA imagery, which is in
the public domain, by `tools/earth-textures.py`. With thanks to NASA Earth Observatory and NASA
Visible Earth (https://visibleearth.nasa.gov/).

- `day.jpg`: Blue Marble Next Generation, December 2004, topography and bathymetry
  (`world.topo.bathy.200412.3x21600x10800.jpg`; Reto Stöckli, NASA Earth Observatory).
- `aux.jpg`, red channel: Black Marble 2016 night lights (`BlackMarble_2016_3km.jpg`; NASA Earth
  Observatory, Joshua Stevens, Miguel Román).
- `aux.jpg`, green channel: Blue Marble cloud composite (`cloud_combined_8192.tif`; NASA Goddard,
  MODIS).
- `aux.jpg`, blue channel: a water mask derived from `day.jpg`.

NASA imagery does not imply NASA endorsement of this project. The imagery stays in the public
domain; the GPL covers the code and the rest of this repository.

## Regenerating the textures

Only needed after changing the camera, `impactQ`, `impactLatLon` or `northHeading` in
`tuning.json`. With the three NASA files above in one directory:

    node tools/earth-crop.mjs
    python3 -I tools/earth-textures.py <directory with the NASA files>
