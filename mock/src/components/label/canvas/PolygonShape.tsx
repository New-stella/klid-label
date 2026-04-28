import { Circle, Line, Group, Text, Rect } from 'react-konva';
import type Konva from 'konva';
import type { LabelObject } from '../../../api/types';

interface Props {
  object: LabelObject & { type: 'polygon'; points: number[] };
  isSelected: boolean;
  onSelect: () => void;
  onChange: (patch: Partial<LabelObject>) => void;
  scale: number;
  isSelectMode: boolean;
  visible?: boolean;
  readOnly?: boolean;
}

export function PolygonShape({ object, isSelected, onSelect, onChange, scale, isSelectMode, visible = true, readOnly = false }: Props) {
  const { points, color } = object;
  const isAuto = object.createdBy === 'auto';

  // Scale points to canvas coords
  const scaledPoints = points.map((p, i) => (i % 2 === 0 ? p * scale : p * scale));

  // Centroid for label badge
  const pairCount = points.length / 2;
  let cx = 0;
  let cy = 0;
  for (let i = 0; i < points.length; i += 2) {
    cx += points[i];
    cy += points[i + 1];
  }
  cx = (cx / pairCount) * scale;
  cy = (cy / pairCount) * scale;

  const handleVertexDrag = (idx: number, e: Konva.KonvaEventObject<DragEvent>) => {
    if (!isSelectMode) return;
    const node = e.target as Konva.Circle;
    const newPoints = [...points];
    newPoints[idx * 2] = Math.round(node.x() / scale);
    newPoints[idx * 2 + 1] = Math.round(node.y() / scale);
    onChange({ points: newPoints });
  };

  const handleVertexContextMenu = (idx: number, e: Konva.KonvaEventObject<MouseEvent>) => {
    e.evt.preventDefault();
    if (points.length / 2 <= 3) return; // min 3 points
    const newPoints = [...points];
    newPoints.splice(idx * 2, 2);
    onChange({ points: newPoints });
  };

  if (!visible) return null;

  return (
    <Group>
      <Line
        points={scaledPoints}
        closed
        stroke={color}
        strokeWidth={isSelected ? 2.5 : 2}
        dash={isAuto ? [6, 3] : undefined}
        fill={color + '30'}
        onClick={onSelect}
        onTap={onSelect}
        perfectDrawEnabled={false}
        listening
      />
      {/* Label badge at centroid */}
      <Group x={cx - (object.labelName.length * 4 + (isAuto ? 11 : 4))} y={cy - 8}>
        <Rect
          x={0}
          y={0}
          width={object.labelName.length * 8 + (isAuto ? 22 : 8)}
          height={16}
          fill={color}
          cornerRadius={2}
          listening={false}
        />
        <Text
          x={4}
          y={2}
          text={(isAuto ? '🤖 ' : '') + object.labelName}
          fontSize={10}
          fill="white"
          fontStyle="bold"
          listening={false}
        />
      </Group>
      {/* Vertex handles when selected */}
      {isSelected && !readOnly &&
        Array.from({ length: points.length / 2 }, (_, i) => (
          <Circle
            key={i}
            x={points[i * 2] * scale}
            y={points[i * 2 + 1] * scale}
            radius={5}
            fill={color}
            stroke="white"
            strokeWidth={1.5}
            draggable={isSelectMode}
            onDragEnd={(e) => handleVertexDrag(i, e)}
            onContextMenu={(e) => handleVertexContextMenu(i, e)}
            perfectDrawEnabled={false}
          />
        ))}
    </Group>
  );
}
