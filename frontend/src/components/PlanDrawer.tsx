import { Drawer, Table, Tag, Space, Statistic, Row, Col, Alert, Button } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { projectsApi } from '../api/client';
import type { Project, PlanTable } from '../api/types';

const fmtBytes = (b: number) => (b > 0 ? `${(b / 1048576).toFixed(1)} MB` : '—');
const fmtDur = (s: number) => (s < 60 ? `${s}s` : s < 3600 ? `${Math.round(s / 60)}m` : `${(s / 3600).toFixed(1)}h`);

/** Intelligent migration plan (#88): dependency-ordered tables, parallel levels, estimates, risks. */
export default function PlanDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const open = project !== null;
  const q = useQuery({
    queryKey: ['plan', project?.id],
    queryFn: () => projectsApi.plan(project!.id),
    enabled: open,
    retry: false,
  });
  const cost = useQuery({
    queryKey: ['cost', project?.id],
    queryFn: () => projectsApi.costEstimate(project!.id),
    enabled: open,
    retry: false,
  });

  return (
    <Drawer title={project ? `Migration plan — ${project.name}` : ''} width={820} open={open} onClose={onClose}
      extra={<Button onClick={() => q.refetch()} loading={q.isFetching}>Recompute</Button>}>
      {q.isError && (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message="Plan unavailable"
          description={(q.error as any)?.response?.data?.message ?? 'Select tables and ensure the source is reachable.'} />
      )}
      {q.data && (
        <>
          <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
            <Col xs={12} md={6}><Statistic title="Tables" value={q.data.tables.length} /></Col>
            <Col xs={12} md={6}><Statistic title="Parallel waves" value={q.data.levels} /></Col>
            <Col xs={12} md={6}><Statistic title="Total rows" value={q.data.totalRows} /></Col>
            <Col xs={12} md={6}><Statistic title="Est. duration" value={fmtDur(q.data.estimatedSeconds)} /></Col>
            {cost.data && (
              <Col xs={12} md={6}><Statistic title="Est. first-month cost" prefix="$" value={cost.data.totalFirstMonthUsd} precision={2} /></Col>
            )}
          </Row>
          {q.data.hasCycles && (
            <Alert type="error" showIcon style={{ marginBottom: 12 }}
              message="Circular foreign-key dependencies detected"
              description="Cyclic tables migrate last with deferred constraints." />
          )}
          {q.data.risks.length > 0 && (
            <Alert type="warning" showIcon style={{ marginBottom: 12 }}
              message="Risks" description={<ul style={{ margin: 0, paddingLeft: 18 }}>{q.data.risks.map((r) => <li key={r}>{r}</li>)}</ul>} />
          )}
          <Table<PlanTable>
            rowKey="fqName" size="small" loading={q.isLoading} dataSource={q.data.tables}
            pagination={false} scroll={{ x: 'max-content' }}
            columns={[
              { title: '#', width: 48, render: (_t, _r, i) => i + 1 },
              { title: 'Table', dataIndex: 'fqName' },
              {
                title: 'Wave', dataIndex: 'level',
                render: (l: number) => (l < 0 ? <Tag color="red">cycle</Tag> : <Tag color="blue">{l}</Tag>),
              },
              { title: 'Rows', dataIndex: 'rowCount' },
              { title: 'Size', dataIndex: 'bytes', render: fmtBytes },
              { title: 'PK', dataIndex: 'hasPk', render: (v: boolean) => (v ? '✓' : <Tag color="orange">none</Tag>) },
              {
                title: 'Risks',
                render: (_v, r: PlanTable) => (r.risks.length === 0 ? '—'
                  : <Space wrap>{r.risks.map((x) => <Tag key={x} color="volcano">{x}</Tag>)}</Space>),
              },
            ]}
          />
        </>
      )}
      {!q.data && !q.isError && <div style={{ padding: 24, textAlign: 'center' }}>Computing plan…</div>}
    </Drawer>
  );
}
