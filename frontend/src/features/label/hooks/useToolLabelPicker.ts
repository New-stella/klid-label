// 도형 도구 ↔ 라벨 선택 모달 연결 (2026-08-03 사용자 확정).
//
// 조작 흐름: 도형 도구 클릭/전환 → 라벨 선택 모달 → 라벨 확정 후 드로잉 시작.
// 취소하면 도구를 활성화하지 않고 이전 도구로 되돌린다.
//
// ★ 판정은 여기 한 곳에만 둔다 — 툴바 클릭과 키보드 단축키가 각자 모달을 띄우도록 배선하면
//   한쪽이 반드시 뒤처진다(이 프로젝트의 "진입점마다 정책 복제" 결함 패턴). 이 훅은
//   `activeTool` 전이를 감시하므로 어떤 경로로 도구가 바뀌든 동일하게 동작하고,
//   `requestTool` 은 "이미 활성인 도구를 다시 클릭"(전이가 없어 감시로는 못 잡는 경우)만 보완한다.
//
// 모달 재노출 조건 = **도구 전이 1회**. 도형 하나 그릴 때마다 뜨지 않으며(연속 작업 시
// 마지막 선택 라벨 유지), 같은 도구 버튼을 다시 누르면 라벨을 바꿀 수 있다.

import { useCallback, useEffect, useRef, useState } from 'react';

import { useLabelStore } from '@/stores/useLabelStore';

import { ToolType } from '../types';

/**
 * 라벨이 있어야 그릴 수 있는 도구 — 신규 라벨을 생성하며 `resolveDefaultLabel` 로 라벨 마스터를
 * 확정하는 도구들(OverlayLayer 의 드로잉 경로와 동일 집합).
 *
 * 제외: SELECT/PAN(선택·이동), TRACK(이미 선택된 객체의 라벨을 그대로 전파),
 *       MASK_BRUSH/MASK_ERASER(기존 마스크 편집), 삭제/실행취소 등 액션 버튼.
 */
export const LABEL_REQUIRED_TOOLS: readonly ToolType[] = [
  ToolType.BBOX,
  ToolType.POLYGON,
  ToolType.SAM_SEGMENT,
  ToolType.KEYPOINT,
];

export function isLabelRequiredTool(tool: ToolType): boolean {
  return LABEL_REQUIRED_TOOLS.includes(tool);
}

export interface ToolLabelPicker {
  /** 모달 노출 여부. */
  open: boolean;
  /** 모달을 띄운 도구(안내 문구용). */
  pendingTool: ToolType | null;
  /** 툴바 버튼 클릭 핸들러 — DarkToolbar 의 도구 선택을 이 훅으로 위임한다. */
  requestTool: (tool: ToolType) => void;
  /** 라벨 확정. */
  confirm: (labelId: number) => void;
  /** 취소 — 도구를 이전 상태로 되돌린다. */
  cancel: () => void;
}

export function useToolLabelPicker(): ToolLabelPicker {
  const activeTool = useLabelStore((s) => s.activeTool);
  const setActiveTool = useLabelStore((s) => s.setActiveTool);
  const setActiveLabelId = useLabelStore((s) => s.setActiveLabelId);

  const [open, setOpen] = useState(false);
  const [pendingTool, setPendingTool] = useState<ToolType | null>(null);

  // 직전 도구(전이 감지) / 취소 시 복귀 대상 / 복귀로 인한 재노출 1회 억제.
  const prevToolRef = useRef<ToolType>(activeTool);
  const revertToolRef = useRef<ToolType>(activeTool);
  const suppressRef = useRef(false);

  useEffect(() => {
    const prev = prevToolRef.current;
    prevToolRef.current = activeTool;
    if (prev === activeTool) return;

    // 취소로 되돌린 전이는 모달을 다시 띄우지 않는다(이전 도구도 라벨 필요 도구일 수 있다).
    if (suppressRef.current) {
      suppressRef.current = false;
      return;
    }

    if (!isLabelRequiredTool(activeTool)) {
      setOpen(false);
      setPendingTool(null);
      return;
    }

    revertToolRef.current = prev;
    setPendingTool(activeTool);
    setOpen(true);
  }, [activeTool]);

  const requestTool = useCallback(
    (tool: ToolType) => {
      // 이미 활성인 도구를 다시 클릭 → 전이가 없어 위 감시가 걸리지 않으므로 직접 연다
      // (라벨을 바꿀 유일한 동선). 라벨 불필요 도구는 그대로 no-op.
      if (tool === activeTool) {
        if (!isLabelRequiredTool(tool)) return;
        revertToolRef.current = tool;
        setPendingTool(tool);
        setOpen(true);
        return;
      }
      setActiveTool(tool);
    },
    [activeTool, setActiveTool],
  );

  const confirm = useCallback(
    (labelId: number) => {
      setActiveLabelId(labelId);
      setOpen(false);
      setPendingTool(null);
    },
    [setActiveLabelId],
  );

  const cancel = useCallback(() => {
    setOpen(false);
    setPendingTool(null);
    const revert = revertToolRef.current;
    if (revert !== activeTool) {
      suppressRef.current = true;
      setActiveTool(revert);
    }
  }, [activeTool, setActiveTool]);

  return { open, pendingTool, requestTool, confirm, cancel };
}
