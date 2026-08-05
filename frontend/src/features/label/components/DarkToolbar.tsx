// SCR-LABEL-001 좌측 세로 도구바 (mock 정합 — 아이콘 only, w-14).
//
// 도구: 선택(Esc) / 바운딩박스(B) / 폴리곤(P) / SAM분할(G) / SAM추적(Shift+T) / 키포인트(K)
//       / [구분선] / 삭제(Del) / 실행취소(Ctrl+Z) / [구분선] / 저장(Ctrl+S)
// ★ 단축키 표기는 하드코딩하지 않고 SHORTCUT_KEYMAP(단일 출처)에서 formatBindingKeys 로 파생 —
//   키맵과 툴팁이 100% 일치(오표기 0)하도록 보장한다.

import {
  Loader2,
  Maximize2,
  MousePointer2,
  Pentagon,
  PersonStanding,
  RotateCcw,
  Route,
  ScanSearch,
  Save,
  Sparkles,
  Square,
  Trash2,
} from 'lucide-react';

import { cn } from '@/lib/cn';
import { useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';

import { formatBindingKeys } from '../hooks/labelingKeymap';
import { PORTAL_HIDDEN_TOOLS, TOOL_DISPLAY_NAME, ToolType } from '../types';

// 각 도구/액션 버튼의 단축키 툴팁은 SHORTCUT_KEYMAP 단일 출처에서 파생한다(하드코딩 오표기 근절).
// 키맵 id ↔ 툴바 항목 매핑. YOLO 오토라벨은 키맵 미등록이라 별도 고정 표기('Y').
const TOOL_KEYMAP_ID: Partial<Record<ToolType, string>> = {
  [ToolType.SELECT]: 'tool.select',
  [ToolType.BBOX]: 'tool.bbox',
  [ToolType.POLYGON]: 'tool.polygon',
  [ToolType.SAM_SEGMENT]: 'tool.samSegment',
  [ToolType.TRACK]: 'tool.track',
  [ToolType.KEYPOINT]: 'tool.keypoint',
};

interface DarkToolbarProps {
  onSave: () => void;
  /**
   * R17 이슈3 / ADR-013 — 포털 모드에서는 SAM2 분할/추적 도구를 미노출.
   * 포털은 데이터마트 영상 간편 라벨링 전용으로 오토라벨링(SAM2/YOLO)을 제공하지 않으며,
   * 내부 /frames/{id}/sam2-* 엔드포인트도 PORTAL 채널 403 이다.
   */
  portalMode?: boolean;
  /**
   * Phase 3 — YOLO 오토라벨 수동 트리거 핸들러. 미지정 시 버튼 미노출.
   * ADR-013 — 포털 모드에서는 항상 숨김(BE /frames/{id}/autolabel 도 PORTAL 채널 403).
   */
  onAutolabel?: () => void;
  /** YOLO 오토라벨 요청 진행 중 — 버튼 로딩/비활성 표시 + 중복 클릭 방지. */
  isAutolabeling?: boolean;
  /**
   * 도구 선택 위임 (2026-08-03) — 도형 도구는 라벨 선택 모달을 거쳐야 하므로 툴바가
   * `setActiveTool` 을 직접 호출하지 않고 호출부(useToolLabelPicker)에 넘긴다.
   * 미지정 시 기존 동작(스토어 직접 전환)을 유지한다.
   */
  onSelectTool?: (tool: ToolType) => void;
}

interface ToolItem {
  kind: 'tool';
  tool: ToolType;
  icon: React.ElementType;
  label: string;
  shortcut: string;
}

interface ActionItem {
  kind: 'action';
  icon: React.ElementType;
  label: string;
  shortcut: string;
  action: () => void;
  /** ADR-013 — 포털 모드에서 숨김 대상 액션(오토라벨 등). */
  portalHidden?: boolean;
  /** 진행 중 표시 — 스피너 + 비활성. */
  busy?: boolean;
}

interface DividerItem {
  kind: 'divider';
}

type Item = ToolItem | ActionItem | DividerItem;

export function DarkToolbar({
  onSave,
  portalMode = false,
  onAutolabel,
  isAutolabeling = false,
  onSelectTool,
}: DarkToolbarProps) {
  const activeTool = useLabelStore((s) => s.activeTool);
  // 편집 차단 단일 판정원 — 장시간 작업 중에는 도구 전환·삭제·되돌리기·저장을 모두 비활성화한다.
  const editBlocked = useIsEditBlocked();
  const setActiveTool = useLabelStore((s) => s.setActiveTool);
  const undo = useLabelStore((s) => s.undo);
  const removeLabel = useLabelStore((s) => s.removeLabel);
  const selectedId = useLabelStore((s) => s.selectedLabelId);
  // R3 — 수동 Fit(뷰 초기화): zoom=1·pan=0 으로 화면 맞춤 복귀.
  const resetView = useLabelStore((s) => s.resetView);

  const handleDelete = () => {
    if (selectedId) removeLabel(selectedId);
  };

  // ADR-013 — 포털은 SAM 분할/추적·키포인트 미제공(PORTAL_HIDDEN_TOOLS)이고 오토라벨(YOLO) 액션도 숨긴다.
  // (단축키 게이팅 useLabelingShortcuts 와 동일 정책 소스.)
  // 도구 단축키는 키맵에서 파생(TOOL_KEYMAP_ID). 액션 단축키도 키맵 id 로 파생.
  const toolShortcut = (tool: ToolType): string => {
    const id = TOOL_KEYMAP_ID[tool];
    return id ? formatBindingKeys(id) : '';
  };
  const allItems: Item[] = [
    // 도구 표시명은 TOOL_DISPLAY_NAME 단일 출처에서 파생 — 라벨 선택 모달 안내와 동일 문구 보장.
    { kind: 'tool', tool: ToolType.SELECT, icon: MousePointer2, label: TOOL_DISPLAY_NAME[ToolType.SELECT], shortcut: toolShortcut(ToolType.SELECT) },
    { kind: 'tool', tool: ToolType.BBOX, icon: Square, label: TOOL_DISPLAY_NAME[ToolType.BBOX], shortcut: toolShortcut(ToolType.BBOX) },
    { kind: 'tool', tool: ToolType.POLYGON, icon: Pentagon, label: TOOL_DISPLAY_NAME[ToolType.POLYGON], shortcut: toolShortcut(ToolType.POLYGON) },
    { kind: 'tool', tool: ToolType.SAM_SEGMENT, icon: Sparkles, label: TOOL_DISPLAY_NAME[ToolType.SAM_SEGMENT], shortcut: toolShortcut(ToolType.SAM_SEGMENT) },
    { kind: 'tool', tool: ToolType.TRACK, icon: Route, label: TOOL_DISPLAY_NAME[ToolType.TRACK], shortcut: toolShortcut(ToolType.TRACK) },
    { kind: 'tool', tool: ToolType.KEYPOINT, icon: PersonStanding, label: TOOL_DISPLAY_NAME[ToolType.KEYPOINT], shortcut: toolShortcut(ToolType.KEYPOINT) },
    // Phase 3 — YOLO 오토라벨 수동 트리거(액션). 핸들러가 주어질 때만 노출, 포털 숨김(ADR-013).
    // YOLO 는 키맵 미등록(파이프라인 트리거)이라 표기는 고정 'Y'.
    ...(onAutolabel
      ? [
          {
            kind: 'action' as const,
            icon: ScanSearch,
            label: 'AI 탐지',
            shortcut: 'Y',
            action: onAutolabel,
            portalHidden: true,
            busy: isAutolabeling,
          },
        ]
      : []),
    { kind: 'divider' },
    { kind: 'action', icon: Trash2, label: '삭제', shortcut: formatBindingKeys('label.delete'), action: handleDelete },
    { kind: 'action', icon: RotateCcw, label: '실행 취소', shortcut: formatBindingKeys('edit.undo'), action: undo },
    // R3 — 화면 맞춤(Fit): 프레임 전환 시 뷰 유지 정책과 짝을 이루는 수동 초기화 컨트롤(키맵 미배정).
    { kind: 'action', icon: Maximize2, label: '화면 맞춤', shortcut: '', action: resetView },
    { kind: 'divider' },
    { kind: 'action', icon: Save, label: '저장', shortcut: formatBindingKeys('edit.save'), action: onSave },
  ];
  const items: Item[] = allItems.filter((item) => {
    if (!portalMode) return true;
    // 포털 숨김 도구는 PORTAL_HIDDEN_TOOLS 단일 소스로만 관리. 액션(오토라벨)은 portalHidden 플래그.
    if (item.kind === 'tool') return !PORTAL_HIDDEN_TOOLS.includes(item.tool);
    if (item.kind === 'action') return !item.portalHidden;
    return true;
  });

  // ⚠ 알려진 미해결 결함 (2026-08-04 스윕에서 발견 · 사용자 확정으로 이번 범위 밖 — 별건)
  //
  // 이 툴바는 `overflow-hidden` 조상(LabelingPage.tsx 의 `flex flex-1 overflow-hidden`) 안의
  // flex 아이템인데 **자신은 스크롤 계약이 없다**. 따라서 버튼이 세로로 넘치면 스크롤이 아니라
  // 조상이 그대로 잘라내며, 잘린 하단 버튼(맨 끝 '저장')은 **영구히 클릭할 수 없다**.
  // 실측(브라우저): 뷰포트 높이 700px 에서 '저장' 버튼 바닥 y=570, 하단 타임라인 시작 y≈580 —
  // 여유가 10px 뿐이라 창을 조금만 더 줄이면(≈690px 이하) 잘리기 시작한다. 700px 미만은 미측정.
  //
  // 이는 같은 날 고친 ObjectAttributePanel 겹침(overflow 계약 누락)과 **동일 계열 결함**이다.
  // 다만 단순히 `overflow-y-auto` 를 추가하면 안 된다 — overflow-y 를 non-visible 로 두면
  // overflow-x 도 auto 로 강제되어, 버튼 우측에 `absolute left-12` 로 그려지는 툴팁(아래 참조)이
  // 함께 클리핑되는 새 회귀가 생긴다. 제대로 고치려면 스크롤 컨테이너를 버튼 목록에만 적용하거나
  // 툴팁을 portal 로 분리하는 선행 작업이 필요하다.
  //
  // 조용한 누락과 구분하기 위해 여기 남긴다. 고칠 때 docs/test-cases/H-frontend-e2e.md 의
  // TC-FE-304(폐기 아님 · 미해결로 등재)도 함께 갱신할 것.
  return (
    <div
      className="flex flex-col items-center gap-1 p-2 bg-gray-800 border-r border-gray-700 w-14 shrink-0"
      role="toolbar"
      aria-label="라벨링 도구"
    >
      {items.map((item, idx) => {
        if (item.kind === 'divider') {
          return <div key={idx} className="w-8 h-px bg-gray-600 my-1" />;
        }
        const busy = item.kind === 'action' && item.busy === true;
        // 진행 중 표시(busy)와 편집 차단(editBlocked)은 다른 축이지만, 버튼 비활성은 동일하게 적용한다.
        const disabled = busy || editBlocked;
        const Icon = busy ? Loader2 : item.icon;
        const isActive = item.kind === 'tool' && activeTool === item.tool;
        const selectTool = onSelectTool ?? setActiveTool;
        const handleClick =
          item.kind === 'action' ? item.action : () => selectTool(item.tool);

        return (
          <div key={idx} className="relative group">
            <button
              type="button"
              onClick={handleClick}
              disabled={disabled}
              aria-label={item.label}
              // 단축키를 title 로도 노출 — 키맵 파생(오표기 0), 마우스 호버/스크린리더 힌트.
              title={item.shortcut ? `${item.label} (${item.shortcut})` : item.label}
              aria-pressed={isActive}
              aria-busy={busy}
              className={cn(
                'w-10 h-10 rounded-lg flex items-center justify-center transition-colors',
                isActive
                  ? 'bg-primary-600 text-white'
                  : 'text-gray-300 hover:bg-gray-700 hover:text-white',
                disabled && 'opacity-60 cursor-not-allowed',
              )}
            >
              <Icon size={18} className={cn(busy && 'animate-spin')} />
            </button>
            {/* Tooltip — group-hover로 우측에 노출 */}
            <div className="absolute left-12 top-1/2 -translate-y-1/2 z-50 pointer-events-none opacity-0 group-hover:opacity-100 transition-opacity">
              <div className="bg-gray-900 text-white text-xs rounded px-2 py-1 whitespace-nowrap border border-gray-700 shadow-lg">
                {item.label}
                <span className="ml-2 text-gray-400">{item.shortcut}</span>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
