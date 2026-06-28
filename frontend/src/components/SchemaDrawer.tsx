import {
  Drawer, Table, Tag, Alert, Button, Space, App, Typography, Spin, Modal,
} from 'antd';
import { KeyOutlined, SaveOutlined, ApartmentOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { schemaApi, projectsApi, connectionsApi } from '../api/client';
import type { ColumnInfo, Project, TableInfo, ConstraintApplyResult } from '../api/types';

const keyOf = (t: TableInfo) => `${t.schemaName}.${t.tableName}`;

function ColumnsTable({ connectionId, schema, table }: { connectionId: string; schema: string; table: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['columns', connectionId, schema, table],
    queryFn: () => schemaApi.columns(connectionId, schema, table),
  });
  if (isLoading) return <Spin size="small" />;
  return (
    <Table<ColumnInfo>
      size="small"
      rowKey="name"
      pagination={false}
      dataSource={data}
      columns={[
        {
          title: 'Column', dataIndex: 'name',
          render: (n: string, c: ColumnInfo) => (
            <Space>{c.primaryKey && <KeyOutlined style={{ color: '#faad14' }} />}{n}</Space>
          ),
        },
        { title: 'Type', dataIndex: 'dataType' },
        { title: 'Size', dataIndex: 'size' },
        { title: 'Nullable', dataIndex: 'nullable', render: (v: boolean) => (v ? 'yes' : 'no') },
      ]}
    />
  );
}

export default function SchemaDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const open = project !== null;
  const connId = project?.sourceConnectionId;
  const [selected, setSelected] = useState<string[]>([]);
  const [constraintsOpen, setConstraintsOpen] = useState(false);
  const [applyResult, setApplyResult] = useState<ConstraintApplyResult | null>(null);

  const ddl = useQuery({
    queryKey: ['constraint-ddl', project?.id],
    queryFn: () => schemaApi.constraintsDdl(project!.id),
    enabled: constraintsOpen && !!project,
  });
  const applyConstraints = useMutation({
    mutationFn: () => schemaApi.applyConstraints(project!.id),
    onSuccess: (r) => {
      setApplyResult(r);
      message[r.errors.length === 0 ? 'success' : 'warning'](
        `Applied ${r.indexes} index(es), ${r.foreignKeys} FK(s)` + (r.errors.length ? `, ${r.errors.length} error(s)` : ''));
    },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Apply failed'),
  });

  useEffect(() => {
    const saved = (project?.config?.selectedTables as string[] | undefined) ?? [];
    setSelected(saved);
  }, [project]);

  const tables = useQuery({
    queryKey: ['tables', connId],
    queryFn: () => schemaApi.tables(connId!),
    enabled: open && !!connId,
  });

  // CDC-enabled is a per-table concept only for SQL Server; other engines capture differently and
  // always report cdc=false, so the "CDC off" warning is scoped to SQL Server sources to avoid noise.
  const connections = useQuery({ queryKey: ['connections'], queryFn: connectionsApi.list, enabled: open });
  const isSqlServerSource = connections.data?.find((c) => c.id === connId)?.dbType === 'SQLSERVER';
  const selectedCdcOff = isSqlServerSource
    && selected.some((k) => tables.data?.find((t) => keyOf(t) === k && !t.cdcEnabled));

  // Bulk selection across ALL tables (not just the current page) — essential with 300+ tables where
  // paging through to tick boxes is impractical. CDC enabled/off is a SQL Server concept; for other
  // engines every table reports cdc=off, so only "All"/"Clear" are meaningful there.
  const allTables = tables.data ?? [];
  const allKeys = allTables.map(keyOf);
  const cdcKeys = allTables.filter((t) => t.cdcEnabled).map(keyOf);
  const nonCdcKeys = allTables.filter((t) => !t.cdcEnabled).map(keyOf);

  const save = useMutation({
    mutationFn: () => projectsApi.update(project!.id, {
      name: project!.name,
      description: project!.description,
      sourceConnectionId: project!.sourceConnectionId,
      targetConnectionId: project!.targetConnectionId,
      config: {
        ...project!.config,
        selectedTables: selected,
        tableIncludeList: selected.join(','),
      },
    }),
    onSuccess: () => {
      message.success(`Saved ${selected.length} table(s)`);
      qc.invalidateQueries({ queryKey: ['projects'] });
      onClose();
    },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Save failed'),
  });

  const columns = [
    { title: 'Schema', dataIndex: 'schemaName', width: 110 },
    { title: 'Table', dataIndex: 'tableName' },
    {
      title: 'Primary key', dataIndex: 'hasPrimaryKey',
      render: (v: boolean) => (v
        ? <Tag color="green">PK</Tag>
        : <Tag color="red">no PK</Tag>),
    },
    {
      title: 'CDC', dataIndex: 'cdcEnabled',
      render: (v: boolean) => (v
        ? <Tag color="blue">enabled</Tag>
        : <Tag>off</Tag>),
    },
  ];

  return (
    <Drawer
      title={project ? `Select tables — ${project.name}` : ''}
      width={820}
      open={open}
      onClose={onClose}
      extra={
        <Space>
          <Button icon={<ApartmentOutlined />} disabled={!connId}
            onClick={() => { setApplyResult(null); setConstraintsOpen(true); }}>
            Indexes &amp; FKs
          </Button>
          <Button type="primary" icon={<SaveOutlined />}
            disabled={!connId} loading={save.isPending} onClick={() => save.mutate()}>
            Save selection ({selected.length})
          </Button>
        </Space>
      }
    >
      {!connId && (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message="No source connection"
          description="Assign a SQL Server source connection to this project before discovering tables." />
      )}
      {tables.isError && (
        <Alert type="error" showIcon style={{ marginBottom: 16 }}
          message="Discovery failed" description={(tables.error as any)?.response?.data?.message} />
      )}
      {selected.some((k) => tables.data?.find((t) => keyOf(t) === k && !t.hasPrimaryKey)) && (
        <Alert type="info" showIcon style={{ marginBottom: 16 }}
          message="Some selected tables have no primary key; upsert/CDC delete handling needs one." />
      )}
      {selectedCdcOff && (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }}
          message="Some selected tables are not CDC-enabled"
          description="They'll be snapshotted once during the initial load, but ongoing inserts/updates/deletes won't stream to the target. Enable CDC on them (sys.sp_cdc_enable_table) to keep them in sync." />
      )}
      <Typography.Paragraph type="secondary">
        Expand a row to inspect columns. The selection drives the connector's table include list.
      </Typography.Paragraph>
      {allTables.length > 0 && (
        <Space wrap style={{ marginBottom: 12 }}>
          <Typography.Text type="secondary">Quick select ({allTables.length} tables):</Typography.Text>
          <Button size="small" onClick={() => setSelected(allKeys)}>All ({allKeys.length})</Button>
          {isSqlServerSource && (
            <>
              <Button size="small" onClick={() => setSelected(cdcKeys)}>CDC-enabled ({cdcKeys.length})</Button>
              <Button size="small" onClick={() => setSelected(nonCdcKeys)}>Non-CDC ({nonCdcKeys.length})</Button>
            </>
          )}
          <Button size="small" disabled={selected.length === 0} onClick={() => setSelected([])}>Clear</Button>
        </Space>
      )}
      <Table<TableInfo>
        rowKey={keyOf}
        loading={tables.isLoading}
        dataSource={tables.data}
        columns={columns}
        pagination={{ pageSize: 20, showSizeChanger: true, pageSizeOptions: ['20', '50', '100', '300'] }}
        rowSelection={{
          selectedRowKeys: selected,
          onChange: (keys) => setSelected(keys as string[]),
          preserveSelectedRowKeys: true,
        }}
        expandable={{
          expandedRowRender: (t) => connId
            ? <ColumnsTable connectionId={connId} schema={t.schemaName} table={t.tableName} />
            : null,
        }}
      />

      <Modal
        title="Replicate indexes & foreign keys"
        open={constraintsOpen}
        width={760}
        onCancel={() => setConstraintsOpen(false)}
        footer={[
          <Button key="cancel" onClick={() => setConstraintsOpen(false)}>Close</Button>,
          <Button key="apply" type="primary" loading={applyConstraints.isPending}
            onClick={() => applyConstraints.mutate()}>Apply to target</Button>,
        ]}
      >
        <Typography.Paragraph type="secondary">
          DDL generated from the source for the selected tables (indexes then foreign keys), with
          snake_cased names. Run after the initial load; idempotent. Defaults/check constraints are
          out of scope.
        </Typography.Paragraph>
        {ddl.isLoading ? <Spin /> : (
          <pre style={{
            maxHeight: 280, overflow: 'auto', background: '#f5f5f5', color: '#1E2430',
            padding: 12, fontSize: 12, borderRadius: 6, margin: 0,
          }}>
            {(ddl.data ?? []).join('\n') || '— no indexes or foreign keys found —'}
          </pre>
        )}
        {applyResult && (
          <Alert
            style={{ marginTop: 12 }}
            type={applyResult.errors.length === 0 ? 'success' : 'warning'}
            showIcon
            message={`Applied ${applyResult.indexes} index(es), ${applyResult.foreignKeys} foreign key(s)`}
            description={applyResult.errors.length
              ? <pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{applyResult.errors.join('\n')}</pre>
              : undefined}
          />
        )}
      </Modal>
    </Drawer>
  );
}
