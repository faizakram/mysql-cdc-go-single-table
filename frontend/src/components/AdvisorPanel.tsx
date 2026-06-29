import { useQuery } from '@tanstack/react-query';
import { List, Tag, Typography, Spin, Space } from 'antd';
import { advisorApi } from '../api/client';
import type { AdvisorRecommendation } from '../api/types';

const SEV_COLOR: Record<string, string> = { OK: 'green', SUGGESTION: 'blue', WARNING: 'orange' };

/**
 * Performance advisor (#217): tuning recommendations for a project, derived from live throughput + lag.
 * Rendered in the dashboard's expandable project row; polls lightly while the row is open.
 */
export default function AdvisorPanel({ projectId }: { projectId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['advisor', projectId],
    queryFn: () => advisorApi.forProject(projectId),
    staleTime: 15_000,
    refetchInterval: 15_000,
  });

  if (isLoading) return <Spin size="small" />;
  if (!data) return <Typography.Text type="secondary">No advice available.</Typography.Text>;

  const header = (
    <Typography.Text strong>
      Performance advisor — {data.eventsPerSec} ev/s
      {data.sinkLagRecords != null ? `, sink lag ${data.sinkLagRecords} records` : ''}
    </Typography.Text>
  );

  return (
    <List
      size="small"
      header={header}
      dataSource={data.recommendations}
      renderItem={(r: AdvisorRecommendation) => (
        <List.Item>
          <Space align="start" wrap>
            <Tag color={SEV_COLOR[r.severity] ?? 'default'}>{r.severity}</Tag>
            <span>
              {r.message}
              {r.setting && (
                <Typography.Text code style={{ marginInlineStart: 6 }}>
                  {r.setting}: {r.current} → {r.suggested}
                </Typography.Text>
              )}
            </span>
          </Space>
        </List.Item>
      )}
    />
  );
}
