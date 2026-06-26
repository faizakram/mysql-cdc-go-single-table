import {
  Drawer, Table, Select, Input, Alert, Button, Space, App, Tag, Typography,
} from 'antd';
import { KeyOutlined, SaveOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { schemaApi, projectsApi } from '../api/client';
import type { ColumnMapping, Project } from '../api/types';

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
    queryFn: () => schemaApi.typeMapping(connId!, schemaName, tableName),
    enabled: open && !!connId && !!table,
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
        <Input
          size="small"
          style={{ width: 180 }}
          value={edits[m.column]?.type ?? m.proposedType}
          onChange={(ev) => setEdit(m.column, { type: ev.target.value })}
          list="pgtypes"
        />
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
        <Button type="primary" icon={<SaveOutlined />}
          disabled={!table} loading={save.isPending} onClick={() => save.mutate()}>
          Save mapping
        </Button>
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
      )}
    </Drawer>
  );
}
