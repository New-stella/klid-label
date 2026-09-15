// 포털 라벨링 — 왼쪽 도구 칸(그리기 도구 · 보기 · 단축키 안내).
//
// <h3>흐름은 원본 그대로</h3>
// 원본 도구 막대(`ToolBar`)와 같은 판정을 쓴다 —
//   · 도구 목록은 선택 · 바운딩 박스 · 폴리곤(포털에 없는 도구는 `PORTAL_HIDDEN_TOOLS` 로 뺀다)
//   · 켜진 도구는 스토어 `activeTool`, 누르면 화면의 `onSelectTool`(라벨 선택 창을 거친다)
//   · 회전 중에는 선택을 뺀 그리기 도구가 잠기고, 편집 차단(장시간 작업) 중에는 전부 잠긴다
//   · 단축키 표기는 키맵(`formatBindingKeys`)에서 뽑는다 — 손으로 적지 않는다
//   · 화면 맞춤은 스토어 `resetView`
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 화면(KLID_Portal `AuthoringLabelingView` 의 도구 칸)과 같다 — 도구 판 둘(그리기 도구 · 보기)과
// 맨 아래 「단축키 안내」 버튼.
// ★ 원본의 「단축키 안내」 는 손을 올리면 도움말이 옆에 뜨는 미리보기였고, 전체 도움말 창은 머리 줄의
//   ? 버튼이 열었다. 포털 화면은 이 버튼 하나가 도움말 창을 연다 — 같은 도움말에 닿는 길이 하나로 모였다.

import { Button } from 'krds-react';
import {
  Grid3x3,
  Keyboard,
  Maximize2,
  MousePointer2,
  Pentagon,
  RotateCcw,
  RotateCw,
  Square,
  ZoomIn,
  type LucideIcon,
} from 'lucide-react';

import {
  ToolAction,
  ToolList,
  ToolSwitch,
  type ToolListItem,
} from '@portal/pages/workspace/authoring/ToolList';
import { ToolPanel } from '@portal/pages/workspace/authoring/ToolPanel';
import { formatBindingKeys } from '@/features/label/hooks/labelingKeymap';
import { PORTAL_HIDDEN_TOOLS, TOOL_DISPLAY_NAME, ToolType } from '@/features/label/types';
import { useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';

/** 그리기 도구 — 원본 도구 막대의 그리기 묶음 순서. 키맵 id 로 단축키를 뽑는다. */
const DRAW_TOOLS: { tool: ToolType; icon: LucideIcon; keymapId: string }[] = [
  { tool: ToolType.SELECT, icon: MousePointer2, keymapId: 'tool.select' },
  { tool: ToolType.BBOX, icon: Square, keymapId: 'tool.bbox' },
  { tool: ToolType.POLYGON, icon: Pentagon, keymapId: 'tool.polygon' },
];

export interface PortalLabelingRailProps {
  rotation: number;
  onRotate: (deltaDeg: -90 | 90) => void;
  zoomAreaMode: boolean;
  onToggleZoomArea: () => void;
  showGrid: boolean;
  onToggleGrid: () => void;
  onSelectTool: (tool: ToolType) => void;
  onOpenShortcuts: () => void;
}

export function PortalLabelingRail({
  rotation,
  onRotate,
  zoomAreaMode,
  onToggleZoomArea,
  showGrid,
  onToggleGrid,
  onSelectTool,
  onOpenShortcuts,
}: PortalLabelingRailProps) {
  const activeTool = useLabelStore((s) => s.activeTool);
  const resetView = useLabelStore((s) => s.resetView);
  const editBlocked = useIsEditBlocked();
  const rotated = rotation !== 0;

  const tools: ToolListItem<ToolType>[] = DRAW_TOOLS.filter(
    ({ tool }) => !PORTAL_HIDDEN_TOOLS.includes(tool),
  ).map(({ tool, icon, keymapId }) => {
    const label = TOOL_DISPLAY_NAME[tool];
    const shortcut = formatBindingKeys(keymapId);
    // 회전 중 잠금 대상은 그리기 도구뿐이다 — 선택은 회전을 풀었을 때 돌아갈 기본 도구라 남긴다(원본 규칙).
    const lockedByRotation = rotated && tool !== ToolType.SELECT;
    return {
      value: tool,
      label,
      icon,
      shortcut: shortcut || undefined,
      disabled: lockedByRotation,
      title: lockedByRotation
        ? `${label} (회전 중에는 사용할 수 없습니다)`
        : shortcut
          ? `${label} (${shortcut})`
          : label,
    };
  });

  return (
    <>
      <ToolPanel title="그리기 도구">
        <ToolList
          label="그리기 도구"
          items={tools}
          value={activeTool}
          onChange={onSelectTool}
          disabled={editBlocked}
        />
      </ToolPanel>
      <ToolPanel title="보기" note="회전 중에는 그리기 도구가 잠깁니다.">
        <div className="klid-tool-list">
          <ToolAction
            icon={RotateCcw}
            label="좌 90° 회전"
            title={`왼쪽으로 90도 회전 · 현재 ${rotation}도`}
            disabled={editBlocked}
            onClick={() => onRotate(-90)}
          />
          <ToolAction
            icon={RotateCw}
            label="우 90° 회전"
            title={`오른쪽으로 90도 회전 · 현재 ${rotation}도`}
            disabled={editBlocked}
            onClick={() => onRotate(90)}
          />
          <ToolAction
            icon={Maximize2}
            label="화면 맞춤"
            title="화면 맞춤"
            disabled={editBlocked}
            onClick={resetView}
          />
          <ToolSwitch
            icon={ZoomIn}
            label="영역 확대"
            checked={zoomAreaMode}
            disabled={editBlocked}
            onChange={onToggleZoomArea}
          />
          <ToolSwitch
            icon={Grid3x3}
            label="그리드 표시"
            checked={showGrid}
            disabled={editBlocked}
            onChange={onToggleGrid}
          />
        </div>
      </ToolPanel>
      {/* 도구 칸 맨 아래 — 도구를 다 훑고 난 자리에서 단축키로 넘어가는 길 */}
      <Button size="small" variant="tertiary" className="klid-labeling-help" onClick={onOpenShortcuts}>
        <Keyboard aria-hidden />
        단축키 안내
      </Button>
    </>
  );
}
