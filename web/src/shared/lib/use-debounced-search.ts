import { useEffect, useState } from "react";

/** Trims search text and delays network queries while the user is typing. */
export function useDebouncedSearch(value: string, delay = 300): string {
  const [debounced, setDebounced] = useState(value.trim());

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value.trim()), delay);
    return () => window.clearTimeout(timer);
  }, [delay, value]);

  return debounced;
}
