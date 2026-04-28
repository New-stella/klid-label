import { Rect, Line, Circle } from 'react-konva';

interface DrawBBox {
  type: 'bbox';
  x: number;
  y: number;
  w: number;
  h: number;
}

interface DrawPolygon {
  type: 'polygon';
  points: number[];
  mouseX: number;
  mouseY: number;
}

type DrawState = DrawBBox | DrawPolygon | null;

interface Props {
  drawState: DrawState;
}

export function DrawingLayer({ drawState }: Props) {
  if (!drawState) return null;

  if (drawState.type === 'bbox') {
    return (
      <Rect
        x={drawState.x}
        y={drawState.y}
        width={drawState.w}
        height={drawState.h}
        stroke="#3B82F6"
        strokeWidth={2}
        dash={[6, 3]}
        fill="rgba(59,130,246,0.1)"
        listening={false}
        perfectDrawEnabled={false}
      />
    );
  }

  if (drawState.type === 'polygon') {
    const { points, mouseX, mouseY } = drawState;
    if (points.length < 2) return null;

    // Draw accumulated line + preview to current mouse
    const linePoints = [...points, mouseX, mouseY];

    return (
      <>
        <Line
          points={linePoints}
          stroke="#3B82F6"
          strokeWidth={2}
          dash={[6, 3]}
          fill="rgba(59,130,246,0.1)"
          listening={false}
          perfectDrawEnabled={false}
        />
        {/* Vertex dots */}
        {Array.from({ length: points.length / 2 }, (_, i) => (
          <Circle
            key={i}
            x={points[i * 2]}
            y={points[i * 2 + 1]}
            radius={4}
            fill="#3B82F6"
            stroke="white"
            strokeWidth={1.5}
            listening={false}
            perfectDrawEnabled={false}
          />
        ))}
      </>
    );
  }

  return null;
}

export type { DrawState };
