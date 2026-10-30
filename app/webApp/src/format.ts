import type { ShareNote } from 'sharedLogic';

export function duration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`;
}

export function distance(meters: number): string {
  return meters >= 1000 ? `${(meters / 1000).toFixed(2)} km` : `${Math.round(meters)} m`;
}

export function minutes(count: number): string {
  return count === 1 ? '1 minute' : `${count} minutes`;
}

export function note(n: ShareNote, courseName: string | null | undefined): string {
  switch (n.kind) {
    case 'silent':
      return `Nothing heard for ${minutes(n.minutes)}. The phone may have no signal.`;
    case 'notMoving':
      return `Not moving for ${minutes(n.minutes)}.`;
    case 'headingToCourse':
      return `Heading to the course, ${distance(n.meters)} away.`;
    case 'offCourse':
      return `Off course by ${distance(n.meters)}.`;
    case 'onCourse':
      return `${distance(n.meters)} to go on ${courseName ?? 'the course'}.`;
    default:
      return '';
  }
}
