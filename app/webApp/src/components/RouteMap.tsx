import 'maplibre-gl/dist/maplibre-gl.css';

import { layers, namedFlavor } from '@protomaps/basemaps';
import type { Feature, FeatureCollection, LineString, Point } from 'geojson';
import * as maplibregl from 'maplibre-gl';
import type { GeoJSONSource, LayerSpecification, StyleSpecification } from 'maplibre-gl';
import { Protocol } from 'pmtiles';
import { useEffect, useRef } from 'react';
import type { LatLon, ShareView } from 'sharedLogic';

/**
 * The basemap, a PMTiles extract on the static host or wherever VITE_PMTILES_URL points; without it, the route
 * shows on a plain background. Labels are left out, so no glyphs or sprites are fetched from anywhere.
 */
const TILES_URL: string | undefined = import.meta.env.VITE_PMTILES_URL;

if (TILES_URL) maplibregl.addProtocol('pmtiles', new Protocol().tile);

function style(dark: boolean): StyleSpecification {
  const background: LayerSpecification = {
    id: 'background',
    type: 'background',
    paint: { 'background-color': dark ? '#1f1e1b' : '#ece9e2' },
  };
  if (!TILES_URL) return { version: 8, sources: {}, layers: [background] };
  return {
    version: 8,
    sources: {
      protomaps: {
        type: 'vector',
        url: `pmtiles://${TILES_URL}`,
        attribution: '© <a href="https://openstreetmap.org/copyright">OpenStreetMap</a>',
      },
    },
    layers: layers('protomaps', namedFlavor(dark ? 'dark' : 'light'), { lang: 'en' }).filter((layer) => layer.type !== 'symbol'),
  };
}

function line(points: LatLon[]): Feature<LineString> {
  return { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: points.map((p) => [p.lon, p.lat]) } };
}

function position(points: LatLon[]): FeatureCollection<Point> {
  const last = points[points.length - 1];
  return {
    type: 'FeatureCollection',
    features: last ? [{ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [last.lon, last.lat] } }] : [],
  };
}

function bounds(points: LatLon[]): maplibregl.LngLatBounds | null {
  if (points.length === 0) return null;
  const b = new maplibregl.LngLatBounds([points[0].lon, points[0].lat], [points[0].lon, points[0].lat]);
  for (const p of points) b.extend([p.lon, p.lat]);
  return b;
}

/** Route, course and position; it keeps them in view until the viewer moves the map. */
export function RouteMap({ view }: { view: ShareView }) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const ready = useRef(false);
  const moved = useRef(false);
  const latest = useRef(view);
  latest.current = view;

  useEffect(() => {
    const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    const colors = getComputedStyle(document.documentElement);
    const m = new maplibregl.Map({ container: container.current!, style: style(dark), attributionControl: { compact: true } });
    map.current = m;
    m.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-left');
    m.on('dragstart', () => (moved.current = true));
    m.on('zoomstart', (event: maplibregl.MapLibreEvent<MouseEvent | TouchEvent | WheelEvent | undefined>) => {
      if (event.originalEvent) moved.current = true;
    });
    m.on('load', () => {
      const v = latest.current;
      m.addSource('course', { type: 'geojson', data: line(v.course) });
      m.addSource('route', { type: 'geojson', data: line(v.route) });
      m.addSource('position', { type: 'geojson', data: position(v.route) });
      m.addLayer({
        id: 'course',
        type: 'line',
        source: 'course',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': colors.getPropertyValue('--course').trim(), 'line-width': 5, 'line-dasharray': [0.5, 2] },
      });
      m.addLayer({
        id: 'route',
        type: 'line',
        source: 'route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': colors.getPropertyValue('--route').trim(), 'line-width': 4 },
      });
      m.addLayer({
        id: 'position',
        type: 'circle',
        source: 'position',
        paint: {
          'circle-radius': 8,
          'circle-color': colors.getPropertyValue('--route').trim(),
          'circle-stroke-width': 3,
          'circle-stroke-color': colors.getPropertyValue('--surface').trim(),
        },
      });
      ready.current = true;
      fit(m, v, false);
    });
    return () => {
      ready.current = false;
      m.remove();
    };
  }, []);

  // A new view arrives every second; the map changes only when the route does.
  useEffect(() => {
    const m = map.current;
    if (!m || !ready.current) return;
    (m.getSource('route') as GeoJSONSource).setData(line(view.route));
    (m.getSource('position') as GeoJSONSource).setData(position(view.route));
    (m.getSource('course') as GeoJSONSource).setData(line(view.course));
    if (!moved.current) fit(m, view, true);
  }, [view.routeVersion, view.course]);

  return <div ref={container} className="map" role="img" aria-label="The route on a map" />;
}

function fit(m: maplibregl.Map, view: ShareView, animate: boolean) {
  const b = bounds([...view.route, ...view.course]);
  if (b) m.fitBounds(b, { padding: 32, maxZoom: 16, animate });
}
