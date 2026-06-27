import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import StatCard from './StatCard';

describe('StatCard', () => {
  it('renders the title and value', () => {
    render(<StatCard title="Active jobs" value={7} icon={<span>j</span>} />);
    expect(screen.getByText('Active jobs')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
  });

  it('renders a sparkline when given a multi-point series', () => {
    const { container } = render(
      <StatCard title="Lag" value={12} icon={<span>l</span>} series={[1, 2, 3, 4]} />,
    );
    expect(container.querySelector('svg[role="img"]')).toBeInTheDocument();
  });

  it('omits the sparkline for a single-point series', () => {
    const { container } = render(
      <StatCard title="Lag" value={12} icon={<span>l</span>} series={[1]} />,
    );
    expect(container.querySelector('svg[role="img"]')).not.toBeInTheDocument();
  });
});
