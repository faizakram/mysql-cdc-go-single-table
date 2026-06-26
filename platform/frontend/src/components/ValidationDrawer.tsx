import {
  Drawer, Button, Table, Tag, Tooltip, App, Statistic, Row, Col, Empty, Typography,
  Segmented, InputNumber, Space, Switch, Divider,
} from 'antd';
import { CheckCircleOutlined, SyncOutlined, DownloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { reconciliationApi, projectsApi } from '../api/client';
import type { Project, ReconciliationResult, ReconciliationRun } from '../api/types';

const RESULT_COLOR: Record<string, string> = {
  MATCH: 'green', MISMATCH: 'red', ERROR: 'orange', SKIPPED: 'default',
};

export default function ValidationDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const open = project !== null;
  const [mode, setMode] = useState<'COUNT' | 'CHECKSUM'>('COUNT');
  const [sampleSize, setSampleSize] = useState(1000);
  const [auto, setAuto] = useState(false);

  useEffect(() => { setAuto(project?.config?.autoReconcile === true); }, [project]);

  const history = useQuery({
    queryKey: ['reconciliation', project?.id],
    queryFn: () => reconciliationApi.history(project!.id),
    enabled: open,
  });

  const run = useMutation({
    mutationFn: () => reconciliationApi.run(project!.id, mode, sampleSize),
    onSuccess: (r: ReconciliationRun) => {
      message[r.mismatched === 0 && r.status === 'COMPLETED' ? 'success' : 'warning'](
        `Validation ${r.status.toLowerCase()} — ${r.mismatched}/${r.totalTables} mismatched`);
      qc.invalidateQueries({ queryKey: ['reconciliation', project?.id] });
    },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Validation failed'),
  });

  const toggleAuto = useMutation({
    mutationFn: (enabled: boolean) => projectsApi.update(project!.id, {
      name: project!.name, description: project!.description,
      sourceConnectionId: project!.sourceConnectionId, targetConnectionId: project!.targetConnectionId,
      config: { ...project!.config, autoReconcile: enabled },
    }),
    onSuccess: (_d, enabled) => {
      setAuto(enabled);
      message.success(enabled ? 'Scheduled validation enabled' : 'Scheduled validation disabled');
      qc.invalidateQueries({ queryKey: ['projects'] });
    },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Update failed'),
  });

  const download = async (runId: string) => {
    try {
      const blob = await reconciliationApi.report(runId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = `reconciliation-${runId}.csv`; a.click();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Download failed');
    }
  };

  const latest = history.data?.[0];
  const isChecksum = latest?.mode === 'CHECKSUM';
  const trend = [...(history.data ?? [])].slice(0, 14).reverse(); // oldest → newest

  const baseCols = [{ title: 'Table', render: (_: unknown, r: ReconciliationResult) => `${r.schemaName}.${r.tableName}` }];
  const countCols = [
    { title: 'Source', dataIndex: 'sourceCount', render: (v: number | null) => v ?? '—' },
    { title: 'Target', dataIndex: 'targetCount', render: (v: number | null) => v ?? '—' },
    { title: 'Diff', dataIndex: 'difference', render: (v: number | null) => (v == null ? '—' : <span style={{ color: v === 0 ? undefined : '#cf1322' }}>{v}</span>) },
  ];
  const checksumCols = [
    { title: 'Sampled', dataIndex: 'sampled', render: (v: number | null) => v ?? '—' },
    { title: 'Missing in target', dataIndex: 'missing', render: (v: number | null) => (v == null ? '—' : <span style={{ color: v === 0 ? undefined : '#cf1322' }}>{v}</span>) },
  ];
  const statusCol = [{
    title: 'Status', dataIndex: 'status',
    render: (s: string, r: ReconciliationResult) => {
      const tag = <Tag color={RESULT_COLOR[s] ?? 'default'}>{s}</Tag>;
      return r.error ? <Tooltip title={r.error}>{tag}</Tooltip> : tag;
    },
  }];
  const columns = [...baseCols, ...(isChecksum ? checksumCols : countCols), ...statusCol];

  return (
    <Drawer
      title={project ? `Validate — ${project.name}` : ''}
      width={780}
      open={open}
      onClose={onClose}
      extra={
        <Space>
          <Segmented value={mode} onChange={(v) => setMode(v as 'COUNT' | 'CHECKSUM')}
            options={[{ label: 'Row counts', value: 'COUNT' }, { label: 'Checksum sample', value: 'CHECKSUM' }]} />
          {mode === 'CHECKSUM' && (
            <InputNumber min={10} max={100000} step={500} value={sampleSize}
              onChange={(v) => setSampleSize(v ?? 1000)} addonBefore="sample" style={{ width: 160 }} />
          )}
          <Button type="primary" icon={<SyncOutlined />} loading={run.isPending} onClick={() => run.mutate()}>
            Run validation
          </Button>
        </Space>
      }
    >
      <Space style={{ marginBottom: 12 }}>
        <Switch checked={auto} loading={toggleAuto.isPending} onChange={(v) => toggleAuto.mutate(v)} />
        <Typography.Text>Scheduled validation (drift detection)</Typography.Text>
        <Typography.Text type="secondary">runs row-count checks automatically while the migration is active</Typography.Text>
      </Space>

      {trend.length > 0 && (
        <>
          <Typography.Text type="secondary">Drift trend (recent runs)</Typography.Text>
          <div style={{ display: 'flex', gap: 4, alignItems: 'flex-end', height: 44, margin: '6px 0 12px' }}>
            {trend.map((r) => (
              <Tooltip key={r.id} title={`${new Date(r.startedAt).toLocaleString()} · ${r.mismatched}/${r.totalTables} mismatched · ${r.mode}`}>
                <div style={{
                  width: 14, height: r.mismatched > 0 ? 40 : 16, borderRadius: 2,
                  background: r.status !== 'COMPLETED' ? '#d9d9d9' : r.mismatched > 0 ? '#ff4d4f' : '#52c41a',
                }} />
              </Tooltip>
            ))}
          </div>
        </>
      )}

      <Typography.Paragraph type="secondary">
        {mode === 'COUNT'
          ? 'Compares row counts per selected table (source vs target). Soft-deleted rows are excluded from the target count when the project uses soft delete.'
          : 'Samples primary keys from the source and checks they exist in the target — catches identity gaps that equal counts can miss. Single-primary-key tables only.'}
      </Typography.Paragraph>

      {latest && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={5}><Statistic title="Mode" value={latest.mode} /></Col>
          <Col span={5}><Statistic title="Tables" value={latest.totalTables} /></Col>
          <Col span={6}>
            <Statistic title="Mismatched" value={latest.mismatched}
              valueStyle={{ color: latest.mismatched > 0 ? '#cf1322' : '#3f8600' }}
              prefix={latest.mismatched === 0 ? <CheckCircleOutlined /> : undefined} />
          </Col>
          <Col span={8}>
            <Statistic title="Latest run" value={latest.status} />
            <Button size="small" icon={<DownloadOutlined />} onClick={() => download(latest.id)}>Download CSV</Button>
          </Col>
        </Row>
      )}

      {!latest && !history.isLoading
        ? <Empty description="No validation runs yet. Pick a mode and click Run validation." />
        : <Table<ReconciliationResult>
            rowKey={(r) => `${r.schemaName}.${r.tableName}`}
            size="small"
            loading={history.isLoading || run.isPending}
            dataSource={latest?.results}
            columns={columns}
            pagination={false}
          />}

      {history.data && history.data.length > 1 && (
        <>
          <Divider orientation="left" plain>History</Divider>
          <Table
            rowKey="id"
            size="small"
            dataSource={history.data}
            pagination={{ pageSize: 8 }}
            columns={[
              { title: 'Started', dataIndex: 'startedAt', render: (t: string) => new Date(t).toLocaleString() },
              { title: 'Mode', dataIndex: 'mode', render: (m: string) => <Tag>{m}</Tag> },
              { title: 'Status', dataIndex: 'status' },
              {
                title: 'Mismatched',
                render: (_: unknown, r: ReconciliationRun) => (
                  <span style={{ color: r.mismatched > 0 ? '#cf1322' : undefined }}>{r.mismatched}/{r.totalTables}</span>
                ),
              },
              {
                title: '',
                render: (_: unknown, r: ReconciliationRun) => (
                  <Button size="small" icon={<DownloadOutlined />} onClick={() => download(r.id)}>CSV</Button>
                ),
              },
            ]}
          />
        </>
      )}
    </Drawer>
  );
}
