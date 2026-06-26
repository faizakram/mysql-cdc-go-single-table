import { useState } from 'react';
import {
  Button, Card, Collapse, Form, Input, Modal, Select, Space, Table, Tag, App,
} from 'antd';
import {
  PlusOutlined, DeleteOutlined, ThunderboltOutlined, TableOutlined, SwapOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { projectsApi, connectionsApi } from '../api/client';
import type { Project, ProjectRequest, ProjectStatus } from '../api/types';
import JobsDrawer from '../components/JobsDrawer';
import SchemaDrawer from '../components/SchemaDrawer';
import MappingDrawer from '../components/MappingDrawer';
import ValidationDrawer from '../components/ValidationDrawer';
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
  const [form] = Form.useForm<FormValues>();

  const { data, isLoading } = useQuery({ queryKey: ['projects'], queryFn: projectsApi.list });
  const connections = useQuery({ queryKey: ['connections'], queryFn: connectionsApi.list });

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

  const opts = (type: 'SQLSERVER' | 'POSTGRESQL') =>
    (connections.data ?? []).filter((c) => c.dbType === type)
      .map((c) => ({ value: c.id, label: `${c.name} (${c.host})` }));

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
          <Button size="small" icon={<TableOutlined />}
            disabled={!canWrite || !row.sourceConnectionId}
            onClick={() => setTablesFor(row)}>Tables</Button>
          <Button size="small" icon={<SwapOutlined />}
            disabled={!canWrite || !row.sourceConnectionId}
            onClick={() => setMappingFor(row)}>Mapping</Button>
          <Button size="small" icon={<CheckCircleOutlined />}
            disabled={!canWrite || !row.sourceConnectionId || !row.targetConnectionId}
            onClick={() => setValidateFor(row)}>Validate</Button>
          <Button size="small" type="primary" ghost icon={<ThunderboltOutlined />}
            disabled={!canWrite}
            onClick={() => setRunsFor(row)}>Runs</Button>
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
      <Table rowKey="id" loading={isLoading} dataSource={data} columns={columns} pagination={false} />

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
            <Form.Item name="sourceConnectionId" label="Source (SQL Server)" style={{ width: '50%' }}>
              <Select allowClear placeholder="Select source" options={opts('SQLSERVER')} />
            </Form.Item>
            <Form.Item name="targetConnectionId" label="Target (PostgreSQL)" style={{ width: '50%' }}>
              <Select allowClear placeholder="Select target" options={opts('POSTGRESQL')} />
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
      <SchemaDrawer project={tablesFor} onClose={() => setTablesFor(null)} />
      <MappingDrawer project={mappingFor} onClose={() => setMappingFor(null)} />
      <ValidationDrawer project={validateFor} onClose={() => setValidateFor(null)} />
    </Card>
  );
}
