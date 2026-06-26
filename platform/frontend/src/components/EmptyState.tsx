import { Typography } from 'antd';
import type { ReactNode } from 'react';

/**
 * A polished, consistent empty state: a soft icon medallion, a clear title, a one-line explanation,
 * and an optional primary action. Used wherever a list/table can be empty.
 */
export default function EmptyState({
  icon, title, description, action, compact,
}: {
  icon: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
  compact?: boolean;
}) {
  return (
    <div style={{
      display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center',
      gap: 6, padding: compact ? '28px 16px' : '52px 24px',
    }}>
      <div style={{
        width: 56, height: 56, borderRadius: 16, display: 'grid', placeItems: 'center',
        marginBottom: 6, fontSize: 26, color: '#6366F1',
        background: 'linear-gradient(135deg, rgba(99,102,241,0.14), rgba(99,102,241,0.06))',
        border: '1px solid rgba(99,102,241,0.18)',
      }}>
        {icon}
      </div>
      <Typography.Title level={5} style={{ margin: 0 }}>{title}</Typography.Title>
      {description && (
        <Typography.Text type="secondary" style={{ maxWidth: 420 }}>{description}</Typography.Text>
      )}
      {action && <div style={{ marginTop: 14 }}>{action}</div>}
    </div>
  );
}
