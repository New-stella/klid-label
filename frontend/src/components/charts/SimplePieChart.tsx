import { Cell, Pie, PieChart, Tooltip } from 'recharts';

export interface SimplePieChartProps {
  data: { label: string; value: number; color: string }[];
  size?: number;
  showLegend?: boolean;
}

export function SimplePieChart({ data, size = 160, showLegend = false }: SimplePieChartProps) {
  return (
    <div className="flex flex-col items-center gap-2">
      <PieChart width={size} height={size}>
        <Pie
          data={data}
          dataKey="value"
          nameKey="label"
          cx="50%"
          cy="50%"
          outerRadius={60}
        >
          {data.map((entry) => (
            <Cell key={entry.label} fill={entry.color} />
          ))}
        </Pie>
        <Tooltip formatter={(value, name) => [value, name]} />
      </PieChart>
      {showLegend && (
        <div className="flex flex-col gap-1 w-full">
          {data.map((entry) => (
            <div key={entry.label} className="flex items-center gap-1.5 text-xs">
              <span
                className="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
                style={{ backgroundColor: entry.color }}
              />
              <span className="text-gray-600 truncate">{entry.label}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
