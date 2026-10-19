# Street maps and offline packs

Fly and Plan show OpenStreetMap street maps automatically when internet is available and no local basemap or imagery pack is installed. You do not need a Google, Mapbox or OpenStreetMap account, payment details or map key. Flight Blender setup and administrator approval are separate.

## Start with a map

1. Connect the Android device to Wi-Fi or mobile data.
2. Open **Fly**. If initial device setup opens, complete it with your coordinator's private setup. Tap the small map to make it larger.
3. Allow location permission to help centre the map near you. You can also pan and zoom manually. No location fix means the map initially shows a world view; it does not select a flight area for you.
4. Open **Plan** to draw and review the operation area. Check that the map matches the intended location before drawing.
5. In **Device setup → Maps**, choose the appropriate source:

| Choice | Behaviour |
|---|---|
| **Automatic: prefer offline packs** (default) | Uses installed basemap/imagery files; otherwise loads online streets. |
| **Online street maps** | Uses online streets even when offline basemap/imagery files are installed. Useful outside an installed pack's coverage. |
| **Offline packs only** | Uses installed files without adding an online tile source. Missing files show a setup hint. |

Automatic mode chooses based on which files are installed, not the current viewport or a file's geographic bounds. If you leave a pack's coverage, select online streets or import a pack for the new area.

Map data credits remain visible in the large map and inset. Online source credits link to the provider's attribution page. Map settings, downloaded packs and existing flight overlays are retained when switching sources.

## Prepare for no internet

Online caching may retain recently viewed tiles, but it is not a downloaded region and cannot guarantee coverage. Android may clear the cache.

1. Ask the coordinator for approved **basemap**, **imagery** and, if needed, **terrain** PMTiles files covering the operation area.
2. Open **Device setup → Maps** and import the supplied files, or use the coordinator's configured pack downloads.
3. Choose **Offline packs only** and review the entire planned area before travel.
4. Confirm the required detail and terrain data are available. A street map alone does not provide terrain elevations, satellite photography, obstacle clearance or permission to fly.

Do not download or scrape a region from the public OSM tile server for offline use. Offline packs use separate sources licensed for that purpose. The [design guide](12-dji-app-design.md#zones-and-maps) describes the pack format for administrators.

## Troubleshooting

| What you see | What to do |
|---|---|
| **Loading street map…** | Allow time for the connection. Confirm the device has usable internet, including any Wi-Fi sign-in page. |
| **No internet for street maps** | Restore internet or use an installed offline pack. Some cached tiles may still appear. |
| **Some map tiles unavailable** | Check internet and tap the message to retry. The provider may be unavailable. |
| **No offline map** | Tap the message to open map setup. Import a pack or select online streets. |
| Blank area with an installed pack | Check the pack covers the location. Automatic mode does not switch based on coverage; choose online streets if connected. |
| Streets but no aerial imagery | The default layer is a street map. Import the lab's imagery pack for imagery. |
| No terrain height | Import the appropriate terrain pack. Street tiles contain no elevation model. |

## Español: mapas de calles y uso sin conexión

1. Conecte el dispositivo a internet. Los mapas de calles de OpenStreetMap se cargan automáticamente si no hay un paquete local instalado. No necesita una cuenta ni una clave de mapas.
2. En **Device setup → Maps**, mantenga **Automatic: prefer offline packs** para dar preferencia a los archivos locales. Seleccione **Online street maps** para ver calles fuera de la cobertura de un paquete instalado.
3. Para trabajar sin internet, solicite a su coordinador los archivos PMTiles de su zona, impórtelos y elija **Offline packs only**. Revise toda la zona antes de salir. La caché de mapas en línea no garantiza cobertura sin conexión.
4. Si aparece **No offline map**, pulse el mensaje para abrir la configuración. Si faltan mapas en línea, compruebe internet y pulse el mensaje de error para reintentar.

Los mapas de calles no incluyen imágenes satelitales ni alturas del terreno. Estas requieren paquetes separados. El proveedor recibe su dirección IP y los mosaicos de la zona que está viendo; la aplicación no adjunta credenciales de Flight Blender a estas solicitudes. No hay una función para descargar regiones desde el servidor público de OSM. La disponibilidad de ese servicio no está garantizada. Un mapa no autoriza un vuelo.

## Why maps were blank before

The earlier app used MapLibre to render local PMTiles files. It included neither an offline region nor an online source, so a fresh install showed the background and overlays. MapLibre remains the renderer; this release adds online streets as the default fallback.

Google Maps requires its Android setup, API key and Google Play services; some DJI controllers lack Play services. Mapbox requires a provider token. Keeping MapLibre allows the same map UI across the app's supported Android/controller targets while retaining local packs. This does not expand aircraft or controller compatibility.

## Privacy and service availability

The tile provider receives normal map requests, including your IP address and tile coordinates for the area viewed. They can reveal the viewed area. The app's map client does not attach Passport tokens, Flight Blender credentials, pilot names or aircraft IDs. Map traffic is separate from Flight Blender declarations and telemetry.

The public OSM service is best effort with no availability guarantee. Requests identify OpenUTM Flight, honour provider cache headers, use a seven-day fallback when OSM supplies no expiry, and disable tile prefetch. There is no public-tile offline download feature. For a larger rollout, the coordinator should select a provider with appropriate capacity or host tiles.

## Administrator: change the online provider

An optional `online_map` object in the existing private device setup selects another compatible raster XYZ service. Import or scan the updated setup through the normal workflow; no APK rebuild is needed. All existing required setup fields still apply.

```json
"online_map": {
  "name": "Lab streets",
  "tile_url": "https://maps.example.org/{z}/{x}/{y}.png",
  "attribution": "© Your map contributors",
  "attribution_url": "https://maps.example.org/credits",
  "max_zoom": 19
}
```

This is a fragment, not a complete setup file. Use the provider's actual URL, credits and supported zoom limit. URLs must use HTTPS, with no embedded username/password or fragment. The template requires one each of `{z}`, `{x}` and `{y}`. There is no custom authorization-header support; do not embed private credentials in a tile URL. Omitting `online_map` retains the OSM default. Providers must permit native-app raster tile use and the expected traffic.

References: [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/), [OSM attribution](https://www.openstreetmap.org/copyright), [Google Android map setup](https://developers.google.com/maps/documentation/android-sdk/config), [Mapbox access tokens](https://docs.mapbox.com/help/glossary/access-token/).
