import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

// Ant Design reads matchMedia (responsive Grid) and ResizeObserver (some overlays); jsdom ships
// neither. Stub them so components mount without throwing.
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }) as MediaQueryList;
}

if (!('ResizeObserver' in window)) {
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

// antd's Table measures the scrollbar via getComputedStyle(el, pseudoElt); jsdom throws on the
// pseudo-element argument. Drop it so the call falls back to the supported single-arg form.
const realGetComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = ((el: Element) => realGetComputedStyle(el)) as typeof window.getComputedStyle;

// jsdom lacks getComputedStyle transform support antd's motion uses; silence the noise.
vi.spyOn(console, 'error').mockImplementation((...args) => {
  const msg = String(args[0] ?? '');
  if (msg.includes('not wrapped in act') || msg.includes('matchMedia')) return;
  // eslint-disable-next-line no-console
  console.warn(...args);
});

afterEach(() => cleanup());
