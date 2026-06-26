import { useId } from 'react';

/**
 * A tiny dependency-free SVG sparkline: smooth line, soft area fill, emphasized endpoint.
 * Renders nothing meaningful below 2 points (shows a flat baseline).
 */
export default function Sparkline({
  data, color = '#4F46E5', width = 96, height = 32, strokeWidth = 1.75,
}: {
  data: number[];
  color?: string;
  width?: number;
  height?: number;
  strokeWidth?: number;
}) {
  const gradId = useId();
  const pad = 2;
  const w = width - pad * 2;
  const h = height - pad * 2;
  const pts = data.length >= 2 ? data : [data[0] ?? 0, data[0] ?? 0];

  const min = Math.min(...pts);
  const max = Math.max(...pts);
  const span = max - min || 1;
  const stepX = w / (pts.length - 1);
  const xy = pts.map((v, i) => [pad + i * stepX, pad + h - ((v - min) / span) * h] as const);

  const line = xy.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `${line} L${xy[xy.length - 1][0].toFixed(1)},${pad + h} L${xy[0][0].toFixed(1)},${pad + h} Z`;
  const [ex, ey] = xy[xy.length - 1];

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label="trend">
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.28" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${gradId})`} />
      <path d={line} fill="none" stroke={color} strokeWidth={strokeWidth} strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={ex} cy={ey} r={2.4} fill={color} />
    </svg>
  );
}
