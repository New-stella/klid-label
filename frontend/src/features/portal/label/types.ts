// 포털 라벨링 편집기가 라벨링 화면(`LabelingPage`)에서 받는 값 — 이미 계산된 상태와 핸들러다. [@design SCREEN-029]

import type { RefObject } from 'react';

import type { CanvasRotation } from '@/features/label/canvas/CanvasShell';
import type { OverlayLayerHandle } from '@/features/label/canvas/layers/OverlayLayer';
import type { ToolLabelPicker } from '@/features/label/hooks/useToolLabelPicker';
import type { FrameSummary, Label, ToolType } from '@/features/label/types';
import type { UploadLabelNotice } from '@/features/portal/uploads/hooks/useUploadLabelSource';

import type {
  PortalBusy,
  PortalCloseConfirm,
  PortalNavGuard,
  PortalSaveConflict,
} from './components/PortalLabelingDialogs';

export type RightTab = 'objects' | 'meta' | 'issues';

export interface PortalLabelingViewProps {
  /** 편집기를 세우기 전의 상태 — 하나라도 걸리면 편집기 대신 안내 판이 선다. */
  status: {
    /** 데이터마트 갈래인데 주소의 번호가 숫자가 아니다. */
    invalidId: boolean;
    /** 업로드 자산 갈래의 상태 안내(준비 중 · 처리 실패 · 프레임 없음 · 못 불러옴 · 잘못된 주소). */
    uploadNotice: UploadLabelNotice | null;
    isLoading: boolean;
    error: Error | null;
    onBack: () => void;
  };
  header: {
    /** 머리 줄 이름 — 업로드는 파일명, 데이터마트는 「프레임 #번호」. 없으면 「프레임 N」. */
    title: string | undefined;
    dirty: boolean;
    saving: boolean;
    onClose: () => void;
  };
  frames: {
    list: FrameSummary[];
    index: number;
    current: FrameSummary | undefined;
    onRequestGoTo: (index: number) => void;
    savedSrcSns: Set<number>;
    discardedSrcSns: Set<number>;
    uploadSource: boolean;
  };
  edit: {
    labels: Label[];
    dirtyCount: number;
    isEditBlocked: boolean;
    isLocked: boolean;
    isDiscarded: boolean;
    discardPending: boolean;
    onSave: () => void | Promise<void>;
  };
  view: {
    rotation: CanvasRotation;
    onRotate: (deltaDeg: -90 | 90) => void;
    zoomAreaMode: boolean;
    onToggleZoomArea: () => void;
    showGrid: boolean;
    onToggleGrid: () => void;
    onSelectTool: (tool: ToolType) => void;
  };
  canvas: {
    handleRef: RefObject<OverlayLayerHandle | null>;
    onLabelAdd: (label: Label) => void;
    onImageSize: (width: number, height: number) => void;
    onKeypointPlacingChange: (placingIndex: number | null) => void;
    immediateSegment: boolean;
    segmentSimplifyTolerance: number | undefined;
    imageLoading: boolean;
    imageError: Error | null;
    imageErrorHint: string | undefined;
    frameNaturalSize: { width: number; height: number } | undefined;
  };
  busy: PortalBusy;
  panel: {
    tab: RightTab;
    onTabChange: (tab: RightTab) => void;
    srcSn: number | undefined;
    rawSn: number | undefined;
  };
  labelPicker: ToolLabelPicker;
  dialogs: {
    close: PortalCloseConfirm;
    nav: PortalNavGuard;
    conflict: PortalSaveConflict;
    shortcuts: { open: boolean; onOpenChange: (open: boolean) => void };
  };
}
