// UI-047 ToolBar — 라벨링 캔버스 좌측 도구 패널 (w-52 = 시안 .tool-rail 208px).
//
// 도구: 선택(Esc) / 바운딩박스(B) / 폴리곤(P) / AI분할(G) / 키포인트(K)
//       / AI 탐지 / [구분선] / 좌·우 90° 회전 / 화면 맞춤 / 영역 확대 / 그리드 표시
//
// ★표현은 **항목명 + 단축키 배지**다 — 아이콘 전용이 아니다 (2026-08-18 정합).
//   확정 고충실 시안(design-main.html §.tool-rail)이 그리기 도구·보기를 각각 카드로 묶고 각 행에
//   이름과 단축키(.tool-key)를 함께 보여준다. 아이콘만 남기면 ①단축키가 화면에서 사라져 사양이
//   "그룹별 단축키를 보여준다"고 규정한 안내가 맨 아래 도움말 하나에만 남고 ②그리기/보기 그룹
//   구분이 시각적으로 소멸해 회전·확대 같은 보기 조작이 그리기 도구처럼 읽힌다.
//   ⚠ 아이콘 전용(w-14)으로 되돌리지 말 것.
// ★버튼의 접근성 이름은 **aria-label 단일 출처**를 유지한다 — 눈에 보이는 이름을 덧붙였다고
//   aria-label 을 떼면 기존 테스트·보조기술 이름이 배지("B") 까지 함께 읽히는 형태로 흔들린다.
//
// ★'AI 추적'은 이 도구바에 두지 않는다 (2026-08-18 정합 — 사양 SCREEN-005 §좌측 도구바의 버튼은
//   선택·바운딩박스·폴리곤·AI 분할·키포인트·AI 탐지 여섯이며 AI 추적은 그 목록에 없다).
//   AI 추적은 **이미 그려진 객체 하나를 뒤 프레임으로 전파**하는 행위라 '무엇을 추적할지'가 정해진
//   뒤에야 성립한다 — 그래서 실행 진입점은 우측 객체 패널의 선택 객체 속성이고(§라벨링 캔버스 —
//   "우측 패널 '객체' 탭에서 대상 객체를 펼쳤을 때 노출되는 버튼으로 실행한다"), 도구바에 모드
//   버튼을 두면 "모드를 켜야 실행 버튼이 나타나는" 2단 동선이 된다.
//   단축키(Shift+T)와 AI 탐지 다이얼로그의 '트랙으로 실행'은 사양이 유지하므로 그대로 둔다 —
//   그 둘은 추적 형태·라벨을 기억한 상태를 켤 뿐 실행 진입점이 아니다.
//   ⚠ 도구바에 되돌려 넣지 말 것(회귀 가드: ToolBar.test.tsx).
// ★포털 채널(SCREEN-029)은 카드를 **세 묶음**으로 나눈다 (2026-09-15 확정) — 「그리기」(선택·바운딩
//   박스·폴리곤) / 「AI 보조」(AI 탐지·AI 분할·AI 자동 추적) / 「보기」. AI 기능이 그리기 도구 사이에
//   섞여 무엇이 AI 인지 구분되지 않던 것을 푼다. 내부 채널(SCREEN-005)은 종전 두 묶음 그대로다 —
//   관제향 흐름 정리는 별건이라 여기서 함께 바꾸지 않는다.
//   포털의 「AI 자동 추적」 버튼은 **실행하지 않는다** — 우측 객체 탭의 자동 추적 패널로 포커스를
//   옮길 뿐이다(실행·검토의 자리는 그 패널 하나). 위 「AI 추적」(선택 객체 하나 전파)과는 다른 기능이며,
//   선택 객체 AI 추적은 포털에 계속 없다(PORTAL_HIDDEN_TOOLS 의 TRACK).
// ★보기 조작(회전·화면 맞춤·영역 확대)과 그리드 표시 토글은 SCREEN-005 §좌측 도구바 소관이다.
//   회전·영역확대·그리드 상태는 이 도구바가 갖지 않고 **화면(호출부)** 이 갖는다 — 같은 상태를 캔버스
//   (CanvasShell)도 써야 하므로 공통 상위가 단일 보유자여야 한다.
// ★ 단축키 표기는 하드코딩하지 않고 SHORTCUT_KEYMAP(단일 출처)에서 formatBindingKeys 로 파생 —
//   키맵과 툴팁이 100% 일치(오표기 0)하도록 보장한다.
// ★ 저장·삭제·실행취소·다시실행은 **이 도구바가 아니라 캔버스 상단 옵션바**(CanvasOptionBar)가
//   담당한다(SCREEN-005 §좌측 도구바 / §캔버스 상단 옵션바 확정). 양쪽에 두지 않는다 —
//   진입점이 둘이면 잠금·진행중 판정이 한쪽만 갱신돼 조용히 열린 구멍이 생긴다.
//
// @design SCREEN-005, SCREEN-029

import {
  Grid3x3,
  Keyboard,
  Loader2,
  Maximize2,
  MousePointer2,
  Pentagon,
  PersonStanding,
  RotateCcw,
  RotateCw,
  Route,
  ScanSearch,
  Sparkles,
  Square,
  ZoomIn,
} from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import { cn } from '@/lib/cn';
import { getPortalOverlayRoot } from '@/lib/portalOverlayRoot';

// ★포털 채널 전용 — 부모 포털 시안이 쓰는 킷 부품. 관제 렌더 경로는 이것들을 거치지 않는다.
//   (포털 채널 산출물에만 실리도록 하는 것은 라우터의 `PortalLayout` 지연 로드가 맡는다.)
import { Button } from 'krds-react';

import { ToolAction, ToolList, ToolPanel, ToolSwitch, type ToolListItem } from '@/components/portal/authoring';
import { useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';

import { formatBindingKeys } from '../hooks/labelingKeymap';
import { PORTAL_HIDDEN_TOOLS, TOOL_DISPLAY_NAME, ToolType } from '../types';
import { ShortcutCheatSheetContent } from './ShortcutCheatSheet';

/** 도움말 패널이 뷰포트 가장자리에서 유지하는 최소 여백(px). */
const HELP_PANEL_GUTTER = 8;

// 각 도구/액션 버튼의 단축키 툴팁은 SHORTCUT_KEYMAP 단일 출처에서 파생한다(하드코딩 오표기 근절).
// 키맵 id ↔ 툴바 항목 매핑. **키맵에 없는 항목은 단축키 표기를 갖지 않는다**(아래 'AI 탐지' 참조).
const TOOL_KEYMAP_ID: Partial<Record<ToolType, string>> = {
  [ToolType.SELECT]: 'tool.select',
  [ToolType.BBOX]: 'tool.bbox',
  [ToolType.POLYGON]: 'tool.polygon',
  [ToolType.SAM_SEGMENT]: 'tool.samSegment',
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
 *
 * ⚠ ObjectAttributePanel(PANEL_LAYOUT_CLASS)과 같은 계열의 계약이다. 되돌리면 같은 결함이 재발한다.
 * 회귀 가드: ToolBarScrollContract.test.tsx.
 */
export const TOOLBAR_SCROLL_CLASS =
  'flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto overflow-x-hidden p-2';

/** 시안 `.rail-card` — 그룹(그리기 도구 / 보기)을 감싸는 흰 카드. */
const RAIL_CARD_CLASS = 'shrink-0 rounded-lg border border-gray-200 bg-white p-2 shadow-sm';
/** 시안 `.rail-card-title` — 그룹 제목. */
const RAIL_CARD_TITLE_CLASS =
  'mb-2 px-1 text-label font-semibold uppercase tracking-wide text-gray-600';

interface ToolBarProps {
  /**
   * 포털 채널 여부 — 카드 구성을 세 묶음(그리기 / AI 보조 / 보기)으로 바꾸고, 포털 미제공 도구
   * (PORTAL_HIDDEN_TOOLS — 선택 객체 AI 추적·스켈레톤)를 숨긴다.
   * ⚠ [폐기] 구 서술 — *"포털은 오토라벨링(SAM2/YOLO)을 제공하지 않아 AI 분할·AI 탐지를 미노출"*.
   *   2026-09-15 에 포털도 AI 탐지·AI 분할·AI 자동 추적을 쓰게 됐다(포털 전용 창구).
   */
  portalMode?: boolean;
  /**
   * AI 탐지 팝업 열기 핸들러. 미지정 시 버튼 미노출. 두 채널 모두 노출한다.
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
  /**
   * 현재 화면 표시용 회전각(0/90/180/270°). 호출부가 그 4단계만 넘긴다(값 보유자 = 화면).
   * 0 이 아니면 **그리기 도구를 잠근다**(SCREEN-005 §좌측 도구바 — 회전 중에는 그리기 도구를 잠근다).
   */
  rotation?: number;
  /**
   * 회전 요청 — `-90`(좌) / `+90`(우). 미지정 시 회전 버튼을 노출하지 않는다(기존 호출부 무회귀).
   */
  onRotate?: (deltaDeg: -90 | 90) => void;
  /**
   * 영역 확대 모드 활성 여부 — 토글 버튼의 눌림 상태로 노출한다.
   * 회전과 달리 **그리기 도구를 잠그지 않는다**(캔버스가 그 구간의 편집 입력을 봉인하고,
   * 도구를 고르면 화면이 이 모드를 해제한다).
   */
  zoomAreaMode?: boolean;
  /** 영역 확대 모드 토글 요청. 미지정 시 버튼을 노출하지 않는다(기존 호출부 무회귀). */
  onToggleZoomArea?: () => void;
  /** 그리드(격자) 표시 여부 — 토글 버튼의 눌림 상태로 노출한다. */
  showGrid?: boolean;
  /** 그리드 표시 토글 요청. 미지정 시 토글 버튼을 노출하지 않는다(기존 호출부 무회귀). */
  onToggleGrid?: () => void;
  /**
   * (포털 채널 전용) 「AI 자동 추적」 — 우측 객체 탭의 자동 추적 패널로 이동·포커스한다(실행 아님).
   * 미지정이거나 내부 채널이면 버튼을 두지 않는다(내부 채널의 진입점은 그 패널 자체다).
   */
  onFocusAutoTrack?: () => void;
  /**
   * 「AI 자동 추적」을 쓸 수 없는 사유(예: 뒤따르는 프레임 없음). 값이 있으면 버튼을 비활성으로
   * 두고 그 사유를 툴팁과 묶음 아래 보조문으로 보인다 — 눌러 봐야 알 수 있는 제약이 아니다.
   */
  autoTrackUnavailableReason?: string;
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
  /**
   * 화면에 **보이는** 짧은 표기. 미지정 시 `label` 을 그대로 쓴다.
   *
   * 접근성 이름(`label`)은 아이콘만 보고도 뜻이 통하도록 풀어 쓴 문장이라(예: '왼쪽으로 90도 회전')
   * 208px 레일에서 잘린다. 시안은 '좌 90° 회전' 처럼 줄여 적으므로 **보이는 글자만** 줄이고
   * 접근성 이름은 건드리지 않는다(둘을 합치면 낭독이 빈약해지거나 화면이 잘린다).
   */
  shortLabel?: string;
  shortcut: string;
  action: () => void;
  /** 진행 중 표시 — 스피너 + 비활성. */
  busy?: boolean;
  /** busy·editBlocked 와 **다른 축**의 추가 비활성(예: 영상 잠금). */
  disabled?: boolean;
  /**
   * 비활성 사유 — 지정하면 버튼을 비활성으로 두고 툴팁에 사유를 붙이며, `describedById` 가 있으면
   * 화면의 보조문과 `aria-describedby` 로 잇는다(비활성 버튼의 이유를 보조기술에도 전달).
   */
  unavailableReason?: string;
  describedById?: string;
  /**
   * 접근성 이름 — 미지정 시 `label`. 화면에 보이는 이름(`label`)을 **포함**해야 한다(WCAG 2.5.3).
   * 같은 화면에 같은 이름의 다른 버튼이 있을 때만 쓴다(예: 도구바 「AI 자동 추적」은 패널로 이동,
   * 패널의 「AI 자동 추적」은 실행 — 이름이 같으면 보조기술 사용자가 둘을 구별할 수 없다).
   */
  accessibleName?: string;
  /**
   * 토글형 액션의 눌림 상태 — 지정하면 `aria-pressed` 로 노출한다.
   * 미지정(일회성 액션)이면 속성 자체를 붙이지 않는다 — 누름 상태가 없는 버튼에 `aria-pressed="false"`
   * 를 달면 보조기술이 토글 버튼으로 잘못 안내한다.
   */
  pressed?: boolean;
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
  rotation = 0,
  onRotate,
  zoomAreaMode = false,
  onToggleZoomArea,
  showGrid = false,
  onToggleGrid,
  onFocusAutoTrack,
  autoTrackUnavailableReason,
}: ToolBarProps) {
  // 회전 중에는 그리기 도구를 잠근다(사양). 캔버스도 같은 구간에 편집 입력을 봉인하므로,
  // 여기서 잠그지 않으면 눌러도 아무 일이 없는 "죽은 버튼"이 된다.
  const rotated = rotation !== 0;
  const activeTool = useLabelStore((s) => s.activeTool);
  // 편집 차단 단일 판정원 — 장시간 작업 중에는 도구 전환·보기 조작을 비활성화한다.
  const editBlocked = useIsEditBlocked();
  const setActiveTool = useLabelStore((s) => s.setActiveTool);
  // R3 — 수동 Fit(뷰 초기화): zoom=1·pan=0 으로 화면 맞춤 복귀.
  const resetView = useLabelStore((s) => s.resetView);
  // 툴팁은 스크롤 상자 밖(body)에서 그린다 — 상자 안에 두면 overflow 계약에 함께 잘린다.
  const [tooltip, setTooltip] = useState<TooltipState | null>(null);

  // 포털 미제공 도구(PORTAL_HIDDEN_TOOLS — 선택 객체 AI 추적·스켈레톤)는 숨긴다
  // (단축키 게이팅 useLabelingShortcuts 와 동일 정책 소스).
  // 도구 단축키는 키맵에서 파생(TOOL_KEYMAP_ID). 액션 단축키도 키맵 id 로 파생.
  const toolShortcut = (tool: ToolType): string => {
    const id = TOOL_KEYMAP_ID[tool];
    return id ? formatBindingKeys(id) : '';
  };
  // ★그룹 — 내부 채널은 둘(시안 .rail-card ×2): ①그리기 도구 + AI 탐지 ②보기 조작 + 그리드.
  //   포털 채널은 셋: ①그리기 ②AI 보조 ③보기 (SCREEN-029 §좌측 도구바).
  //   한 배열에 섞어 두면 그룹 제목을 붙일 수 없어 화면에서 여러 축이 한 덩어리로 읽힌다.
  // 도구 표시명은 TOOL_DISPLAY_NAME 단일 출처에서 파생 — 라벨 선택 모달 안내와 동일 문구 보장.
  const toolItem = (tool: ToolType, icon: React.ElementType): ToolItem => ({
    kind: 'tool',
    tool,
    icon,
    label: TOOL_DISPLAY_NAME[tool],
    shortcut: toolShortcut(tool),
  });
  const manualDrawItems: Item[] = [
    toolItem(ToolType.SELECT, MousePointer2),
    toolItem(ToolType.BBOX, Square),
    toolItem(ToolType.POLYGON, Pentagon),
  ];
  const autolabelItems: Item[] = onAutolabel
    ? [
        {
          kind: 'action',
          icon: ScanSearch,
          label: 'AI 탐지',
          shortcut: '',
          action: onAutolabel,
          busy: isAutolabeling,
        },
      ]
    : [];
  // 포털 「AI 보조」 묶음 — AI 탐지(팝업) · AI 분할(도구 전환) · AI 자동 추적(패널로 이동).
  const autoTrackReasonId = 'label-toolbar-auto-track-reason';
  const portalAiGroup: Item[] = [
    ...autolabelItems,
    toolItem(ToolType.SAM_SEGMENT, Sparkles),
    ...(onFocusAutoTrack
      ? [
          {
            kind: 'action' as const,
            icon: Route,
            label: 'AI 자동 추적',
            accessibleName: 'AI 자동 추적 패널로 이동',
            shortcut: '',
            action: onFocusAutoTrack,
            unavailableReason: autoTrackUnavailableReason,
            describedById: autoTrackUnavailableReason ? autoTrackReasonId : undefined,
            testId: 'label-toolbar-auto-track',
          },
        ]
      : []),
  ];
  const drawGroup: Item[] = portalMode
    ? manualDrawItems
    : [
    ...manualDrawItems,
    toolItem(ToolType.SAM_SEGMENT, Sparkles),
    toolItem(ToolType.KEYPOINT, PersonStanding),
    // Phase 3 — YOLO 오토라벨 수동 트리거(액션). 핸들러가 주어질 때만 노출.
    // ★단축키 표기를 갖지 않는다 (2026-08-18 정합 — 구 고정 표기 `'Y'` 폐기).
    //   SHORTCUT_KEYMAP 에 `y` 바인딩이 **없어** 사용자가 Y 를 눌러도 아무 일이 일어나지 않는데,
    //   그 고정값이 버튼 `title` 에 `(Y)` 로 실려 **존재하지 않는 단축키를 광고**하고 있었다.
    //   확정 시안의 `.tool-btn-action` 에도 단축키 표기가 없다.
    //   ⚠ 반대 방향(키맵에 `y` 를 등록)으로 해소하지 말 것 — AI 탐지는 도구 전환이 아니라
    //     파이프라인 트리거라 단축키 대상이 아니고 시안에도 없다. 표기만 걷어내는 것이 맞다.
    //   ⚠ 버튼의 접근성 이름('AI 탐지')·동작은 그대로다 — 바뀐 것은 툴팁의 단축키 노출뿐이다.
    //   회귀 가드: ToolBarShortcuts.test.tsx.
    ...autolabelItems,
  ];

  // ★삭제·실행취소·다시실행·저장은 여기에 두지 않는다 — 캔버스 상단 옵션바(CanvasOptionBar) 소관.
  //   되돌려 넣으면 진입점이 둘로 갈려 잠금·진행중 판정이 한쪽만 갱신된다.
  const viewGroup: Item[] = [
    // 보기 조작(좌/우 90° 회전 → 화면 맞춤) + 그리드 표시 — SCREEN-005 §좌측 도구바 순서.
    // 회전은 **표시 전용**이라 라벨 좌표를 바꾸지 않는다(저장에 영향 없음).
    ...(onRotate
      ? [
          {
            kind: 'action' as const,
            icon: RotateCcw,
            label: '왼쪽으로 90도 회전',
            shortLabel: '좌 90° 회전',
            // 현재 각도를 툴팁에 함께 노출 — 일회성 액션이라 눌림 상태(aria-pressed)로는 표현하지 않는다.
            shortcut: `현재 ${rotation}도`,
            action: () => onRotate(-90),
            testId: 'label-toolbar-rotate-left',
          },
          {
            kind: 'action' as const,
            icon: RotateCw,
            label: '오른쪽으로 90도 회전',
            shortLabel: '우 90° 회전',
            shortcut: `현재 ${rotation}도`,
            action: () => onRotate(90),
            testId: 'label-toolbar-rotate-right',
          },
        ]
      : []),
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
    // 영역 확대 — 드래그한 사각형이 화면을 채우도록 배율·위치를 옮기는 **보기 조작**이다.
    // 라벨을 만들지 않으며(캔버스가 그 구간의 드로잉 경로를 봉인한다), 회전 중에도 쓸 수 있다.
    // 되돌리기는 바로 위 '화면 맞춤'.
    ...(onToggleZoomArea
      ? [
          {
            kind: 'action' as const,
            icon: ZoomIn,
            label: '영역 확대',
            shortcut: '',
            action: onToggleZoomArea,
            // 모드 토글이므로 눌림 상태를 노출한다(일회성 액션인 회전과 다르다).
            pressed: zoomAreaMode,
            testId: 'label-toolbar-zoom-area',
          },
        ]
      : []),
    ...(onToggleGrid
      ? [
          {
            kind: 'action' as const,
            icon: Grid3x3,
            label: '그리드 표시',
            shortcut: '',
            action: onToggleGrid,
            // 토글이므로 눌림 상태를 노출한다(회전 버튼은 일회성 액션이라 노출하지 않는다).
            pressed: showGrid,
            testId: 'label-toolbar-grid',
          },
        ]
      : []),
  ];
  const visible = (group: Item[]): Item[] =>
    group.filter((item) => {
      if (!portalMode) return true;
      // 포털 숨김 도구는 PORTAL_HIDDEN_TOOLS 단일 소스로만 관리한다.
      if (item.kind === 'tool') return !PORTAL_HIDDEN_TOOLS.includes(item.tool);
      return true;
    });
  const drawItems = visible(drawGroup);
  const aiItems = portalMode ? visible(portalAiGroup) : [];
  const viewItems = visible(viewGroup);

  // ── 단축키 도움말 (SCREEN-005 §좌측 도구바 맨 아래) ─────────────────────────
  // 사양 표면은 **hover** 지만 hover 전용은 키보드 사용자에게 도달 불가라 접근성 회귀다
  // (WCAG 2.1.1). 따라서 focus 로도 동일하게 열고, Esc 로 닫을 수 있게 한다(1.4.13 dismissible).
  // 패널은 툴팁과 같은 이유로 body 에 portal 한다 — 도구바의 overflow 계약에 잘리지 않게.
  //
  // ★위치·높이 계약 (2026-08-08 — 실측 결함 해소): 패널은 버튼 하단을 기준으로 **위로만** 자라서
  //  내용 높이(약 1120px)가 뷰포트보다 크면 상단이 화면 밖으로 잘렸다(1280x800 실측 top=-444.9 —
  //  패널 제목과 「도구」 섹션 전체가 통째로 사라졌다). 게다가 자신도 조상도 스크롤 컨테이너가
  //  아니라 잘린 내용에 도달할 방법이 없었다. 따라서 ①bottom 기준으로 고정하고 ②남은 높이를
  //  뷰포트 기준 max-height 로 상한한 뒤 ③내부 스크롤을 허용한다. 상한을 고정 px 로 박으면
  //  다른 해상도에서 같은 결함이 재발하므로 100vh 기준으로 계산한다.
  const [helpAnchor, setHelpAnchor] = useState<{ bottom: number; left: number } | null>(null);
  const helpPanelRef = useRef<HTMLDivElement>(null);
  const openHelp = (el: HTMLElement) => {
    const rect = el.getBoundingClientRect();
    setHelpAnchor({
      // 뷰포트 하단에서 버튼 하단까지의 거리 = 패널 바닥 위치.
      bottom: Math.max(HELP_PANEL_GUTTER, window.innerHeight - rect.bottom),
      // 버튼 우변에 **붙여** 둔다(시각적 간격은 패널의 투명 좌패딩이 만든다) — 사이에 틈이 있으면
      // 포인터가 패널로 이동하는 도중 버튼의 mouseleave 가 먼저 닫아버려 스크롤할 수 없다.
      left: rect.right,
    });
  };
  const closeHelp = () => setHelpAnchor(null);
  /** 포인터가 패널 안으로 들어가는 이동이면 닫지 않는다(WCAG 1.4.13 hoverable — 내부 스크롤 수단). */
  const closeHelpUnlessEnteringPanel = (related: EventTarget | null) => {
    if (related instanceof Node && helpPanelRef.current?.contains(related) === true) return;
    closeHelp();
  };
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

  /**
   * 버튼 상태 도출 — 그리기 행과 보기 행이 **같은 판정을 공유**한다.
   * 두 렌더러가 각자 판정하면 회전 잠금·편집 차단이 한쪽만 갱신되는 구멍이 생긴다.
   */
  const itemState = (item: ToolItem | ActionItem) => {
    const busy = item.kind === 'action' && item.busy === true;
    // 회전 중 잠금 대상 = **그리기 도구**(사양). 선택 도구는 잠그지 않는다 — 회전을 풀었을 때
    // 되돌아갈 안전한 기본 도구라 남겨 둔다.
    const lockedByRotation = rotated && item.kind === 'tool' && item.tool !== ToolType.SELECT;
    // 진행 중 표시(busy)·편집 차단(editBlocked)·개별 비활성(잠금)·회전 잠금은 서로 다른 축이지만,
    // 버튼 비활성은 동일하게 적용한다(fail-closed — 하나라도 참이면 막는다).
    const unavailableReason = item.kind === 'action' ? item.unavailableReason : undefined;
    const disabled =
      busy ||
      editBlocked ||
      lockedByRotation ||
      (item.kind === 'action' && item.disabled === true) ||
      unavailableReason !== undefined;
    const isActive = item.kind === 'tool' && activeTool === item.tool;
    const selectTool = onSelectTool ?? setActiveTool;
    return {
      busy,
      disabled,
      isActive,
      Icon: busy ? Loader2 : item.icon,
      // 도구는 활성 여부가 곧 눌림 상태, 액션은 토글일 때만 눌림 상태를 갖는다(그 외 undefined
      // → 속성 미부착). 일회성 액션에 aria-pressed="false" 를 달면 토글로 오안내된다.
      pressed: item.kind === 'tool' ? isActive : item.pressed,
      title: lockedByRotation
        ? `${item.label} (회전 중에는 사용할 수 없습니다)`
        : unavailableReason
          ? `${item.label} (${unavailableReason})`
          : item.shortcut
          ? `${item.label} (${item.shortcut})`
          : item.label,
      onClick: item.kind === 'action' ? item.action : () => selectTool(item.tool),
      testId: item.kind === 'action' ? item.testId : undefined,
      describedBy: item.kind === 'action' ? item.describedById : undefined,
      accessibleName: (item.kind === 'action' ? item.accessibleName : undefined) ?? item.label,
    };
  };

  /** 시안 `.tool-btn` — 아이콘 + 항목명 + 단축키 배지를 한 줄에 담는 full-width 버튼. */
  const renderToolRow = (item: ToolItem | ActionItem, key: number) => {
    const s = itemState(item);
    return (
      <button
        key={key}
        type="button"
        onClick={s.onClick}
        disabled={s.disabled}
        // ★접근성 이름은 aria-label 이 단일 출처다 — 눈에 보이는 이름·단축키 배지가 함께 읽혀
        //   이름이 흔들리지 않게 한다.
        aria-label={s.accessibleName}
        // 단축키를 title 로도 노출 — 키맵 파생(오표기 0), 마우스 호버/스크린리더 힌트.
        title={s.title}
        aria-pressed={s.pressed}
        aria-busy={s.busy}
        aria-describedby={s.describedBy}
        data-testid={s.testId}
        className={cn(
          'flex min-h-[40px] w-full items-center gap-2 rounded-md border px-2 text-left text-body-md font-medium transition-colors',
          s.isActive
            ? 'border-primary-200 bg-primary-50 text-primary-700'
            : 'border-transparent text-gray-900 hover:bg-gray-50',
          item.kind === 'action' && !s.isActive && 'bg-gray-50',
          s.disabled && 'cursor-not-allowed opacity-60',
        )}
      >
        <s.Icon
          size={18}
          aria-hidden="true"
          className={cn('shrink-0', s.busy && 'animate-spin', s.isActive ? 'text-primary-600' : 'text-gray-600')}
        />
        <span className="truncate">{item.label}</span>
        {/* 시안 `.tool-key` — **도구(kind:'tool')에만** 붙인다. 액션('AI 탐지')은 키맵에 등록된
            바인딩이 없어 `shortcut` 이 비어 있고(위 drawGroup), 시안의 `.tool-btn-action` 에도
            `.tool-key` 가 없다. ⚠ 액션에 고정 문자열을 되살려 배지·툴팁으로 내보내면 화면이
            존재하지 않는 단축키를 광고하게 된다(실제 결함이었다 — `'Y'` 표기). */}
        {item.kind === 'tool' && item.shortcut && (
          <span
            aria-hidden="true"
            className={cn(
              'ml-auto shrink-0 rounded border px-1 font-mono text-caption',
              s.isActive ? 'border-primary-200 text-primary-700' : 'border-gray-200 text-gray-600',
            )}
          >
            {item.shortcut}
          </span>
        )}
      </button>
    );
  };

  /** 시안 `.view-row` — 좌측에 항목명, 우측에 아이콘 버튼(또는 토글). */
  const renderViewRow = (item: ToolItem | ActionItem, key: number) => {
    const s = itemState(item);
    // 토글(그리드·영역 확대)은 눌림 상태를 색으로도 알린다 — 아이콘만으로는 켜짐/꺼짐이 안 읽힌다.
    const toggledOn = s.pressed === true;
    return (
      <div key={key} className="flex min-h-[36px] items-center justify-between gap-2 px-2">
        <span className="truncate text-body-md text-gray-900">
          {(item.kind === 'action' ? item.shortLabel : undefined) ?? item.label}
        </span>
        <button
          type="button"
          onClick={s.onClick}
          disabled={s.disabled}
          aria-label={item.label}
          title={s.title}
          aria-pressed={s.pressed}
          aria-busy={s.busy}
          data-testid={s.testId}
          // 아이콘 전용 버튼이라 툴팁을 유지한다(그리기 행과 달리 화면에 단축키가 없다).
          onMouseEnter={(e) => showTooltip(e.currentTarget, item)}
          onFocus={(e) => showTooltip(e.currentTarget, item)}
          onMouseLeave={() => setTooltip(null)}
          onBlur={() => setTooltip(null)}
          className={cn(
            'flex h-8 w-8 shrink-0 items-center justify-center rounded-md border transition-colors',
            toggledOn
              ? 'border-primary-200 bg-primary-50 text-primary-700'
              : 'border-gray-200 text-gray-600 hover:bg-gray-50 hover:text-gray-900',
            s.disabled && 'cursor-not-allowed opacity-60',
          )}
        >
          <s.Icon size={16} aria-hidden="true" className={cn(s.busy && 'animate-spin')} />
        </button>
      </div>
    );
  };

  /**
   * 단축키 도움말 미리 보기 패널 — **두 채널이 한 벌을 나눠 쓴다.**
   * 표 본문은 `ShortcutCheatSheetContent` 단일 출처를 그대로 담는다(표기 복제 금지).
   */
  const renderHelpPanel = () => {
    if (helpAnchor === null) return null;
    return createPortal(
          <div
            id="toolbar-shortcut-help"
            ref={helpPanelRef}
            role="tooltip"
            data-testid="label-toolbar-shortcut-panel"
            // pl-2 = 버튼과의 시각적 간격(투명 영역). 요소 자체는 버튼에 붙어 있어야
            // 포인터가 끊김 없이 패널로 넘어와 스크롤할 수 있다.
            className="fixed z-[60] pl-2"
            style={{ bottom: helpAnchor.bottom, left: helpAnchor.left }}
            onMouseLeave={closeHelp}
          >
            <div
              data-testid="label-toolbar-shortcut-panel-box"
              // 폭 44rem — 3열 표가 좁으면 셀마다 줄바꿈이 잦아 세로로 되레 길어진다(34rem 실측 1014px,
              // 같은 표가 모달 폭에서는 552px). 좌측 도구바(56px) 옆에 두고도 남는 폭이다.
              className="w-[704px] max-w-[calc(100vw-80px)] overflow-y-auto overscroll-contain rounded-lg border border-gray-200 bg-white px-4 py-3 shadow-lg"
              // 상한은 뷰포트 기준 — 패널 바닥(bottom)에서 화면 위쪽 여백(gutter)까지가 쓸 수 있는 전부다.
              style={{ maxHeight: `calc(100vh - ${helpAnchor.bottom + HELP_PANEL_GUTTER}px)` }}
            >
              <p className="mb-2 text-sub font-semibold text-gray-700">단축키 도움말</p>
              <ShortcutCheatSheetContent portalMode={portalMode} />
            </div>
          </div>,
          // 덧띄움은 앵커 «안»에 붙인다 — `document.body` 직하면 포털 채널에서 스타일 격리
          // 범위 밖으로 떨어진다(근거 전문은 `lib/portalOverlayRoot`). [@design INT-013]
      getPortalOverlayRoot(),
    );
  };

  /* ── 포털 채널 레일 ───────────────────────────────────────────────────────
     부모 포털 시안(`AuthoringLabelingView` 의 `rail`)을 그대로 옮긴다 — 묶음 카드는 `ToolPanel`,
     고르는 도구는 `ToolList`(라디오 묶음), 누르는 조작은 `ToolAction`, 켜고 끄는 조작은
     `ToolSwitch`(오른쪽 끝 On·Off)다.

     ★관제 렌더 경로는 아래 그대로 두고 **이 분기만 새로 선다** — 같은 파일 안 두 벌이지만
       항목 정의(`drawItems`·`aiItems`·`viewItems`)와 잠금 판정은 **한 벌을 나눠 쓴다.**
       그래서 도구가 늘거나 잠금 규칙이 바뀌어도 한쪽만 갱신되지 않는다.

     ★말풍선(`tooltip`)을 포털 줄에는 달지 않는다 — 킷 줄은 이름과 단축키를 **늘 보여** 주므로
       얹어야만 뜻이 통하는 자리가 없다. 사유가 있는 비활성만 `title` 로 남긴다.
     ⚠ 단축키 도움말은 **미리 보기 패널을 그대로 쓴다**(시안은 창을 연다). 그 패널은 키보드로도
       열리게 만든 접근성 보정이라 모양만 킷 버튼으로 갈아끼우고 동작은 유지한다. */
  if (portalMode) {
    const selectTool = onSelectTool ?? setActiveTool;
    /** 킷 도구 목록의 한 줄 — 잠금·사유는 관제와 같은 판정(`itemState`)에서 가져온다. */
    const toKitItem = (item: ToolItem): ToolListItem<ToolType> => {
      const st = itemState(item);
      return {
        value: item.tool,
        label: item.label,
        icon: item.icon as NonNullable<ToolListItem<ToolType>['icon']>,
        shortcut: item.shortcut || undefined,
        disabled: st.disabled,
        title: st.title,
      };
    };
    /**
     * 줄 하나 — 고르는 도구는 바깥에서 묶어 세우므로 여기는 누르는 조작만 다룬다.
     *
     * ⚠ **누르는 조작에는 키 이름표를 달지 않는다.** 킷 줄은 `shortcut` 을 오른쪽 끝 **키 배지**로
     *   그리는데, 이 화면의 액션에는 실제 키가 하나도 없고(`shortcut: ''`) 회전만 그 칸을
     *   「현재 N도」라는 **상태 안내**로 빌려 쓴다. 그대로 넘기면 배지 자리에 문장이 들어가
     *   존재하지 않는 단축키를 광고하고, 그 폭에 밀려 이름이 두 줄로 접힌다(실측).
     *   상태 안내는 말풍선(`title`)이 이미 나른다 — 관제 렌더 경로와 같은 자리다.
     *
     * ★**접근성 이름·보조문·시험 후크가 필요한 줄은 킷 부품을 쓰지 않는다.**
     *   킷 `ToolAction`·`ToolSwitch` 는 `aria-label`·`aria-describedby`·`data-testid` 를 받지
     *   않는데, 이 화면에는 그 셋이 **확정 사양**인 줄이 있다 — 도구바의 「AI 자동 추적」은
     *   패널의 실행 버튼과 **같은 이름이면 보조기술 사용자가 둘을 구별할 수 없어** 이름을
     *   달리 하고, 비활성 사유는 `aria-describedby` 로 화면 보조문과 이어야 한다.
     *   그래서 그 줄만 킷과 **같은 클래스·같은 속**을 쓰는 우리 줄로 그린다(킷 원본은 고치지
     *   않는다 — 킷 폴더 주석의 규약). 나머지는 킷 부품 그대로다.
     */
    const renderKitAction = (item: ActionItem, key: number) => {
      const st = itemState(item);
      const label = item.shortLabel ?? item.label;
      const needsOwnRow =
        item.accessibleName !== undefined ||
        item.describedById !== undefined ||
        item.testId !== undefined;

      if (item.pressed !== undefined && !needsOwnRow) {
        return (
          <ToolSwitch
            key={key}
            icon={item.icon as NonNullable<ToolListItem['icon']>}
            label={label}
            checked={item.pressed}
            disabled={st.disabled}
            onChange={item.action}
          />
        );
      }
      if (!needsOwnRow) {
        return (
          <ToolAction
            key={key}
            icon={item.icon as NonNullable<ToolListItem['icon']>}
            label={label}
            disabled={st.disabled}
            title={st.title}
            onClick={item.action}
          />
        );
      }

      const Icon = st.Icon;
      return (
        <button
          key={key}
          type="button"
          className="klid-tool-item"
          aria-label={st.accessibleName}
          aria-describedby={st.describedBy}
          aria-pressed={item.pressed}
          data-testid={item.testId}
          disabled={st.disabled}
          title={st.title}
          onClick={item.action}
        >
          <Icon className="icon" aria-hidden />
          <span className="label">{label}</span>
          {item.pressed !== undefined && (
            <span className="state" aria-hidden>
              {item.pressed ? 'On' : 'Off'}
            </span>
          )}
        </button>
      );
    };
    /**
     * 묶음 하나를 줄들로 편다 — **차례를 지킨다.**
     * 고르는 도구가 이어지는 구간은 라디오 묶음 하나로 합치고, 그 사이의 누르는 조작은 제자리에
     * 둔다. 차례를 무시하고 종류별로 몰아 세우면 시안·사양이 정한 순서가 무너진다.
     */
    const renderKitGroup = (items: Item[], label: string) => {
      const out: React.ReactNode[] = [];
      let run: ToolItem[] = [];
      const flush = (key: number) => {
        if (run.length === 0) return;
        const items = run.map(toKitItem);
        run = [];
        out.push(
          <ToolList
            key={`tools-${key}`}
            label={label}
            items={items}
            value={activeTool}
            onChange={selectTool}
          />,
        );
      };
      items.forEach((item, i) => {
        if (item.kind === 'tool') {
          run.push(item);
          return;
        }
        flush(i);
        if (item.kind === 'action') out.push(renderKitAction(item, i));
      });
      flush(items.length);
      return out;
    };

    return (
      <div className="klid-labeling-rail" role="toolbar" aria-label="라벨링 도구">
        <ToolPanel title="그리기 도구">{renderKitGroup(drawItems, '그리기 도구')}</ToolPanel>

        {aiItems.length > 0 && (
          <ToolPanel title="AI 보조">
            <div className="klid-tool-list" data-testid="label-toolbar-ai-group">
              {renderKitGroup(aiItems, 'AI 보조 도구')}
            </div>
            {/* ★사유 보조문을 킷 `note` 로 넘기지 않는다 — 그 자리는 id 를 받지 않는데,
                비활성 버튼이 `aria-describedby` 로 **이 문장을 가리켜야** 보조기술에 사유가
                닿는다. 킷과 같은 클래스를 써서 생김새는 그대로 두고 id 만 우리가 단다. */}
            {autoTrackUnavailableReason && (
              <p id={autoTrackReasonId} className="klid-tool-panel-note">
                {autoTrackUnavailableReason}
              </p>
            )}
          </ToolPanel>
        )}

        <ToolPanel title="보기" note="회전 중에는 그리기 도구가 잠깁니다.">
          <div className="klid-tool-list">{renderKitGroup(viewItems, '보기 도구')}</div>
        </ToolPanel>

        {/* 도구를 다 훑고 난 자리에서 단축키로 넘어가는 길(시안). 동작은 관제와 같은 미리 보기 패널. */}
        <Button
          size="small"
          variant="tertiary"
          className="klid-labeling-help"
          aria-label="단축키 도움말 미리 보기"
          aria-expanded={helpAnchor !== null}
          aria-describedby={helpAnchor !== null ? 'toolbar-shortcut-help' : undefined}
          data-testid="label-toolbar-shortcut-help"
          title="단축키 도움말 미리 보기"
          onMouseEnter={(e: React.MouseEvent<HTMLElement>) => openHelp(e.currentTarget)}
          onFocus={(e: React.FocusEvent<HTMLElement>) => openHelp(e.currentTarget)}
          onMouseLeave={(e: React.MouseEvent<HTMLElement>) =>
            closeHelpUnlessEnteringPanel(e.relatedTarget)
          }
          onBlur={closeHelp}
        >
          <Keyboard aria-hidden />
          단축키 안내
        </Button>

        {helpAnchor !== null && renderHelpPanel()}
      </div>
    );
  }

  return (
    <div
      className="flex w-52 shrink-0 flex-col border-r border-gray-200 bg-gray-50"
      role="toolbar"
      aria-label="라벨링 도구"
    >
      <div
        className={TOOLBAR_SCROLL_CLASS}
        data-testid="label-toolbar-scroll"
        // 스크롤하면 툴팁 좌표가 낡는다 — 포인터가 그대로여도 위치가 어긋나므로 즉시 닫는다.
        onScroll={() => setTooltip(null)}
      >
        <section className={RAIL_CARD_CLASS}>
          {/* 내부 채널은 종전 제목 그대로 둔다(관제향 무변경). 포털은 세 묶음의 짧은 제목. */}
          <h2 className={RAIL_CARD_TITLE_CLASS}>{portalMode ? '그리기' : '그리기 도구'}</h2>
          <div className="flex flex-col gap-1">
            {drawItems.map((item, idx) =>
              item.kind === 'divider' ? (
                <div key={idx} className="my-1 h-px bg-gray-200" />
              ) : (
                renderToolRow(item, idx)
              ),
            )}
          </div>
        </section>

        {portalMode && aiItems.length > 0 && (
          <section className={RAIL_CARD_CLASS} data-testid="label-toolbar-ai-group">
            <h2 className={RAIL_CARD_TITLE_CLASS}>AI 보조</h2>
            <div className="flex flex-col gap-1">
              {aiItems.map((item, idx) =>
                item.kind === 'divider' ? (
                  <div key={idx} className="my-1 h-px bg-gray-200" />
                ) : (
                  renderToolRow(item, idx)
                ),
              )}
            </div>
            {/* 비활성 사유는 눌러 봐야 알 수 있는 것이 아니라 미리 보여야 한다 — 툴팁만 두면
                마우스를 올리지 않는 사용자·보조기술 사용자에게 닿지 않는다. */}
            {onFocusAutoTrack && autoTrackUnavailableReason && (
              <p id={autoTrackReasonId} className="px-2 pt-1 text-caption text-gray-600">
                {autoTrackUnavailableReason}
              </p>
            )}
          </section>
        )}

        <section className={RAIL_CARD_CLASS}>
          <h2 className={RAIL_CARD_TITLE_CLASS}>보기</h2>
          <div className="flex flex-col gap-1">
            {viewItems.map((item, idx) =>
              item.kind === 'divider' ? (
                <div key={idx} className="my-1 h-px bg-gray-200" />
              ) : (
                renderViewRow(item, idx)
              ),
            )}
          </div>
          {/* 회전 잠금은 눌러 봐야 알 수 있는 것이 아니라 미리 알려야 하는 제약이다(시안 캡션). */}
          <p className="px-2 pt-1 text-caption text-gray-600">
            회전 중에는 그리기 도구가 잠깁니다.
          </p>
        </section>
      </div>

      {/* 단축키 도움말 — 도구바 **맨 아래** 고정(스크롤 상자 밖이라 목록이 넘쳐도 항상 보인다).
          표 본문은 ShortcutCheatSheetContent 단일 출처를 그대로 담는다(표기 복제 금지). */}
      <div className="shrink-0 border-t border-gray-200 p-2">
        <button
          type="button"
          // ★접근성 이름은 헤더의 `?` 버튼과 **구분**한다 — 같은 화면에 같은 이름의 버튼이 둘이면
          //   보조기술 사용자가 서로 다른 UI(도구바 미리보기 / 전체 모달)를 구별할 수 없다.
          //   두 진입점 모두 확정 사양이라 한쪽을 없애는 것은 답이 아니다.
          aria-label="단축키 도움말 미리 보기"
          aria-expanded={helpAnchor !== null}
          aria-describedby={helpAnchor !== null ? 'toolbar-shortcut-help' : undefined}
          data-testid="label-toolbar-shortcut-help"
          title="단축키 도움말 미리 보기"
          onMouseEnter={(e) => openHelp(e.currentTarget)}
          onFocus={(e) => openHelp(e.currentTarget)}
          onMouseLeave={(e) => closeHelpUnlessEnteringPanel(e.relatedTarget)}
          onBlur={closeHelp}
          // 시안 `.shortcut-trigger` — 점선 테두리 + 항목명. 아이콘만 두면 이 자리가 무슨 기능인지
          // 호버해 봐야 알 수 있다(사양은 "맨 아래 고정 위치에 단축키 안내를 둔다").
          className="flex min-h-[40px] w-full items-center gap-2 rounded-md border border-dashed border-gray-300 bg-white px-2 text-body-sm font-semibold text-gray-700 transition-colors hover:bg-gray-50 hover:text-gray-900"
        >
          <Keyboard size={16} aria-hidden="true" className="shrink-0" />
          <span className="truncate">단축키 안내</span>
        </button>
      </div>
      {renderHelpPanel()}

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
          // 덧띄움은 앵커 «안»에 붙인다 — `document.body` 직하면 포털 채널에서 스타일 격리
          // 범위 밖으로 떨어진다(근거 전문은 `lib/portalOverlayRoot`). [@design INT-013]
          getPortalOverlayRoot(),
        )}
    </div>
  );
}
