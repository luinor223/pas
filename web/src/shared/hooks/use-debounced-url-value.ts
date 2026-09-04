import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { useLocation } from "@tanstack/react-router";

/** Keeps typing responsive while committing shareable search text to the URL after a short pause. */
export function useDebouncedUrlValue(value: string, onCommit: (value: string) => void, delay = 300) {
  const locationKey = useLocation({ select: (location) => location.state.__TSR_key ?? location.href });
  const [state, setState] = useState({ locationKey, source: value, draft: value });
  const draft = state.locationKey === locationKey && state.source === value ? state.draft : value;
  const setDraft = useCallback((next: string) => setState({ locationKey, source: value, draft: next }), [locationKey, value]);
  const commitRef = useRef(onCommit);

  // A history transition owns a fresh draft, even when its `q` value happens
  // to match the entry we left. This also cancels that entry's pending timer.
  useLayoutEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect -- router history is the external source synchronized by this hook.
    if (state.locationKey !== locationKey) setState({ locationKey, source: value, draft: value });
  }, [locationKey, state.locationKey, value]);

  useEffect(() => {
    commitRef.current = onCommit;
  }, [onCommit]);

  useEffect(() => {
    if (draft === value) return;
    const timer = window.setTimeout(() => {
      // Mark the draft as committed before navigation changes `value`. This keeps
      // an older draft from being resurrected when browser history restores it.
      setState({ locationKey, source: draft, draft });
      commitRef.current(draft);
    }, delay);
    return () => window.clearTimeout(timer);
  }, [delay, draft, locationKey, value]);

  return [draft, setDraft] as const;
}
