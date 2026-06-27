import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import Sparkline from './Sparkline';

describe('Sparkline', () => {
  it('renders an accessible svg with line + area + endpoint', () => {
    const { container, getByRole } = render(<Sparkline data={[1, 4, 2, 8, 5]} />);
    expect(getByRole('img', { name: 'trend' })).toBeInTheDocument();
    // area path + line path
    expect(container.querySelectorAll('path').length).toBe(2);
    // emphasized endpoint
    expect(container.querySelector('circle')).toBeInTheDocument();
  });

  it('degrades gracefully to a flat baseline with a single point', () => {
    const { container } = render(<Sparkline data={[3]} />);
    // still produces a valid line path (no NaN coordinates)
    const line = container.querySelectorAll('path')[1];
    expect(line.getAttribute('d')).not.toMatch(/NaN/);
  });

  it('honours width/height props on the viewBox', () => {
    const { getByRole } = render(<Sparkline data={[1, 2]} width={200} height={40} />);
    expect(getByRole('img', { name: 'trend' })).toHaveAttribute('viewBox', '0 0 200 40');
  });
});
