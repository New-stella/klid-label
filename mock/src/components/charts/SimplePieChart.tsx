interface PieDataItem {
  label: string;
  value: number;
  color: string;
}

interface SimplePieChartProps {
  data: PieDataItem[];
  size?: number;
  showLegend?: boolean;
}

function polarToCartesian(cx: number, cy: number, r: number, angleDeg: number) {
  const rad = ((angleDeg - 90) * Math.PI) / 180;
  return {
    x: cx + r * Math.cos(rad),
    y: cy + r * Math.sin(rad),
  };
}

function arcPath(cx: number, cy: number, r: number, startAngle: number, endAngle: number): string {
  const start = polarToCartesian(cx, cy, r, endAngle);
  const end = polarToCartesian(cx, cy, r, startAngle);
  const largeArc = endAngle - startAngle > 180 ? 1 : 0;
  return `M ${cx} ${cy} L ${start.x} ${start.y} A ${r} ${r} 0 ${largeArc} 0 ${end.x} ${end.y} Z`;
}

export function SimplePieChart({ data, size = 160, showLegend = true }: SimplePieChartProps) {
  const total = data.reduce((s, d) => s + d.value, 0);
  if (total === 0) return null;

  const cx = size / 2;
  const cy = size / 2;
  const r = size / 2 - 8;

  let cumAngle = 0;
  const slices = data.map((d) => {
    const angle = (d.value / total) * 360;
    const startAngle = cumAngle;
    cumAngle += angle;
    return { ...d, startAngle, endAngle: cumAngle, angle };
  });

  return (
    <div className="flex items-center gap-6">
      <svg
        viewBox={`0 0 ${size} ${size}`}
        width={size}
        height={size}
        aria-label="원형 차트"
        role="img"
        className="shrink-0"
      >
        {slices.map((s, i) => (
          <path
            key={i}
            d={arcPath(cx, cy, r, s.startAngle, s.endAngle)}
            fill={s.color}
            stroke="white"
            strokeWidth={1.5}
          >
            <title>{`${s.label}: ${s.value} (${((s.value / total) * 100).toFixed(1)}%)`}</title>
          </path>
        ))}
        {/* Center donut hole */}
        <circle cx={cx} cy={cy} r={r * 0.45} fill="white" />
        <text x={cx} y={cy + 4} textAnchor="middle" fontSize={11} fontWeight="600" fill="#374151">
          {total >= 1000 ? `${(total / 1000).toFixed(1)}k` : total}
        </text>
      </svg>

      {showLegend && (
        <ul className="space-y-1.5 flex-1 min-w-0">
          {slices.map((s, i) => (
            <li key={i} className="flex items-center justify-between gap-2 text-xs">
              <span className="flex items-center gap-1.5 min-w-0">
                <span
                  className="w-2.5 h-2.5 rounded-full shrink-0"
                  style={{ backgroundColor: s.color }}
                />
                <span className="truncate text-gray-600">{s.label}</span>
              </span>
              <span className="font-medium text-gray-800 tabular-nums shrink-0">
                {((s.value / total) * 100).toFixed(0)}%
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default SimplePieChart;
