import { useEffect, useRef, useState } from 'react';

/**
 * Accumulate a polled scalar into a bounded time series for sparklines. Appends a point whenever
 * the value changes (live, client-side — no historical backend query needed).
 */
export function useSeries(value: number | undefined | null, max = 24): number[] {
  const [series, setSeries] = useState<number[]>([]);
  const seeded = useRef(false);
  useEffect(() => {
    if (value === undefined || value === null) return;
    setSeries((s) => [...s, value].slice(-max));
    seeded.current = true;
  }, [value, max]);
  return series;
}
