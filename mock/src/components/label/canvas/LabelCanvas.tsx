import { useRef, useState, useEffect, useCallback } from 'react';
import { Stage, Layer, Image as KonvaImage } from 'react-konva';
import type Konva from 'konva';
import { useLabelStore } from '../../../store/labelStore';
import { useImage } from '../../../hooks/useImage';
import { BBoxShape } from './BBoxShape';
import { PolygonShape } from './PolygonShape';
import { DrawingLayer } from './DrawingLayer';
import type { DrawState } from './DrawingLayer';
import type { LabelObject } from '../../../api/types';
import { labelColor, labelName } from '../../../utils/labelColors';

const IMG_WIDTH = 640;
const IMG_HEIGHT = 360;

function generateId(): string {
  return `obj-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

interface Props {
  videoId: string;
  frameNo: number;
  readOnly?: boolean;
  onCanvasClick?: (x: number, y: number) => void;
  /** Extra Konva layers rendered on top (e.g. IssueMarkerLayer). Receives current scale. */
  extraLayers?: (scale: number) => React.ReactNode;
}

export function LabelCanvas({ videoId, frameNo, readOnly = false, onCanvasClick, extraLayers }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [stageSize, setStageSize] = useState({ width: 640, height: 360 });
  const [scale, setScale] = useState(1);

  const tool = useLabelStore((s) => s.tool);
  const frames = useLabelStore((s) => s.frames);
  const selectedId = useLabelStore((s) => s.selectedId);
  const selectObject = useLabelStore((s) => s.selectObject);
  const addObject = useLabelStore((s) => s.addObject);
  const updateObject = useLabelStore((s) => s.updateObject);

  const objects = frames[frameNo] ?? [];

  // Responsive stage sizing
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const resize = () => {
      const rect = el.getBoundingClientRect();
      const w = rect.width;
      const h = rect.height;
      // Maintain 16:9 ratio (640x360)
      const scaleX = w / IMG_WIDTH;
      const scaleY = h / IMG_HEIGHT;
      const s = Math.min(scaleX, scaleY);
      setScale(s);
      setStageSize({ width: IMG_WIDTH * s, height: IMG_HEIGHT * s });
    };

    const observer = new ResizeObserver(resize);
    observer.observe(el);
    resize();
    return () => observer.disconnect();
  }, []);

  // Frame image
  const imageSrc = `https://picsum.photos/seed/${videoId}-f${frameNo}/640/360`;
  const [img, imgStatus] = useImage(imageSrc);

  // Drawing state
  const [drawState, setDrawState] = useState<DrawState>(null);
  const bboxStartRef = useRef<{ x: number; y: number } | null>(null);
  const polygonPointsRef = useRef<number[]>([]);

  // Cancel polygon on Escape
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && tool === 'polygon') {
        polygonPointsRef.current = [];
        setDrawState(null);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [tool]);

  // Reset draw state when tool changes
  useEffect(() => {
    setDrawState(null);
    bboxStartRef.current = null;
    polygonPointsRef.current = [];
  }, [tool]);

  const getCanvasPos = useCallback(
    (stageX: number, stageY: number) => ({ x: stageX, y: stageY }),
    [],
  );

  const handleStageMouseDown = useCallback(
    (e: Konva.KonvaEventObject<MouseEvent>) => {
      if (readOnly) return;
      if (tool !== 'bbox') return;
      const stage = e.target.getStage();
      if (!stage) return;
      const pos = stage.getPointerPosition();
      if (!pos) return;
      bboxStartRef.current = getCanvasPos(pos.x, pos.y);
    },
    [readOnly, tool, getCanvasPos],
  );

  const handleStageMouseMove = useCallback(
    (e: Konva.KonvaEventObject<MouseEvent>) => {
      if (readOnly) return;
      if (tool === 'bbox' && bboxStartRef.current) {
        const stage = e.target.getStage();
        if (!stage) return;
        const pos = stage.getPointerPosition();
        if (!pos) return;
        const start = bboxStartRef.current;
        setDrawState({
          type: 'bbox',
          x: Math.min(start.x, pos.x),
          y: Math.min(start.y, pos.y),
          w: Math.abs(pos.x - start.x),
          h: Math.abs(pos.y - start.y),
        });
      }
      if (tool === 'polygon' && polygonPointsRef.current.length >= 2) {
        const stage = e.target.getStage();
        if (!stage) return;
        const pos = stage.getPointerPosition();
        if (!pos) return;
        setDrawState({
          type: 'polygon',
          points: [...polygonPointsRef.current],
          mouseX: pos.x,
          mouseY: pos.y,
        });
      }
    },
    [readOnly, tool],
  );

  const handleStageMouseUp = useCallback(
    (e: Konva.KonvaEventObject<MouseEvent>) => {
      if (readOnly) return;
      if (tool !== 'bbox' || !bboxStartRef.current) return;
      const stage = e.target.getStage();
      if (!stage) return;
      const pos = stage.getPointerPosition();
      if (!pos) return;
      const start = bboxStartRef.current;
      const sx = Math.min(start.x, pos.x);
      const sy = Math.min(start.y, pos.y);
      const sw = Math.abs(pos.x - start.x);
      const sh = Math.abs(pos.y - start.y);
      bboxStartRef.current = null;
      setDrawState(null);
      if (sw < 5 || sh < 5) return;
      const newObj: LabelObject = {
        id: generateId(),
        type: 'bbox',
        labelCode: 'PERSON',
        labelName: labelName('PERSON'),
        color: labelColor('PERSON'),
        confidence: 1.0,
        createdBy: 'manual',
        bbox: {
          x: Math.round(sx / scale),
          y: Math.round(sy / scale),
          w: Math.round(sw / scale),
          h: Math.round(sh / scale),
        },
        attributes: {},
      };
      addObject(newObj);
    },
    [readOnly, tool, scale, addObject],
  );

  const handleStageClick = useCallback(
    (e: Konva.KonvaEventObject<MouseEvent>) => {
      // In readOnly mode: allow select (deselect bg) + fire onCanvasClick for issue marking
      if (readOnly) {
        if (e.target === e.target.getStage() || e.target.name() === 'bg-image') {
          selectObject(null);
          if (onCanvasClick) {
            const stage = e.target.getStage();
            const pos = stage?.getPointerPosition();
            if (pos) onCanvasClick(Math.round(pos.x / scale), Math.round(pos.y / scale));
          }
        }
        return;
      }

      // Select mode: deselect on background click
      if (tool === 'select') {
        if (e.target === e.target.getStage() || e.target.name() === 'bg-image') {
          selectObject(null);
        }
        return;
      }

      // Polygon tool
      if (tool === 'polygon') {
        const stage = e.target.getStage();
        if (!stage) return;
        const pos = stage.getPointerPosition();
        if (!pos) return;
        polygonPointsRef.current = [...polygonPointsRef.current, pos.x, pos.y];
        setDrawState({
          type: 'polygon',
          points: [...polygonPointsRef.current],
          mouseX: pos.x,
          mouseY: pos.y,
        });
      }
    },
    [readOnly, tool, scale, selectObject, onCanvasClick],
  );

  const handleStageDblClick = useCallback(
    (_e: Konva.KonvaEventObject<MouseEvent>) => {
      if (readOnly) return;
      if (tool !== 'polygon') return;
      const pts = polygonPointsRef.current;
      // Need at least 3 unique points (6 values) — dblclick adds 2 extra points, deduplicate last
      const uniquePts = pts.slice(0, pts.length - 2); // remove the last dblclick point duplicate
      if (uniquePts.length < 6) {
        polygonPointsRef.current = [];
        setDrawState(null);
        return;
      }
      const newObj: LabelObject = {
        id: generateId(),
        type: 'polygon',
        labelCode: 'PERSON',
        labelName: labelName('PERSON'),
        color: labelColor('PERSON'),
        confidence: 1.0,
        createdBy: 'manual',
        points: uniquePts.map((p, i) => (i % 2 === 0 ? Math.round(p / scale) : Math.round(p / scale))),
        attributes: {},
      };
      polygonPointsRef.current = [];
      setDrawState(null);
      addObject(newObj);
    },
    [readOnly, tool, scale, addObject],
  );

  return (
    <div ref={containerRef} className="w-full h-full flex items-center justify-center bg-gray-900 overflow-hidden">
      {imgStatus === 'loading' && (
        <div className="absolute inset-0 flex items-center justify-center z-10 pointer-events-none">
          <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
        </div>
      )}
      <Stage
        width={stageSize.width}
        height={stageSize.height}
        onMouseDown={handleStageMouseDown}
        onMouseMove={handleStageMouseMove}
        onMouseUp={handleStageMouseUp}
        onClick={handleStageClick}
        onDblClick={handleStageDblClick}
        style={{ cursor: readOnly ? 'default' : tool === 'select' ? 'default' : 'crosshair' }}
      >
        {/* Layer 1: Background image */}
        <Layer>
          {img && (
            <KonvaImage
              image={img}
              x={0}
              y={0}
              width={stageSize.width}
              height={stageSize.height}
              name="bg-image"
              listening={tool === 'select'}
            />
          )}
        </Layer>

        {/* Layer 2: Existing shapes */}
        <Layer>
          {objects.map((obj) => {
            if (obj.type === 'bbox' && obj.bbox) {
              return (
                <BBoxShape
                  key={obj.id}
                  object={obj as LabelObject & { type: 'bbox'; bbox: NonNullable<LabelObject['bbox']> }}
                  isSelected={selectedId === obj.id}
                  onSelect={() => selectObject(obj.id)}
                  onChange={(patch) => updateObject(obj.id, patch)}
                  scale={scale}
                  isSelectMode={!readOnly && tool === 'select'}
                  readOnly={readOnly}
                />
              );
            }
            if (obj.type === 'polygon' && obj.points) {
              return (
                <PolygonShape
                  key={obj.id}
                  object={obj as LabelObject & { type: 'polygon'; points: number[] }}
                  isSelected={selectedId === obj.id}
                  onSelect={() => selectObject(obj.id)}
                  onChange={(patch) => updateObject(obj.id, patch)}
                  scale={scale}
                  isSelectMode={!readOnly && tool === 'select'}
                  readOnly={readOnly}
                />
              );
            }
            // mask type: static overlay badge
            return null;
          })}
        </Layer>

        {/* Layer 3: Drawing in progress */}
        <Layer listening={false}>
          <DrawingLayer drawState={drawState} />
        </Layer>

        {/* Extra layers (e.g. issue markers) */}
        {extraLayers && extraLayers(scale)}
      </Stage>

      {/* Mask overlay notice */}
      {objects.some((o) => o.type === 'mask') && (
        <div className="absolute top-2 left-2 bg-black/60 text-white text-xs px-2 py-1 rounded pointer-events-none">
          🔒 마스크 라벨 {objects.filter((o) => o.type === 'mask').length}개 — 편집 불가 (Phase 예정)
        </div>
      )}
    </div>
  );
}
