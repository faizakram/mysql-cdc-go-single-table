import { Tour, type TourProps } from 'antd';

/**
 * First-run guided tour (#onboarding). Walks a brand-new user through the platform in workflow order —
 * Connections → Projects → Dashboard → Alerts — so they know "where to start and where to go next".
 * Steps anchor to the nav items by their {@code data-tour} attribute (see AppLayout); a missing anchor
 * (e.g. the mobile drawer is closed) makes the step center on screen, which still reads fine.
 */
export default function OnboardingTour({ open, onClose }: { open: boolean; onClose: () => void }) {
  const at = (sel: string) => () => (document.querySelector(sel) as HTMLElement) ?? null;

  const steps: TourProps['steps'] = [
    {
      title: 'Welcome to the Migration Platform 👋',
      description:
        'A quick tour of how to move data from one database to another — and watch it sync live. '
        + 'It takes about a minute. You can replay it any time from the “Guide” button in the top bar.',
      target: at('.brand'),
    },
    {
      title: 'Step 1 — Connections',
      description:
        'Start here. Add your source and target databases, use “Test” to verify each one, and check '
        + 'CDC readiness on the source. You need at least one source and one target before you can '
        + 'create a migration.',
      target: at('[data-tour="nav-connections"]'),
    },
    {
      title: 'Step 2 — Projects',
      description:
        'Create a project that links a source to a target and pick the tables to migrate. When you start '
        + 'it, the platform first copies your existing data; CDC-capable tables then keep streaming changes '
        + 'live, while the rest are bulk-copied once.',
      target: at('[data-tour="nav-projects"]'),
    },
    {
      title: 'Step 3 — Dashboard',
      description:
        'Your home base once a migration is running: pipeline health, throughput, replication lag and the '
        + 'live sync stream all update here in real time.',
      target: at('[data-tour="nav-dashboard"]'),
    },
    {
      title: 'Step 4 — Alerts',
      description:
        'Connector failures and lag or drift breaches surface here, so you catch problems early. '
        + 'That’s the tour — head to Connections to add your first database!',
      target: at('[data-tour="nav-alerts"]'),
    },
  ];

  return <Tour open={open} onClose={onClose} steps={steps} />;
}
