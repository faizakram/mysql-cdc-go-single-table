import { Card, Statistic } from 'antd';
import type { CSSProperties, ReactNode } from 'react';
import Sparkline from './Sparkline';

/**
 * A richer dashboard metric card: a severity stripe down the left edge, an icon medallion, the
 * value, and an optional live sparkline of recent history.
 */
export default function StatCard({
  title, value, icon, stripe = '#4F46E5', series, loading, valueStyle,
}: {
  title: string;
  value: number;
  icon: ReactNode;
  stripe?: string;
  series?: number[];
  loading?: boolean;
  valueStyle?: CSSProperties;
}) {
  return (
    <Card className="stat-card" style={{ ['--stripe' as any]: stripe } as CSSProperties}
      styles={{ body: { padding: 18 } }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
        <Statistic title={title} value={value} loading={loading} valueStyle={valueStyle} />
        <span style={{
          width: 38, height: 38, borderRadius: 10, display: 'grid', placeItems: 'center',
          flex: 'none', fontSize: 18, color: stripe,
          background: `color-mix(in srgb, ${stripe} 14%, transparent)`,
        }}>
          {icon}
        </span>
      </div>
      {series && series.length > 1 && (
        <div style={{ marginTop: 10, height: 32 }}>
          <Sparkline data={series} color={stripe} width={220} height={32} />
        </div>
      )}
    </Card>
  );
}
