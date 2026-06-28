import { Table, Tag, Statistic, Row, Col, Empty, Typography, Badge, Tooltip } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import { useEffect, useRef, useState } from 'react';
import { tokenStore } from '../auth/token';
import type { LiveSnapshot, LiveTableThroughput } from '../api/types';

const rate = (v: number) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v.toFixed(1)}/s</span>;
const opCell = (color: string) => (v: number) =>
  <span style={{ color: v > 0 ? color : undefined, fontVariantNumeric: 'tabular-nums' }}>{v.toLocaleString()}</span>;

function lagTag(ms: number) {
  if (ms <= 0) return <Tag>—</Tag>;
  const s = ms / 1000;
  const color = s < 3 ? 'green' : s < 10 ? 'gold' : 'red';
  return <Tag color={color}>{s < 1 ? `${ms} ms` : `${s.toFixed(1)} s`}</Tag>;
}

export default function LiveStream() {
  const [snap, setSnap] = useState<LiveSnapshot | null>(null);
  const [connected, setConnected] = useState(false);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    const token = tokenStore.get();
    const es = new EventSource(`/api/v1/monitoring/live/stream?token=${encodeURIComponent(token ?? '')}`);
    esRef.current = es;
    es.addEventListener('open', () => setConnected(true));
    es.addEventListener('live', (e) => {
      try { setSnap(JSON.parse((e as MessageEvent).data)); setConnected(true); } catch { /* ignore */ }
    });
    es.addEventListener('error', () => setConnected(false));   // EventSource auto-reconnects
    return () => es.close();
  }, []);

  const tables = snap?.tables ?? [];
  const active = tables.filter((t) => t.eventsPerSec > 0).length;

  return (
    <>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Statistic
            title="Live throughput"
            value={snap?.totalEventsPerSec ?? 0}
            precision={1}
            suffix="ev/s"
            prefix={<ThunderboltOutlined style={{ color: '#1677ff' }} />}
          />
        </Col>
        <Col span={6}><Statistic title="Active tables" value={active} suffix={`/ ${tables.length}`} /></Col>
        <Col span={6}>
          <Statistic title="Stream" valueRender={() => (
            <Badge status={connected ? 'processing' : 'default'} text={connected ? 'Connected' : 'Reconnecting…'} />
          )} />
        </Col>
      </Row>

      <Typography.Paragraph type="secondary">
        Real-time tail of the Debezium CDC stream (Kafka) — per-table insert/update/delete rates and
        replication lag, pushed live as events flow. Independent consumer; no effect on the sink.
      </Typography.Paragraph>

      {tables.length === 0
        ? <Empty description="No CDC events yet. Start a migration and write to the source to see the stream." />
        : (
          <Table<LiveTableThroughput>
            rowKey="table"
            size="small"
            dataSource={tables}
            pagination={false}
            scroll={{ x: 'max-content' }}
            columns={[
              { title: 'Table', dataIndex: 'table' },
              { title: 'Rate', dataIndex: 'eventsPerSec', render: rate, defaultSortOrder: 'descend',
                sorter: (a, b) => a.eventsPerSec - b.eventsPerSec },
              { title: <Tooltip title="Inserts (op=c)">Inserts</Tooltip>, dataIndex: 'inserts', render: opCell('#3f8600') },
              { title: <Tooltip title="Updates (op=u)">Updates</Tooltip>, dataIndex: 'updates', render: opCell('#1677ff') },
              { title: <Tooltip title="Deletes (op=d)">Deletes</Tooltip>, dataIndex: 'deletes', render: opCell('#cf1322') },
              { title: <Tooltip title="Snapshot reads (op=r)">Snapshot</Tooltip>, dataIndex: 'reads', render: opCell('#8c8c8c') },
              { title: 'Total', dataIndex: 'total', render: (v: number) => v.toLocaleString() },
              { title: <Tooltip title="now − source commit time of the latest event">Lag</Tooltip>,
                dataIndex: 'lastLagMs', render: lagTag },
            ]}
          />
        )}
    </>
  );
}
