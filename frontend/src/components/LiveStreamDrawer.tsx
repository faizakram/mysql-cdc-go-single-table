import { Drawer } from 'antd';
import type { Project } from '../api/types';
import LiveStreamView from './LiveStreamView';

/** Per-project live CDC stream (#168) — scoped to one project's topics. */
export default function LiveStreamDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const open = project !== null;
  return (
    <Drawer
      title={project ? `Live stream — ${project.name}` : ''}
      width={typeof window !== 'undefined' ? Math.min(1100, window.innerWidth - 64) : 1100}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {project && (
        <LiveStreamView streamPath={`/api/v1/projects/${project.id}/monitoring/live/stream`} />
      )}
    </Drawer>
  );
}
