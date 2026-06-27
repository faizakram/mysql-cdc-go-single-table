import { Drawer, Table, Tag, Statistic, Row, Col, Alert, Tooltip } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { connectionsApi } from '../api/client';
import type { Project, SchemaObject } from '../api/types';

const CAT_COLOR: Record<string, string> = {
  SEQUENCE: 'geekblue', IDENTITY: 'cyan', VIEW: 'gold', PROCEDURE: 'purple', FUNCTION: 'magenta',
};

/** Non-table schema objects inventory (#92): sequences/identity migrate; views/procs/functions reported. */
export default function SchemaObjectsDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const open = project !== null;
  const srcId = project?.sourceConnectionId;
  const q = useQuery({
    queryKey: ['schema-objects', srcId],
    queryFn: () => connectionsApi.schemaObjects(srcId!),
    enabled: open && !!srcId,
    retry: false,
  });

  return (
    <Drawer title={project ? `Schema objects — ${project.name}` : ''} width={820} open={open} onClose={onClose}>
      {!srcId && <Alert type="info" showIcon message="Assign a source connection to this project first." />}
      {q.isError && (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message="Inventory unavailable"
          description={(q.error as any)?.response?.data?.message ?? 'Ensure the source connection is reachable.'} />
      )}
      {q.data && (
        <>
          <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
            <Col xs={8}><Statistic title="Objects" value={q.data.objects.length} /></Col>
            <Col xs={8}><Statistic title="Migratable" value={q.data.migratable} valueStyle={{ color: '#16A34A' }} /></Col>
            <Col xs={8}><Statistic title="Report-only" value={q.data.reportOnly} valueStyle={{ color: '#D97706' }} /></Col>
          </Row>
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message="Sequences & identity columns are auto-migrated; views, procedures and functions are inventoried for assisted translation — never silently dropped." />
          <Table<SchemaObject>
            rowKey={(o) => `${o.category}:${o.schema}.${o.name}`} size="small" loading={q.isLoading}
            dataSource={q.data.objects} pagination={{ pageSize: 20 }} scroll={{ x: 'max-content' }}
            columns={[
              { title: 'Type', dataIndex: 'category', render: (c: string) => <Tag color={CAT_COLOR[c] ?? 'default'}>{c}</Tag> },
              { title: 'Schema', dataIndex: 'schema' },
              { title: 'Name', dataIndex: 'name' },
              {
                title: 'Status', dataIndex: 'status',
                render: (s: string) => <Tag color={s === 'MIGRATABLE' ? 'green' : 'orange'}>{s}</Tag>,
              },
              { title: 'Detail', dataIndex: 'detail', ellipsis: true, render: (d: string) => <Tooltip title={d}>{d}</Tooltip> },
            ]}
          />
        </>
      )}
    </Drawer>
  );
}
