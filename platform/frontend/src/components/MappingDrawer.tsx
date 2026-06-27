import {
  Drawer, Table, Select, Input, Alert, Button, Space, App, Tag, Typography, Tooltip, Tabs,
} from 'antd';
import { KeyOutlined, SaveOutlined, WarningOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { schemaApi, projectsApi } from '../api/client';
import type { ColumnMapping, Project, Recommendation, ColumnProfile } from '../api/types';

const CONF_COLOR: Record<string, string> = { HIGH: 'green', LOW: 'orange', MEDIUM: 'blue' };
const PII_COLOR: Record<string, string> = { NONE: 'default', EMAIL: 'red', SSN: 'red', CREDIT_CARD: 'red', PHONE: 'volcano', NAME: 'gold' };

const PG_TYPES = [
  'SMALLINT', 'INTEGER', 'BIGINT', 'NUMERIC', 'DOUBLE PRECISION', 'REAL', 'BOOLEAN',
  'TEXT', 'DATE', 'TIME(6)', 'TIMESTAMP(6)', 'TIMESTAMPTZ(6)', 'BYTEA', 'UUID', 'JSONB',
];

const snake = (s: string) =>
  s.replace(/([A-Z]+)([A-Z][a-z])/g, '$1_$2').replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase();

const asList = (v: unknown): string[] =>
  Array.isArray(v) ? (v as string[])
    : typeof v === 'string' && v ? v.split(',').map((s) => s.trim()).filter(Boolean) : [];

interface Edit { type: string; semantic: 'NONE' | 'UUID' | 'JSON'; }

export default function MappingDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const open = project !== null;
  const connId = project?.sourceConnectionId;
  const tables = asList(project?.config?.selectedTables);
  const [table, setTable] = useState<string | undefined>(undefined);
  const [edits, setEdits] = useState<Record<string, Edit>>({});

  useEffect(() => { setTable(tables[0]); setEdits({}); /* eslint-disable-next-line */ }, [project]);

  const [schemaName, tableName] = (table ?? '.').split('.');

  const mapping = useQuery({
    queryKey: ['type-mapping', connId, table],
    queryFn: () => schemaApi.typeMapping(connId!, schemaName, tableName, project!.id),
    enabled: open && !!connId && !!table,
  });

  const [tab, setTab] = useState('mapping');

  const recs = useQuery({
    queryKey: ['recommendations', project?.id],
    queryFn: () => projectsApi.recommendations(project!.id),
    enabled: open && !!project && tab === 'recommendations',
  });

  const profile = useQuery({
    queryKey: ['profile', connId, table],
    queryFn: () => schemaApi.profile(connId!, schemaName, tableName),
    enabled: open && !!connId && !!table && tab === 'profile',
  });

  useEffect(() => {
    if (mapping.data) {
      const init: Record<string, Edit> = {};
      mapping.data.forEach((m) => { init[m.column] = { type: m.proposedType, semantic: m.semantic }; });
      setEdits(init);
    }
  }, [mapping.data]);

  const setEdit = (col: string, patch: Partial<Edit>) =>
    setEdits((e) => ({ ...e, [col]: { ...e[col], ...patch } }));

  const save = useMutation({
    mutationFn: () => {
      const cfg = project!.config ?? {};
      const uuid = new Set(asList(cfg.uuidColumns));
      const json = new Set(asList(cfg.jsonColumns));
      const overrides: Record<string, string> = { ...(cfg.typeOverrides as Record<string, string> ?? {}) };
      (mapping.data ?? []).forEach((m) => {
        const e = edits[m.column];
        if (!e) return;
        const sc = snake(m.column);
        if (e.semantic === 'UUID') uuid.add(sc); else uuid.delete(sc);
        if (e.semantic === 'JSON') json.add(sc); else json.delete(sc);
        if (e.type && e.type !== m.proposedType) overrides[`${table}.${m.column}`] = e.type;
        else delete overrides[`${table}.${m.column}`];
      });
      return projectsApi.update(project!.id, {
        name: project!.name,
        description: project!.description,
        sourceConnectionId: project!.sourceConnectionId,
        targetConnectionId: project!.targetConnectionId,
        config: {
          ...cfg,
          uuidColumns: [...uuid],
          jsonColumns: [...json],
          typeOverrides: overrides,
        },
      });
    },
    onSuccess: () => {
      message.success('Mapping saved');
      qc.invalidateQueries({ queryKey: ['projects'] });
    },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Save failed'),
  });

  const columns = [
    {
      title: 'Column', dataIndex: 'column',
      render: (n: string, m: ColumnMapping) => (
        <Space>{m.primaryKey && <KeyOutlined style={{ color: '#faad14' }} />}{n}</Space>
      ),
    },
    {
      title: 'Source type', dataIndex: 'sourceType',
      render: (t: string, m: ColumnMapping) => <Tag>{t}{m.size > 0 && m.size < 1e7 ? `(${m.size})` : ''}</Tag>,
    },
    {
      title: 'Target type (override)',
      render: (_: unknown, m: ColumnMapping) => (
        <Space>
          <Input
            size="small"
            style={{ width: 170 }}
            value={edits[m.column]?.type ?? m.proposedType}
            onChange={(ev) => setEdit(m.column, { type: ev.target.value })}
            list="pgtypes"
          />
          {m.note && (
            <Tooltip title={m.note}>
              <WarningOutlined style={{ color: '#faad14' }} />
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: 'Semantic',
      render: (_: unknown, m: ColumnMapping) => (
        <Select
          size="small"
          style={{ width: 110 }}
          value={edits[m.column]?.semantic ?? m.semantic}
          onChange={(v) => setEdit(m.column, { semantic: v })}
          options={[
            { value: 'NONE', label: '—' },
            { value: 'UUID', label: 'UUID' },
            { value: 'JSON', label: 'JSONB' },
          ]}
        />
      ),
    },
  ];

  return (
    <Drawer
      title={project ? `Type mapping — ${project.name}` : ''}
      width={820}
      open={open}
      onClose={onClose}
      extra={
        tab === 'mapping' ? (
          <Button type="primary" icon={<SaveOutlined />}
            disabled={!table} loading={save.isPending} onClick={() => save.mutate()}>
            Save mapping
          </Button>
        ) : null
      }
    >
      <datalist id="pgtypes">{PG_TYPES.map((t) => <option key={t} value={t} />)}</datalist>

      {tables.length === 0 ? (
        <Alert type="warning" showIcon message="No tables selected"
          description="Use the Tables action to discover and select tables first." />
      ) : (
        <>
          <Space style={{ marginBottom: 12 }}>
            <Typography.Text>Table:</Typography.Text>
            <Select
              style={{ width: 320 }}
              value={table}
              onChange={setTable}
              options={tables.map((t) => ({ value: t, label: t }))}
            />
          </Space>

          <Tabs
            activeKey={tab}
            onChange={setTab}
            items={[
              {
                key: 'mapping',
                label: 'Type mapping',
                children: (
                  <>
                    <Alert type="info" showIcon style={{ marginBottom: 12 }}
                      message="UUID/JSON designations feed the type-conversion transform (snake_cased) and apply on the next run. Target-type overrides are saved for the schema-DDL path."
                    />
                    <Table<ColumnMapping>
                      rowKey="column"
                      size="small"
                      loading={mapping.isLoading}
                      dataSource={mapping.data}
                      columns={columns}
                      pagination={false}
                    />
                  </>
                ),
              },
              {
                key: 'recommendations',
                label: 'Recommendations',
                children: (
                  <>
                    <Alert type="info" showIcon style={{ marginBottom: 12 }}
                      message="Type-mapping advice across all selected tables. High-confidence suggestions are safe to apply via the override above; review LOW-confidence ones."
                    />
                    <Table<Recommendation>
                      rowKey={(r) => `${r.table}.${r.column}`}
                      size="small"
                      loading={recs.isLoading}
                      dataSource={recs.data}
                      pagination={{ pageSize: 12, hideOnSinglePage: true }}
                      columns={[
                        { title: 'Table', dataIndex: 'table', render: (v: string) => <Typography.Text type="secondary">{v}</Typography.Text> },
                        { title: 'Column', dataIndex: 'column' },
                        { title: 'Source', dataIndex: 'sourceType', render: (t: string) => <Tag>{t}</Tag> },
                        { title: 'Recommended', dataIndex: 'recommended', render: (t: string) => <Tag color="geekblue">{t}</Tag> },
                        { title: 'Why', dataIndex: 'rationale' },
                        {
                          title: 'Confidence', dataIndex: 'confidence',
                          render: (c: string) => <Tag color={CONF_COLOR[c] ?? 'default'}>{c}</Tag>,
                        },
                      ]}
                    />
                  </>
                ),
              },
              {
                key: 'profile',
                label: 'Profile & PII',
                children: (
                  <>
                    <Alert type="info" showIcon style={{ marginBottom: 12 }}
                      message={`Sampled column statistics${profile.data ? ` — ~${profile.data.rows.toLocaleString()} rows` : ''}. PII tags flag columns that may need masking or extra handling.`}
                    />
                    <Table<ColumnProfile>
                      rowKey="column"
                      size="small"
                      loading={profile.isLoading}
                      dataSource={profile.data?.columns}
                      pagination={false}
                      columns={[
                        {
                          title: 'Column', dataIndex: 'column',
                          render: (n: string, c: ColumnProfile) => (
                            <Space>{n}{c.pii && c.pii !== 'NONE' && <Tag color={PII_COLOR[c.pii] ?? 'red'}>{c.pii}</Tag>}</Space>
                          ),
                        },
                        { title: 'Type', dataIndex: 'type', render: (t: string) => <Tag>{t}</Tag> },
                        {
                          title: 'Null %', dataIndex: 'nullPct',
                          render: (p: number) => `${(p ?? 0).toFixed(1)}%`,
                        },
                        { title: 'Distinct', dataIndex: 'distinct', render: (v: number) => (v ?? 0).toLocaleString() },
                        { title: 'Min', dataIndex: 'min', render: (v: string | null) => v ?? <Typography.Text type="secondary">—</Typography.Text> },
                        { title: 'Max', dataIndex: 'max', render: (v: string | null) => v ?? <Typography.Text type="secondary">—</Typography.Text> },
                      ]}
                    />
                  </>
                ),
              },
            ]}
          />
        </>
      )}
    </Drawer>
  );
}
