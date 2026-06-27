import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import EmptyState from './EmptyState';

describe('EmptyState', () => {
  it('shows the title and description', () => {
    render(<EmptyState icon={<span>i</span>} title="No projects yet" description="Create one to start." />);
    expect(screen.getByText('No projects yet')).toBeInTheDocument();
    expect(screen.getByText('Create one to start.')).toBeInTheDocument();
  });

  it('omits the description when not provided', () => {
    render(<EmptyState icon={<span>i</span>} title="Empty" />);
    expect(screen.getByText('Empty')).toBeInTheDocument();
    expect(screen.queryByText('Create one to start.')).not.toBeInTheDocument();
  });

  it('renders an action when given', () => {
    render(<EmptyState icon={<span>i</span>} title="Empty" action={<button>Add</button>} />);
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument();
  });
});
