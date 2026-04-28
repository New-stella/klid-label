import { useRef, useEffect } from 'react';
import { Rect, Text, Group, Transformer } from 'react-konva';
import type Konva from 'konva';
import type { LabelObject } from '../../../api/types';

interface Props {
  object: LabelObject & { type: 'bbox'; bbox: NonNullable<LabelObject['bbox']> };
  isSelected: boolean;
  onSelect: () => void;
  onChange: (patch: Partial<LabelObject>) => void;
  scale: number;
  isSelectMode: boolean;
  visible?: boolean;
  readOnly?: boolean;
}

export function BBoxShape({ object, isSelected, onSelect, onChange, scale, isSelectMode, visible = true, readOnly = false }: Props) {
  const shapeRef = useRef<Konva.Rect>(null);
  const trRef = useRef<Konva.Transformer>(null);

  useEffect(() => {
    if (isSelected && trRef.current && shapeRef.current) {
      trRef.current.nodes([shapeRef.current]);
      trRef.current.getLayer()?.batchDraw();
    }
  }, [isSelected]);

  const { x, y, w, h } = object.bbox;
  const color = object.color;
  const isAuto = object.createdBy === 'auto';

  const handleDragEnd = (e: Konva.KonvaEventObject<DragEvent>) => {
    const node = e.target as Konva.Rect;
    onChange({
      bbox: {
        x: Math.round(node.x() / scale),
        y: Math.round(node.y() / scale),
        w: object.bbox.w,
        h: object.bbox.h,
      },
    });
  };

  const handleTransformEnd = () => {
    const node = shapeRef.current;
    if (!node) return;
    const scaleX = node.scaleX();
    const scaleY = node.scaleY();
    node.scaleX(1);
    node.scaleY(1);
    onChange({
      bbox: {
        x: Math.round(node.x() / scale),
        y: Math.round(node.y() / scale),
        w: Math.round((node.width() * scaleX) / scale),
        h: Math.round((node.height() * scaleY) / scale),
      },
    });
  };

  if (!visible) return null;

  return (
    <>
      <Group>
        <Rect
          ref={shapeRef}
          x={x * scale}
          y={y * scale}
          width={w * scale}
          height={h * scale}
          stroke={color}
          strokeWidth={isSelected ? 2.5 : 2}
          dash={isAuto ? [6, 3] : undefined}
          fill={color + '30'}
          draggable={isSelectMode && !readOnly}
          onClick={onSelect}
          onTap={onSelect}
          onDragEnd={handleDragEnd}
          onTransformEnd={handleTransformEnd}
          perfectDrawEnabled={false}
        />
        {/* Label badge */}
        <Group x={x * scale} y={y * scale - 18}>
          <Rect
            x={0}
            y={0}
            width={object.labelName.length * 8 + (isAuto ? 22 : 8)}
            height={16}
            fill={color}
            cornerRadius={2}
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
      </Group>
      {isSelected && !readOnly && (
        <Transformer
          ref={trRef}
          rotateEnabled={false}
          boundBoxFunc={(oldBox, newBox) => {
            if (newBox.width < 5 || newBox.height < 5) return oldBox;
            return newBox;
          }}
        />
      )}
    </>
  );
}
