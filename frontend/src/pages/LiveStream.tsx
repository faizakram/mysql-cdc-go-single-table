import { Typography } from 'antd';
import LiveStreamView from '../components/LiveStreamView';

/** All-projects live CDC stream (overview). Per-project streams open from the Projects table. */
export default function LiveStream() {
  return (
    <>
      <Typography.Paragraph type="secondary">
        Real-time tail of the Debezium CDC stream (Kafka) across all projects — per-table
        insert/update/delete rates and replication lag, pushed live as events flow. Independent
        consumer; no effect on the sinks. Open a single project from the Projects page for its own stream.
      </Typography.Paragraph>
      <LiveStreamView streamPath="/api/v1/monitoring/live/stream" showProject />
    </>
  );
}
