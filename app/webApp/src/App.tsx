import { lazy, Suspense } from 'react';
import type { ShareView } from 'sharedLogic';
import { Stats } from './components/Stats.tsx';
import { note } from './format.ts';
import { useLiveShare } from './useLiveShare.ts';

// Most of the bundle; indoor workouts never load it.
const RouteMap = lazy(() => import('./components/RouteMap.tsx').then((m) => ({ default: m.RouteMap })));

const PHASES: Record<string, string> = {
  waiting: 'Starting soon',
  active: 'Live',
  paused: 'Paused',
  ended: 'Finished',
};

function title(view: ShareView): string {
  const what = view.activity === 'running' ? 'run' : view.activity === 'walking' ? 'walk' : 'workout';
  return `${view.outdoor ? '' : 'Indoor '}${what}`.replace(/^./, (c) => c.toUpperCase());
}

function status(view: ShareView): string {
  switch (view.connection) {
    case 'invalidLink':
      return 'This link is incomplete. Ask for it again, and open it exactly as it was sent.';
    case 'notFound':
      return 'This share is over, or the link is wrong.';
    case 'full':
      return 'Too many people are watching right now. Trying again shortly…';
    case 'connecting':
      return 'Opening the share…';
    case 'reconnecting':
      return 'Connection lost. Reconnecting…';
  }
  if (view.phase === 'ended') return 'The workout is over. This page shows how it ended for a while.';
  const ago = view.updatedAgoMs == null ? null : Math.round(view.updatedAgoMs / 60_000);
  return ago == null ? '' : ago < 1 ? 'Updated just now' : `Updated ${ago} min ago`;
}

export function App() {
  const view = useLiveShare();
  if (!view) return null;
  const started = view.phase != null;
  return (
    <main>
      <header>
        <h1>{started ? title(view) : 'Hashiru live'}</h1>
        {started && (
          <span className="phase" data-phase={view.phase}>
            {PHASES[view.phase!]}
          </span>
        )}
      </header>
      {started && view.outdoor && (view.route.length > 0 || view.course.length > 0) && (
        <Suspense fallback={<div className="map" />}>
          <RouteMap view={view} />
        </Suspense>
      )}
      {started && <Stats view={view} />}
      {view.notes.length > 0 && (
        <ul className="notes">
          {view.notes.map((n) => (
            <li key={n.kind}>{note(n, view.courseName)}</li>
          ))}
        </ul>
      )}
      <footer>
        <p role="status">{status(view)}</p>
        {view.batteryPercent != null && <p>Phone battery {view.batteryPercent} %</p>}
        <p className="fine">End-to-end encrypted: the key is in this link, and the server never sees where anyone is.</p>
      </footer>
    </main>
  );
}
