interface BarDataItem {
  label: string;
  value: number;
}

interface SimpleBarChartProps {
  data: BarDataItem[];
  height?: number;
  xAxisInterval?: number;
  color?: string;
  showValues?: boolean;
}

export function SimpleBarChart({
  data,
  height = 200,
  xAxisInterval = 5,
  color = '#3b82f6',
  showValues = false,
}: SimpleBarChartProps) {
  if (data.length === 0) return null;

  const maxValue = Math.max(...data.map((d) => d.value), 1);
  const padTop = 20;
  const padBottom = 36;
  const padLeft = 40;
  const padRight = 8;
  const chartH = height - padTop - padBottom;

  // Y-axis ticks (5 levels)
  const yTicks = [0, 0.25, 0.5, 0.75, 1].map((t) => ({
    value: Math.round(maxValue * t),
    y: padTop + chartH * (1 - t),
  }));

  // Viewbox width fixed at 800 for scalability
  const vbW = 800;
  const vbH = height;
  const usableW = vbW - padLeft - padRight;
  const barCount = data.length;
  const bw = Math.max(2, usableW / barCount - 1);

  return (
    <svg
      viewBox={`0 0 ${vbW} ${vbH}`}
      className="w-full"
      style={{ height }}
      aria-label="막대 그래프"
      role="img"
    >
      {/* Y-axis grid lines + labels */}
      {yTicks.map((tick) => (
        <g key={tick.value}>
          <line
            x1={padLeft}
            x2={vbW - padRight}
            y1={tick.y}
            y2={tick.y}
            stroke="#e5e7eb"
            strokeWidth={1}
          />
          <text
            x={padLeft - 6}
            y={tick.y + 4}
            textAnchor="end"
            fontSize={10}
            fill="#9ca3af"
          >
            {tick.value >= 1000 ? `${(tick.value / 1000).toFixed(1)}k` : tick.value}
          </text>
        </g>
      ))}

      {/* Bars */}
      {data.map((d, i) => {
        const barH = Math.max(1, (d.value / maxValue) * chartH);
        const x = padLeft + i * (usableW / barCount) + (usableW / barCount - bw) / 2;
        const y = padTop + chartH - barH;

        return (
          <g key={i}>
            <rect
              x={x}
              y={y}
              width={bw}
              height={barH}
              fill={color}
              rx={2}
              opacity={0.85}
            >
              <title>{`${d.label}: ${d.value}`}</title>
            </rect>
            {showValues && d.value > 0 && (
              <text
                x={x + bw / 2}
                y={y - 3}
                textAnchor="middle"
                fontSize={9}
                fill="#6b7280"
              >
                {d.value}
              </text>
            )}
            {/* X-axis label */}
            {i % xAxisInterval === 0 && (
              <text
                x={x + bw / 2}
                y={padTop + chartH + 16}
                textAnchor="middle"
                fontSize={9}
                fill="#9ca3af"
              >
                {d.label.length > 5 ? d.label.slice(5) : d.label}
              </text>
            )}
          </g>
        );
      })}

      {/* X-axis line */}
      <line
        x1={padLeft}
        x2={vbW - padRight}
        y1={padTop + chartH}
        y2={padTop + chartH}
        stroke="#d1d5db"
        strokeWidth={1}
      />
    </svg>
  );
}

export default SimpleBarChart;
