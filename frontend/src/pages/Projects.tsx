import { useState } from 'react';
import {
  Button, Card, Collapse, Form, Input, Modal, Select, Space, Table, Tag, App,
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, ThunderboltOutlined, TableOutlined, SwapOutlined,
  CheckCircleOutlined, SettingOutlined, ProfileOutlined, ClockCircleOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { projectsApi, connectionsApi } from '../api/client';
import type { Project, ProjectRequest, ProjectStatus, EngineSpec } from '../api/types';
import JobsDrawer from '../components/JobsDrawer';
import SchemaDrawer from '../components/SchemaDrawer';
import MappingDrawer from '../components/MappingDrawer';
import ValidationDrawer from '../components/ValidationDrawer';
import LiveStreamDrawer from '../components/LiveStreamDrawer';
import ConfigDrawer from '../components/ConfigDrawer';
import ErrorsDrawer from '../components/ErrorsDrawer';
import SchedulesDrawer from '../components/SchedulesDrawer';
import PlanDrawer from '../components/PlanDrawer';
import SchemaObjectsDrawer from '../components/SchemaObjectsDrawer';
import EmptyState from '../components/EmptyState';
import { ProjectOutlined, PartitionOutlined, ApartmentOutlined, ExperimentOutlined } from '@ant-design/icons';
import { useAuth } from '../auth/AuthContext';

const STATUS_COLOR: Record<ProjectStatus, string> = {
  DRAFT: 'default', READY: 'blue', ACTIVE: 'green', ARCHIVED: 'gold',
};

interface FormValues {
  name: string;
  description?: string;
  sourceConnectionId?: string;
  targetConnectionId?: string;
  topicPrefix?: string;
  tableIncludeList?: string;
  snapshotMode?: string;
  deleteStrategy?: string;
  targetSchema?: string;
  uuidColumns?: string;
  jsonColumns?: string;
}

export default function Projects() {
  const { message, modal } = App.useApp();
  const { user } = useAuth();
  const canWrite = user?.role !== 'VIEWER';
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [runsFor, setRunsFor] = useState<Project | null>(null);
  const [tablesFor, setTablesFor] = useState<Project | null>(null);
  const [mappingFor, setMappingFor] = useState<Project | null>(null);
  const [validateFor, setValidateFor] = useState<Project | null>(null);
  const [liveFor, setLiveFor] = useState<Project | null>(null);
  const [configFor, setConfigFor] = useState<Project | null>(null);
  const [errorsFor, setErrorsFor] = useState<Project | null>(null);
  const [schedulesFor, setSchedulesFor] = useState<Project | null>(null);
  const [planFor, setPlanFor] = useState<Project | null>(null);
  const [objectsFor, setObjectsFor] = useState<Project | null>(null);
  const [form] = Form.useForm<FormValues>();

  const PAGE_SIZE = 20;
  const [page, setPage] = useState(0);
  const [q, setQ] = useState<string | undefined>(undefined);
  const [status, setStatus] = useState<ProjectStatus | undefined>(undefined);
  const { data, isLoading } = useQuery({
    queryKey: ['projects', 'page', page, q, status],
    queryFn: () => projectsApi.page({ page, size: PAGE_SIZE, q, status }),
  });
  const connections = useQuery({ queryKey: ['connections'], queryFn: connectionsApi.list });
  const engines = useQuery({ queryKey: ['engines'], queryFn: connectionsApi.engines });

  const create = useMutation({
    mutationFn: (body: ProjectRequest) => projectsApi.create(body),
    onSuccess: () => {
      message.success('Project created');
      qc.invalidateQueries({ queryKey: ['projects'] });
      setOpen(false);
      form.resetFields();
    },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Create failed'),
  });

  const remove = useMutation({
    mutationFn: projectsApi.remove,
    onSuccess: () => {
      message.success('Project deleted');
      qc.invalidateQueries({ queryKey: ['projects'] });
    },
  });

  const submit = (v: FormValues) => {
    const config: Record<string, unknown> = {
      topicPrefix: v.topicPrefix,
      tableIncludeList: v.tableIncludeList,
      snapshotMode: v.snapshotMode,
      deleteStrategy: v.deleteStrategy,
      targetSchema: v.targetSchema,
      uuidColumns: v.uuidColumns,
      jsonColumns: v.jsonColumns,
    };
    Object.keys(config).forEach((k) => (config[k] === undefined || config[k] === '') && delete config[k]);
    create.mutate({
      name: v.name,
      description: v.description,
      sourceConnectionId: v.sourceConnectionId,
      targetConnectionId: v.targetConnectionId,
      config,
    });
  };

  // Any engine can be a source and/or target (#76) — drive the option lists from each engine's
  // capability flags instead of hard-coding SQL Server → PostgreSQL. Targets exclude source-only
  // engines (e.g. MongoDB, canSink=false).
  const engineByType = new Map<string, EngineSpec>((engines.data ?? []).map((e) => [e.type, e]));
  const connOpts = (capable: (e: EngineSpec) => boolean) =>
    (connections.data ?? [])
      .filter((c) => { const e = engineByType.get(c.dbType); return e ? capable(e) : true; })
      .map((c) => {
        const e = engineByType.get(c.dbType);
        return { value: c.id, label: `${c.name} (${e?.displayName ?? c.dbType} — ${c.host})` };
      });

  const columns = [
    { title: 'Name', dataIndex: 'name' },
    {
      title: 'Status', dataIndex: 'status',
      render: (s: ProjectStatus) => <Tag color={STATUS_COLOR[s]}>{s}</Tag>,
    },
    { title: 'Description', dataIndex: 'description', ellipsis: true },
    {
      title: 'Actions',
      render: (_: unknown, row: Project) => (
        <Space>
          <Button size="small" icon={<SettingOutlined />}
            disabled={!canWrite}
            onClick={() => setConfigFor(row)}>Configure</Button>
          <Button size="small" icon={<TableOutlined />}
            disabled={!canWrite || !row.sourceConnectionId}
            onClick={() => setTablesFor(row)}>Tables</Button>
          <Button size="small" icon={<SwapOutlined />}
            disabled={!canWrite || !row.sourceConnectionId}
            onClick={() => setMappingFor(row)}>Mapping</Button>
          <Button size="small" icon={<CheckCircleOutlined />}
            disabled={!canWrite || !row.sourceConnectionId || !row.targetConnectionId}
            onClick={() => setValidateFor(row)}>Validate</Button>
          <Button size="small" icon={<ExperimentOutlined />}
            onClick={async () => {
              const hide = message.loading('Running dry-run…', 0);
              try {
                const r = await projectsApi.dryRun(row.id);
                hide();
                modal[r.ok ? 'success' : 'error']({
                  title: `Dry-run — ${r.ok ? 'ready to migrate' : 'blockers found'}`,
                  width: 560,
                  content: (
                    <div style={{ marginTop: 8 }}>
                      <p>Source: {r.source?.success ? '✓' : '✗'} · Target: {r.target?.success ? '✓' : '✗'}
                        {r.plan ? ` · ${r.plan.tables.length} tables, ~${r.plan.estimatedSeconds}s` : ''}</p>
                      {r.blockers.length > 0 && <><b style={{ color: '#cf1322' }}>Blockers</b>
                        <ul>{r.blockers.map((b: string) => <li key={b}>{b}</li>)}</ul></>}
                      {r.warnings.length > 0 && <><b style={{ color: '#d97706' }}>Warnings</b>
                        <ul>{r.warnings.map((w: string) => <li key={w}>{w}</li>)}</ul></>}
                      {r.ok && r.warnings.length === 0 && <p>No issues — connectivity, plan and types all check out.</p>}
                    </div>
                  ),
                });
              } catch (e: any) { hide(); message.error(e?.response?.data?.message ?? 'Dry-run failed'); }
            }}>Dry-run</Button>
          <Button size="small" icon={<PartitionOutlined />}
            onClick={() => setPlanFor(row)}>Plan</Button>
          <Button size="small" icon={<ApartmentOutlined />}
            onClick={() => setObjectsFor(row)}>Objects</Button>
          <Button size="small" icon={<ClockCircleOutlined />}
            onClick={() => setSchedulesFor(row)}>Schedules</Button>
          <Button size="small" type="primary" ghost icon={<ThunderboltOutlined />}
            disabled={!canWrite}
            onClick={() => setRunsFor(row)}>Runs</Button>
          <Button size="small" icon={<ProfileOutlined />}
            onClick={() => setErrorsFor(row)}>Logs</Button>
          <Button size="small" icon={<ThunderboltOutlined />}
            onClick={() => setLiveFor(row)}>Live</Button>
          <Button size="small" danger icon={<DeleteOutlined />} disabled={!canWrite}
            onClick={() => modal.confirm({
              title: `Delete project "${row.name}"?`,
              onOk: () => remove.mutate(row.id),
            })} />
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="Migration projects"
      extra={
        <Button type="primary" icon={<PlusOutlined />} disabled={!canWrite} onClick={() => setOpen(true)}>
          New project
        </Button>
      }
    >
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search allowClear placeholder="Search name / description" style={{ width: 300 }}
          onSearch={(v) => { setQ(v || undefined); setPage(0); }}
          onChange={(e) => { if (!e.target.value) { setQ(undefined); setPage(0); } }} />
        <Select allowClear placeholder="All statuses" style={{ width: 160 }} value={status}
          onChange={(v) => { setStatus(v); setPage(0); }}
          options={(['DRAFT', 'READY', 'ACTIVE', 'ARCHIVED'] as ProjectStatus[]).map((s) => ({ value: s, label: s }))} />
      </Space>
      <Table rowKey="id" loading={isLoading} dataSource={data?.content} columns={columns}
        pagination={{
          current: page + 1, pageSize: PAGE_SIZE, total: data?.total ?? 0,
          showSizeChanger: false, hideOnSinglePage: true, onChange: (p) => setPage(p - 1),
        }}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: !isLoading && (
          <EmptyState icon={<ProjectOutlined />} title={q || status ? 'No matches' : 'No projects yet'}
            description={q || status ? 'Try a different search or filter.'
              : 'Create a project to link a source and target connection, then run a migration.'} />
        ) }} />

      <Modal
        title="New migration project"
        open={open}
        onCancel={() => setOpen(false)}
        confirmLoading={create.isPending}
        width={640}
        onOk={async () => submit(await form.validateFields())}
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            snapshotMode: 'initial', deleteStrategy: 'SOFT',
            targetSchema: 'public', tableIncludeList: 'dbo.*',
          }}
        >
          <Form.Item name="name" label="Name" rules={[{ required: true }]}>
            <Input placeholder="Employees MSSQL → PG" />
          </Form.Item>
          <Form.Item name="description" label="Description">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Space.Compact block>
            <Form.Item name="sourceConnectionId" label="Source" style={{ width: '50%' }}>
              <Select allowClear placeholder="Select source"
                loading={connections.isLoading || engines.isLoading}
                options={connOpts((e) => e.canSource)} />
            </Form.Item>
            <Form.Item name="targetConnectionId" label="Target" style={{ width: '50%' }}>
              <Select allowClear placeholder="Select target"
                loading={connections.isLoading || engines.isLoading}
                options={connOpts((e) => e.canSink)} />
            </Form.Item>
          </Space.Compact>

          <Collapse
            ghost
            items={[{
              key: 'cdc',
              label: 'CDC configuration',
              children: (
                <>
                  <Space.Compact block>
                    <Form.Item name="deleteStrategy" label="Delete strategy" style={{ width: '50%' }}>
                      <Select options={[
                        { value: 'SOFT', label: 'Soft (mark __cdc_deleted)' },
                        { value: 'HARD', label: 'Hard (remove row)' },
                      ]} />
                    </Form.Item>
                    <Form.Item name="snapshotMode" label="Snapshot mode" style={{ width: '50%' }}>
                      <Select options={[
                        { value: 'initial', label: 'initial (snapshot + CDC)' },
                        { value: 'schema_only', label: 'schema_only (CDC only)' },
                        { value: 'no_data', label: 'no_data' },
                      ]} />
                    </Form.Item>
                  </Space.Compact>
                  <Space.Compact block>
                    <Form.Item name="topicPrefix" label="Topic prefix" style={{ width: '50%' }}>
                      <Input placeholder="defaults to project slug" />
                    </Form.Item>
                    <Form.Item name="targetSchema" label="Target schema" style={{ width: '50%' }}>
                      <Input placeholder="public" />
                    </Form.Item>
                  </Space.Compact>
                  <Form.Item name="tableIncludeList" label="Table include list"
                    tooltip="Debezium regex of source tables, e.g. dbo.* or dbo.Employees,dbo.Orders">
                    <Input placeholder="dbo.*" />
                  </Form.Item>
                  <Space.Compact block>
                    <Form.Item name="uuidColumns" label="UUID columns" style={{ width: '50%' }}>
                      <Input placeholder="user_id,session_id" />
                    </Form.Item>
                    <Form.Item name="jsonColumns" label="JSON columns" style={{ width: '50%' }}>
                      <Input placeholder="metadata,settings" />
                    </Form.Item>
                  </Space.Compact>
                </>
              ),
            }]}
          />
        </Form>
      </Modal>

      <JobsDrawer project={runsFor} onClose={() => setRunsFor(null)} />
      <LiveStreamDrawer project={liveFor} onClose={() => setLiveFor(null)} />
      <SchemaDrawer project={tablesFor} onClose={() => setTablesFor(null)} />
      <MappingDrawer project={mappingFor} onClose={() => setMappingFor(null)} />
      <ValidationDrawer project={validateFor} onClose={() => setValidateFor(null)} />
      <ConfigDrawer project={configFor} onClose={() => setConfigFor(null)} />
      <ErrorsDrawer project={errorsFor} onClose={() => setErrorsFor(null)} />
      <SchedulesDrawer project={schedulesFor} onClose={() => setSchedulesFor(null)} />
      <PlanDrawer project={planFor} onClose={() => setPlanFor(null)} />
      <SchemaObjectsDrawer project={objectsFor} onClose={() => setObjectsFor(null)} />
    </Card>
  );
}
