import { Drawer, Form, Input, InputNumber, Select, Space, Button, App } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { projectsApi } from '../api/client';
import type { Project } from '../api/types';

const asCsv = (v: unknown) =>
  Array.isArray(v) ? (v as string[]).join(',') : (typeof v === 'string' ? v : '');

/** Edit a project's migration/CDC configuration after creation (issue #38). */
export default function ConfigDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const open = project !== null;
  const [form] = Form.useForm();

  useEffect(() => {
    if (!project) return;
    const c = project.config ?? {};
    form.setFieldsValue({
      deleteStrategy: (c.deleteStrategy as string) ?? 'SOFT',
      snapshotMode: (c.snapshotMode as string) ?? 'initial',
      topicPrefix: (c.topicPrefix as string) ?? '',
      targetSchema: (c.targetSchema as string) ?? 'public',
      tableIncludeList: (c.tableIncludeList as string) ?? 'dbo.*',
      uuidColumns: asCsv(c.uuidColumns),
      jsonColumns: asCsv(c.jsonColumns),
      schemaEvolution: (c.schemaEvolution as string) ?? 'basic',
      snapshotMaxThreads: (c.snapshotMaxThreads as number) ?? 1,
      snapshotFetchSize: (c.snapshotFetchSize as number) ?? 2000,
    });
  }, [project, form]);

  const save = useMutation({
    mutationFn: async () => {
      const v = await form.validateFields();
      return projectsApi.update(project!.id, {
        name: project!.name,
        description: project!.description,
        sourceConnectionId: project!.sourceConnectionId,
        targetConnectionId: project!.targetConnectionId,
        config: {
          ...project!.config,
          deleteStrategy: v.deleteStrategy,
          snapshotMode: v.snapshotMode,
          topicPrefix: v.topicPrefix || undefined,
          targetSchema: v.targetSchema,
          tableIncludeList: v.tableIncludeList,
          uuidColumns: v.uuidColumns,
          jsonColumns: v.jsonColumns,
          schemaEvolution: v.schemaEvolution,
          snapshotMaxThreads: v.snapshotMaxThreads,
          snapshotFetchSize: v.snapshotFetchSize,
        },
      });
    },
    onSuccess: () => {
      message.success('Configuration saved');
      qc.invalidateQueries({ queryKey: ['projects'] });
      onClose();
    },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Save failed'),
  });

  return (
    <Drawer
      title={project ? `Configure — ${project.name}` : ''}
      width={560}
      open={open}
      onClose={onClose}
      extra={<Button type="primary" icon={<SaveOutlined />} loading={save.isPending}
        onClick={() => save.mutate()}>Save</Button>}
    >
      <Form form={form} layout="vertical">
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
          tooltip="Debezium regex, e.g. dbo.* or dbo.Employee,dbo.Orders">
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
        <Space.Compact block>
          <Form.Item name="schemaEvolution" label="Schema evolution" style={{ width: '34%' }}
            tooltip="JDBC sink DDL evolution when source schema changes (#26)">
            <Select options={[{ value: 'basic', label: 'basic' }, { value: 'none', label: 'none' }]} />
          </Form.Item>
          <Form.Item name="snapshotMaxThreads" label="Snapshot threads" style={{ width: '33%' }}
            tooltip="Parallel snapshot workers for large tables (#27)">
            <InputNumber min={1} max={32} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="snapshotFetchSize" label="Snapshot fetch size" style={{ width: '33%' }}>
            <InputNumber min={100} max={100000} step={500} style={{ width: '100%' }} />
          </Form.Item>
        </Space.Compact>
      </Form>
    </Drawer>
  );
}
