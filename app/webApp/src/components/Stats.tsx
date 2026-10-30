import type { ShareView } from 'sharedLogic';
import { distance, duration } from '../format.ts';

function Tile({ label, value }: { label: string; value: string }) {
  return (
    <div className="tile">
      <span className="label">{label}</span>
      <span className="value">{value}</span>
    </div>
  );
}

export function Stats({ view }: { view: ShareView }) {
  return (
    <section className="stats">
      <Tile label="Time" value={duration(view.seconds)} />
      <Tile label="Distance" value={distance(view.meters)} />
      <Tile label="Pace" value={view.paceSecondsPerKm != null ? `${duration(view.paceSecondsPerKm)} /km` : '–'} />
      {view.heartRateBpm != null && <Tile label="Heart rate" value={`${view.heartRateBpm} bpm`} />}
      {!view.outdoor && view.steps > 0 && <Tile label="Steps" value={view.steps.toLocaleString()} />}
    </section>
  );
}
