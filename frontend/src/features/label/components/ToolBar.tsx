// UI-047 ToolBar — 라벨링 캔버스 좌측 세로 도구바 (아이콘 only, w-14).
//
// 도구: 선택(Esc) / 바운딩박스(B) / 폴리곤(P) / AI분할(G) / AI추적(Shift+T) / 키포인트(K)
//       / AI 탐지 / [구분선] / 화면 맞춤
// ★ 단축키 표기는 하드코딩하지 않고 SHORTCUT_KEYMAP(단일 출처)에서 formatBindingKeys 로 파생 —
//   키맵과 툴팁이 100% 일치(오표기 0)하도록 보장한다.
// ★ 저장·삭제·실행취소·다시실행은 **이 도구바가 아니라 캔버스 상단 옵션바**(CanvasOptionBar)가
//   담당한다(SCREEN-005 §좌측 도구바 / §캔버스 상단 옵션바 확정). 양쪽에 두지 않는다 —
//   진입점이 둘이면 잠금·진행중 판정이 한쪽만 갱신돼 조용히 열린 구멍이 생긴다.

import {
  Keyboard,
  Loader2,
  Maximize2,
  MousePointer2,
  Pentagon,
  PersonStanding,
  Route,
  ScanSearch,
  Sparkles,
  Square,
} from 'lucide-react';
import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';

import { cn } from '@/lib/cn';
import { useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';

import { formatBindingKeys } from '../hooks/labelingKeymap';
import { PORTAL_HIDDEN_TOOLS, TOOL_DISPLAY_NAME, ToolType } from '../types';
import { ShortcutCheatSheetContent } from './ShortcutCheatSheet';

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

/**
 * 버튼 목록의 **스크롤 계약** (2026-08-06 — 구 미해결 결함 TC-FE-306 해소).
 *
 * 이 툴바는 `overflow-hidden` 조상(LabelingPage 의 `flex flex-1 overflow-hidden`) 안의 flex
 * 아이템이라, 자체 스크롤 계약이 없으면 버튼이 세로로 넘칠 때 스크롤이 아니라 조상이 잘라내고
 * 잘린 하단 버튼이 **영구히 클릭 불가**가 된다(실측: 뷰포트 700px 에서 여유 10px).
 *
 * - `flex-1` + `min-h-0` : flex 아이템의 자동 최소 크기를 풀어 남은 높이에 맞춰 줄어들게 한다.
 *   (`min-h-0` 이 없으면 콘텐츠 높이가 하한이라 overflow 가 아예 발동하지 않는다.)
 * - `overflow-y-auto`    : 넘치면 조상이 아니라 이 안에서 스크롤한다.
 * - `overflow-x-hidden`  : overflow-y 를 non-visible 로 두면 overflow-x 도 auto 로 강제되므로
 *   가로 스크롤바가 생기지 않도록 명시한다. 가로로 삐져나오던 유일한 요소인 툴팁은 아래처럼
 *   **portal 로 body 에 분리**해 이 상자 밖에서 그리므로 함께 잘리지 않는다.
 * - 좌우 패딩 없음(`py-2`) : 세로 스크롤바가 자리를 차지하는 환경(Windows 등)에서도 40px 버튼이
 *   56px 폭 안에 남도록 여유를 남긴다.
 *
 * ⚠ ObjectAttributePanel(PANEL_LAYOUT_CLASS)과 같은 계열의 계약이다. 되돌리면 같은 결함이 재발한다.
 * 회귀 가드: ToolBarScrollContract.test.tsx.
 */
export const TOOLBAR_SCROLL_CLASS =
  'flex min-h-0 flex-1 flex-col items-center gap-1 overflow-y-auto overflow-x-hidden py-2';

interface ToolBarProps {
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

export type { ToolBarProps };

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
  /** busy·editBlocked 와 **다른 축**의 추가 비활성(예: 영상 잠금). */
  disabled?: boolean;
  /** 테스트 식별자 — 같은 이름의 버튼이 화면 다른 곳에도 있을 때 정밀 타겟팅용. */
  testId?: string;
}

interface DividerItem {
  kind: 'divider';
}

type Item = ToolItem | ActionItem | DividerItem;

/** 호버/포커스 시 body 로 portal 되는 툴팁의 위치·내용. */
interface TooltipState {
  label: string;
  shortcut: string;
  /** 뷰포트 좌표(position: fixed) — 버튼 오른쪽 가운데. */
  top: number;
  left: number;
}

export function ToolBar({
  portalMode = false,
  onAutolabel,
  isAutolabeling = false,
  onSelectTool,
}: ToolBarProps) {
  const activeTool = useLabelStore((s) => s.activeTool);
  // 편집 차단 단일 판정원 — 장시간 작업 중에는 도구 전환·보기 조작을 비활성화한다.
  const editBlocked = useIsEditBlocked();
  const setActiveTool = useLabelStore((s) => s.setActiveTool);
  // R3 — 수동 Fit(뷰 초기화): zoom=1·pan=0 으로 화면 맞춤 복귀.
  const resetView = useLabelStore((s) => s.resetView);
  // 툴팁은 스크롤 상자 밖(body)에서 그린다 — 상자 안에 두면 overflow 계약에 함께 잘린다.
  const [tooltip, setTooltip] = useState<TooltipState | null>(null);

  // ADR-013 — 포털은 SAM 분할/추적·키포인트 미제공(PORTAL_HIDDEN_TOOLS)이고 오토라벨(YOLO) 액션도 숨긴다.
  // (단축키 게이팅 useLabelingShortcuts 와 동일 정책 소스.)
  // 도구 단축키는 키맵에서 파생(TOOL_KEYMAP_ID). 액션 단축키도 키맵 id 로 파생.
  const toolShortcut = (tool: ToolType): string => {
    const id = TOOL_KEYMAP_ID[tool];
    return id ? formatBindingKeys(id) : '';
  };
  const allItems: Item[] = [
    // 도구 표시명은 TOOL_DISPLAY_NAME 단일 출처에서 파생 — 라벨 선택 모달 안내와 동일 문구 보장.
    {
      kind: 'tool',
      tool: ToolType.SELECT,
      icon: MousePointer2,
      label: TOOL_DISPLAY_NAME[ToolType.SELECT],
      shortcut: toolShortcut(ToolType.SELECT),
    },
    {
      kind: 'tool',
      tool: ToolType.BBOX,
      icon: Square,
      label: TOOL_DISPLAY_NAME[ToolType.BBOX],
      shortcut: toolShortcut(ToolType.BBOX),
    },
    {
      kind: 'tool',
      tool: ToolType.POLYGON,
      icon: Pentagon,
      label: TOOL_DISPLAY_NAME[ToolType.POLYGON],
      shortcut: toolShortcut(ToolType.POLYGON),
    },
    {
      kind: 'tool',
      tool: ToolType.SAM_SEGMENT,
      icon: Sparkles,
      label: TOOL_DISPLAY_NAME[ToolType.SAM_SEGMENT],
      shortcut: toolShortcut(ToolType.SAM_SEGMENT),
    },
    {
      kind: 'tool',
      tool: ToolType.TRACK,
      icon: Route,
      label: TOOL_DISPLAY_NAME[ToolType.TRACK],
      shortcut: toolShortcut(ToolType.TRACK),
    },
    {
      kind: 'tool',
      tool: ToolType.KEYPOINT,
      icon: PersonStanding,
      label: TOOL_DISPLAY_NAME[ToolType.KEYPOINT],
      shortcut: toolShortcut(ToolType.KEYPOINT),
    },
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
    // ★삭제·실행취소·다시실행·저장은 여기에 두지 않는다 — 캔버스 상단 옵션바(CanvasOptionBar) 소관.
    //   되돌려 넣으면 진입점이 둘로 갈려 잠금·진행중 판정이 한쪽만 갱신된다.
    // R3 — 화면 맞춤(Fit): 프레임 전환 시 뷰 유지 정책과 짝을 이루는 수동 초기화 컨트롤(키맵 미배정).
    //   사양상 보기 조작은 좌측 도구바 소관이다(SCREEN-005 §좌측 도구바).
    {
      kind: 'action',
      icon: Maximize2,
      label: '화면 맞춤',
      shortcut: '',
      action: resetView,
      testId: 'label-toolbar-fit',
    },
  ];
  const items: Item[] = allItems.filter((item) => {
    if (!portalMode) return true;
    // 포털 숨김 도구는 PORTAL_HIDDEN_TOOLS 단일 소스로만 관리. 액션(오토라벨)은 portalHidden 플래그.
    if (item.kind === 'tool') return !PORTAL_HIDDEN_TOOLS.includes(item.tool);
    if (item.kind === 'action') return !item.portalHidden;
    return true;
  });

  // ── 단축키 도움말 (SCREEN-005 §좌측 도구바 맨 아래) ─────────────────────────
  // 사양 표면은 **hover** 지만 hover 전용은 키보드 사용자에게 도달 불가라 접근성 회귀다
  // (WCAG 2.1.1). 따라서 focus 로도 동일하게 열고, Esc 로 닫을 수 있게 한다(1.4.13 dismissible).
  // 패널은 툴팁과 같은 이유로 body 에 portal 한다 — 도구바의 overflow 계약에 잘리지 않게.
  const [helpAnchor, setHelpAnchor] = useState<{ top: number; left: number } | null>(null);
  const openHelp = (el: HTMLElement) => {
    const rect = el.getBoundingClientRect();
    // 패널이 세로로 길어 버튼 높이 기준으로 두면 화면 아래로 넘친다 — 하단 정렬(bottom 기준)한다.
    setHelpAnchor({ top: rect.bottom, left: rect.right + 8 });
  };
  const closeHelp = () => setHelpAnchor(null);
  useEffect(() => {
    if (helpAnchor === null) return;
    const onKeyDown = (e: KeyboardEvent) => {
      // preventDefault 하지 않는다 — Esc 의 기존 동작(도구 선택 복귀)을 뺏지 않는다.
      if (e.key === 'Escape') closeHelp();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [helpAnchor]);

  const showTooltip = (el: HTMLElement, item: ToolItem | ActionItem) => {
    const rect = el.getBoundingClientRect();
    setTooltip({
      label: item.label,
      shortcut: item.shortcut,
      top: rect.top + rect.height / 2,
      left: rect.right + 8,
    });
  };

  return (
    <div
      className="flex flex-col bg-white border-r border-gray-200 w-14 shrink-0"
      role="toolbar"
      aria-label="라벨링 도구"
    >
      <div
        className={TOOLBAR_SCROLL_CLASS}
        data-testid="label-toolbar-scroll"
        // 스크롤하면 툴팁 좌표가 낡는다 — 포인터가 그대로여도 위치가 어긋나므로 즉시 닫는다.
        onScroll={() => setTooltip(null)}
      >
        {items.map((item, idx) => {
          if (item.kind === 'divider') {
            return <div key={idx} className="w-8 h-px bg-gray-200 my-1" />;
          }
          const busy = item.kind === 'action' && item.busy === true;
          // 진행 중 표시(busy)·편집 차단(editBlocked)·개별 비활성(잠금)은 서로 다른 축이지만,
          // 버튼 비활성은 동일하게 적용한다(fail-closed — 하나라도 참이면 막는다).
          const disabled =
            busy || editBlocked || (item.kind === 'action' && item.disabled === true);
          const Icon = busy ? Loader2 : item.icon;
          const isActive = item.kind === 'tool' && activeTool === item.tool;
          const selectTool = onSelectTool ?? setActiveTool;
          const handleClick = item.kind === 'action' ? item.action : () => selectTool(item.tool);

          return (
            <div key={idx} className="relative">
              <button
                type="button"
                onClick={handleClick}
                disabled={disabled}
                aria-label={item.label}
                // 단축키를 title 로도 노출 — 키맵 파생(오표기 0), 마우스 호버/스크린리더 힌트.
                title={item.shortcut ? `${item.label} (${item.shortcut})` : item.label}
                aria-pressed={isActive}
                aria-busy={busy}
                data-testid={item.kind === 'action' ? item.testId : undefined}
                onMouseEnter={(e) => showTooltip(e.currentTarget, item)}
                onFocus={(e) => showTooltip(e.currentTarget, item)}
                onMouseLeave={() => setTooltip(null)}
                onBlur={() => setTooltip(null)}
                className={cn(
                  'w-10 h-10 rounded-lg flex items-center justify-center transition-colors',
                  isActive
                    ? 'bg-primary-600 text-white'
                    : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900',
                  disabled && 'opacity-60 cursor-not-allowed',
                )}
              >
                <Icon size={18} className={cn(busy && 'animate-spin')} />
              </button>
            </div>
          );
        })}
      </div>

      {/* 단축키 도움말 — 도구바 **맨 아래** 고정(스크롤 상자 밖이라 목록이 넘쳐도 항상 보인다).
          표 본문은 ShortcutCheatSheetContent 단일 출처를 그대로 담는다(표기 복제 금지). */}
      <div className="shrink-0 border-t border-gray-200 py-2 flex justify-center">
        <button
          type="button"
          aria-label="단축키 도움말"
          aria-expanded={helpAnchor !== null}
          aria-describedby={helpAnchor !== null ? 'toolbar-shortcut-help' : undefined}
          data-testid="label-toolbar-shortcut-help"
          title="단축키 도움말"
          onMouseEnter={(e) => openHelp(e.currentTarget)}
          onFocus={(e) => openHelp(e.currentTarget)}
          onMouseLeave={closeHelp}
          onBlur={closeHelp}
          className="w-10 h-10 rounded-lg flex items-center justify-center text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900"
        >
          <Keyboard size={18} />
        </button>
      </div>
      {helpAnchor !== null &&
        createPortal(
          <div
            id="toolbar-shortcut-help"
            role="tooltip"
            data-testid="label-toolbar-shortcut-panel"
            className="fixed z-[60] -translate-y-full pointer-events-none"
            style={{ top: helpAnchor.top, left: helpAnchor.left }}
          >
            <div className="w-[34rem] max-w-[80vw] rounded-lg border border-gray-200 bg-white px-4 py-3 shadow-lg">
              <p className="mb-2 text-sub font-semibold text-gray-700">단축키 도움말</p>
              <ShortcutCheatSheetContent portalMode={portalMode} />
            </div>
          </div>,
          document.body,
        )}

      {/* Tooltip — 스크롤 상자(overflow) 밖에서 그려야 잘리지 않으므로 body 로 portal 한다.
          위치는 버튼 rect 기준 뷰포트 좌표(position: fixed). */}
      {tooltip !== null &&
        createPortal(
          <div
            role="tooltip"
            data-testid="label-toolbar-tooltip"
            className="fixed z-[60] -translate-y-1/2 pointer-events-none"
            style={{ top: tooltip.top, left: tooltip.left }}
          >
            <div className="bg-white text-gray-900 text-caption rounded px-2 py-1 whitespace-nowrap border border-gray-200 shadow-lg">
              {tooltip.label}
              <span className="ml-2 text-gray-500">{tooltip.shortcut}</span>
            </div>
          </div>,
          document.body,
        )}
    </div>
  );
}
