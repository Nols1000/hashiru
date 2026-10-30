import { useEffect, useState } from 'react';
import { LiveShareViewer, type ShareView } from 'sharedLogic';

/**
 * Follows the share in the page's fragment through sharedLogic, which verifies and decrypts everything; this
 * only renders what it reports. A new fragment, as when another link is pasted into the same tab, starts over.
 */
export function useLiveShare(): ShareView | null {
  const [fragment, setFragment] = useState(() => window.location.hash);
  const [view, setView] = useState<ShareView | null>(null);

  useEffect(() => {
    const onHashChange = () => setFragment(window.location.hash);
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    const viewer = new LiveShareViewer(fragment);
    const unsubscribe = viewer.subscribe(setView);
    return () => {
      unsubscribe();
      viewer.close();
    };
  }, [fragment]);

  return view;
}
