// SCR-LABEL-001 라벨링 캔버스 페이지 — 풀스크린 다크 UI (mock 정합).
//
// 레이아웃:
//   ┌─ LabelHeader (h-14, bg-gray-800)
//   ├─ flex-1: [DarkToolbar w-14] [Canvas flex-1] [RightPanel w-72]
//   └─ Bottom (h-30): [DarkFrameStrip h-15] [DarkFrameSlider h-15]
//
// 라우트는 AppLayout 밖에서 직접 매칭되므로 LNB/GNB 없는 풀스크린.
// 보안: 사용자 입력 ID는 axios가 URL 인코딩. BE에서 IDOR/Mass Assignment 방어.

import { Suspense, lazy, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { Modal } from '@/components/common/Modal';
import { Spinner } from '@/components/common/Spinner';
import { LabelHeader } from '@/features/label/components/LabelHeader';
import { AiToolModal } from '@/features/label/components/AiToolModal';
import { DarkToolbar } from '@/features/label/components/DarkToolbar';
import { DeidentReportButton } from '@/features/label/components/DeidentReportButton';
import { LabelSidebar } from '@/features/label/components/LabelSidebar';
import { ObjectClassTree } from '@/features/label/components/ObjectClassTree';
import {
  autolabelItemToLabel,
  deleteTrack,
  mergeTracks,
  snapshotToLabel,
  splitTrack,
  trackedItemToLabel,
  type DetectShapeType,
  type LabelHistoryItem,
  type Sam2TrackedItem,
} from '@/features/label/api';
import type { AiToolMode, AiToolOpts } from '@/features/label/components/AiToolModal';
import { YOLO_CLASSES } from '@/features/label/constants/yoloClasses';
import { ToolType } from '@/features/label/types';
import { ObjectAttributePanel } from '@/features/label/components/ObjectAttributePanel';
import { ImageAdjustPanel } from '@/features/label/components/ImageAdjustPanel';
import { TimeseriesSidePanel } from '@/features/label/components/TimeseriesSidePanel';
import { FrameDescriptionPanel } from '@/features/label/components/FrameDescriptionPanel';
import { EventAnnotationPanel } from '@/features/label/components/EventAnnotationPanel';
import { IssueThreadPanel } from '@/features/review/components/IssueThreadPanel';
import { useIssueThreads } from '@/features/review/hooks/useIssueThreads';
import { DarkFrameStrip } from '@/features/label/components/DarkFrameStrip';
import { DarkFrameSlider } from '@/features/label/components/DarkFrameSlider';
import { FrameNavGuardModal } from '@/features/label/components/FrameNavGuardModal';
import { ShortcutCheatSheet } from '@/features/label/components/ShortcutCheatSheet';
import { useImageBlob } from '@/features/label/hooks/useImageBlob';
import { useLabelingShortcuts } from '@/features/label/hooks/useLabelingShortcuts';
import { useAutolabel } from '@/features/label/hooks/useAutolabel';
import { useConfigs } from '@/features/sysconfig/hooks/useConfigs';
import { useLabels } from '@/features/label/hooks/useLabels';
import { useUpdateLabels } from '@/features/label/hooks/useUpdateLabels';
import { useSavePortalLabels } from '@/features/portal/hooks/useSavePortalLabels';
import type { FrameSummary, Label } from '@/features/label/types';
import type { OverlayLayerHandle } from '@/features/label/canvas/layers/OverlayLayer';
import { useSubmitReview, useCancelSubmitReview } from '@/features/review/hooks/useReviewActions';
import { useReview } from '@/features/review/hooks/useReview';
import { HistoryPanel } from '@/features/version/components/HistoryPanel';
import { Role } from '@/lib/api/types';
import { LABEL_KEYS } from '@/lib/queryKeys';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore, shouldResetView } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

// konva는 브라우저 전용 — lazy load로 초기 번들 분리
const CanvasShell = lazy(() =>
  import('@/features/label/canvas/CanvasShell').then((m) => ({ default: m.CanvasShell })),
);

// 캔버스 컨테이너에서 자동 측정해 ResponsiveCanvas로 전달
function useContainerSize<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });

  // useLayoutEffect — 첫 페인트 전 동기 측정으로 캔버스 마운트 가드(`size.width > 0`) 통과 보장
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => {
      const rect = el.getBoundingClientRect();
      setSize({ width: Math.floor(rect.width), height: Math.floor(rect.height) });
    };
    const observer = new ResizeObserver(update);
    observer.observe(el);
    update();
    return () => observer.disconnect();
  }, []);

  return [ref, size] as const;
}

/**
 * SCR-LABEL-001 라벨링 캔버스 페이지 (다크 풀스크린).
 */
export function LabelingPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const numericId = id ? Number(id) : NaN;

  // 채널/역할 가드
  const portalMode = useAuthStore((s) => s.claims?.channel === 'PORTAL');
  const role = useAuthStore((s) => s.claims?.role);
  const isWorker = role === Role.WORKER;
  const isReviewer = role === Role.REVIEWER;
  // INTERNAL 채널 + WORKER/REVIEWER 만 비식별 누락 신고 가능 (포털 회원은 미노출)
  const canReportDeident = !portalMode && (isWorker || isReviewer);
  const pushToast = useUiStore((s) => s.pushToast);

  const { data, isLoading, error } = useLabels(
    Number.isFinite(numericId) ? numericId : undefined,
    portalMode,
  );

  // BE 의 인증 보호된 프레임 이미지 API 를 axios 로 fetch → blob URL 발급.
  // <img>/Image() 직접 호출은 Bearer 토큰 누락으로 401. CSP 의 img-src blob: 허용 활용.
  // R16 — 포털 모드는 /portal/frames/{id}/image (내부 /frames/{id}/image 는 PORTAL 채널 403).
  const { url: imageBlobUrl } = useImageBlob(data?.srcSn, { portalMode });

  const { mutate: submitForReview, isPending: submitting } = useSubmitReview({
    onSuccess: () => {
      pushToast({ variant: 'success', message: '검수 제출 완료' });
      navigate('/task');
    },
    onError: () => pushToast({ variant: 'error', message: '검수 제출 실패' }),
  });

  const { mutate: cancelSubmitForReview, isPending: cancelling } = useCancelSubmitReview({
    onSuccess: () => pushToast({ variant: 'success', message: '검수 제출 취소 완료' }),
    onError: () => pushToast({ variant: 'error', message: '검수 제출 취소 실패' }),
  });

  // R6-B / R12-2 검수제출 가드 — 작업(검수 워크플로우) 상태가 제출 가능 상태일 때만 버튼 enabled.
  // 작업 상태는 LabelsResponse 에 없으므로 reviews/{videoId} 조회로 보강 (WORKER 만).
  // 제출 가능 상태:
  //   - ASSIGNED(배치 완료/배정 직후) / REJECTED(반려 후 재제출)
  //   - COMPLETED(=APPROVED, BE mapToFeStatus 매핑): 검수완료본도 재검수 진입 허용
  //     (CLAUDE.md '검수완료 후 수정→재검수→재승인 시 새 버전 적층'. BE 는 APPROVED→PENDING 재제출 200 허용).
  // 차단 상태: REVIEW_PENDING(=PENDING/이미 제출), REVIEWING(=IN_REVIEW/검수 진행 중).
  // 상태 조회 실패/로딩 중에는 차단하지 않는다(기존 UX 유지) — BE 가 최종 가드.
  const workVideoId = data?.videoId;
  const { data: reviewInfo } = useReview(isWorker ? workVideoId : undefined);
  const workStatus = reviewInfo?.status;
  // BE ReviewResponse.mapToFeStatus 매핑: ASSIGNED/REJECTED 는 원본/REJECTED 코드로, APPROVED→COMPLETED.
  // 'APPROVED' 도 방어적으로 포함(BE 매핑 변경 대비).
  const SUBMITTABLE_STATUSES = ['ASSIGNED', 'REJECTED', 'COMPLETED', 'APPROVED'] as const;
  const submitBlockedByStatus =
    workStatus !== undefined &&
    !(SUBMITTABLE_STATUSES as readonly string[]).includes(workStatus);
  // 제출 취소 노출 조건 — 제출됨(REVIEW_PENDING = 검수 시작 전)일 때만 WORKER 본인에게 노출.
  // 검수 시작(REVIEWING)/승인(COMPLETED)/반려(REJECTED) 상태에서는 취소 불가(버튼 미노출, BE 도 거부).
  const canCancelSubmit = isWorker && workStatus === 'REVIEW_PENDING';
  // 검수완료(APPROVED→COMPLETED 매핑) 상태에서의 제출은 '재검수' — 완료본을 다시 건드린다는 인지를 위해 문구 구분.
  const isResubmitOfApproved = workStatus === 'COMPLETED';
  const submitButtonLabel = isResubmitOfApproved ? '재검수 제출' : '검수제출';
  const submitStatusHint = (() => {
    switch (workStatus) {
      case 'REVIEW_PENDING':
        return '이미 검수 제출되어 검수 대기 중입니다.';
      case 'REVIEWING':
        return '검수가 진행 중이라 다시 제출할 수 없습니다.';
      default:
        return '현재 상태에서는 검수 제출할 수 없습니다.';
    }
  })();

  const setLabels = useLabelStore((s) => s.setLabels);
  const labels = useLabelStore((s) => s.labels);
  const dirtyCount = useLabelStore((s) => s.dirtyLabels.size);
  const clearDirty = useLabelStore((s) => s.clearDirty);
  const addLabel = useLabelStore((s) => s.addLabel);
  const mergeAutoLabels = useLabelStore((s) => s.mergeAutoLabels);
  const revertSaveEvent = useLabelStore((s) => s.revertSaveEvent);
  const stashPendingTracks = useLabelStore((s) => s.stashPendingTracks);
  const drainPendingTracks = useLabelStore((s) => s.drainPendingTracks);
  const setActiveTool = useLabelStore((s) => s.setActiveTool);
  const reset = useLabelStore((s) => s.reset);
  const resetView = useLabelStore((s) => s.resetView);
  const toggleLabelVisibility = useLabelStore((s) => s.toggleLabelVisibility);
  const copyLabels = useLabelStore((s) => s.copyLabels);
  const pasteLabels = useLabelStore((s) => s.pasteLabels);

  // BE 의 LabelResponse.siblings 로 영상 전체 프레임 표시.
  // 메인 캔버스(currentFrame)는 imageBlobUrl(현재 프레임)만 채우고, strip 의 다른 프레임 썸네일은
  // DarkFrameStrip 내부 FrameThumbnail 이 srcSn 별로 useImageBlob 을 호출해 자체 fetch.
  const frames: FrameSummary[] = useMemo(() => {
    if (!data) return [];
    const siblings = Array.isArray(data.siblings) ? data.siblings : [];
    // siblings 가 비어 있으면(레거시 응답 호환) 현재 프레임 단건으로 폴백.
    if (siblings.length === 0) {
      return [
        {
          frameNo: data.frameNo,
          srcSn: data.srcSn,
          thumbnailUrl: imageBlobUrl ?? '',
          imageUrl: imageBlobUrl ?? '',
          // imageWidth/Height 는 하드코딩하지 않는다 — 캔버스가 로드된 이미지의 실측
          // naturalWidth/Height 를 geometry 기준으로 사용한다(좌표 어긋남/스트레치 방지).
        },
      ];
    }
    return siblings.map((s) => {
      const isCurrent = s.srcSn === data.srcSn;
      // 메인 캔버스는 현재 프레임의 imageBlobUrl 만 사용. 나머지 썸네일은 strip 이 자체 fetch.
      return {
        frameNo: s.frameNo,
        srcSn: s.srcSn,
        thumbnailUrl: '',
        imageUrl: isCurrent ? (imageBlobUrl ?? '') : '',
        // imageWidth/Height 하드코딩 제거 — 캔버스가 실측 naturalWidth/Height 사용.
      };
    });
  }, [data, imageBlobUrl]);

  // 현재 프레임의 인덱스는 siblings 위치를 기준으로 계산.
  const frameIdx = useMemo(() => {
    if (!data) return 0;
    const idx = frames.findIndex((f) => f.srcSn === data.srcSn);
    return idx >= 0 ? idx : 0;
  }, [frames, data]);
  const currentFrame = frames[frameIdx];

  // 로드된 프레임 이미지의 실측 네이티브 픽셀 크기 — CanvasShell 이 이미지 onload 시 통지.
  // 수치 좌표 편집(ObjectAttributePanel)·붙여넣기 clamp 가 캔버스 geometry 와 동일한 실측
  // dims 를 쓰도록 상위로 리프팅한다(하드코딩 1920×1080 제거, 좌표 기준 통일).
  const [frameNaturalSize, setFrameNaturalSize] = useState<
    { width: number; height: number } | undefined
  >(undefined);
  // R3 — 뷰(zoom/pan) 유지 판정 기준: 마지막으로 뷰를 확정한 프레임의 (영상ID + 실측 해상도).
  // 프레임 전환 effect 에서 무조건 resetView() 하던 방식을 제거하고, 새 이미지 로드 완료 시점
  // (handleImageSize)에 이전 프레임과 영상·해상도를 비교해 유지/리셋을 결정한다.
  const viewKeyRef = useRef<{ videoId?: number; width: number; height: number } | null>(null);
  // handleImageSize 를 stable 하게 유지하기 위해 최신 videoId 를 ref 로 전달(렌더 순수성 위해 effect 로 동기화).
  const videoIdRef = useRef<number | undefined>(undefined);
  useEffect(() => {
    videoIdRef.current = data?.videoId;
  }, [data?.videoId]);
  // 프레임 전환 시 이전 실측 크기만 초기화 — 새 이미지 로드 완료 전까지 undefined(상한 clamp 미적용).
  // 뷰 리셋은 여기서 하지 않는다(동일영상·동일해상도면 zoom/pan 유지). 판정은 handleImageSize.
  useEffect(() => {
    setFrameNaturalSize(undefined);
  }, [currentFrame?.srcSn]);
  const handleImageSize = useCallback(
    (width: number, height: number) => {
      setFrameNaturalSize({ width, height });
      const videoId = videoIdRef.current;
      const next = { videoId, width, height };
      // 초기 진입/영상 변경/해상도 상이 → fit 리셋. 동일 영상+동일 해상도 → 뷰 유지(no reset).
      if (shouldResetView(viewKeyRef.current, next)) {
        resetView();
      }
      viewKeyRef.current = next;
    },
    [resetView],
  );

  // 다른 프레임으로 실제 이동 — URL 전환 (useLabels 가 재조회).
  // replace=true: history stack 에 push 하지 않음 — X(닫기) 버튼이 뒤로가기 시
  // 이전 프레임이 아닌 진입 이전 경로(작업 목록)로 빠져나가도록 한다.
  const performJump = (idx: number) => {
    const target = frames[idx];
    if (!target || !data) return;
    if (target.srcSn !== data.srcSn) {
      navigate(`/label/${target.srcSn}`, { replace: true });
    }
  };

  // R5 — 프레임 이동 미저장 가드. dirtyLabels.size>0 이면 확인 모달(저장 후 이동/저장 안 함/취소),
  // 아니면 즉시 이동. 슬라이더/썸네일/단축키 프레임 이동이 모두 이 함수를 경유한다.
  // navGuardTarget 에 대기 중인 이동 대상 인덱스를 보관한다(null = 가드 비활성).
  const [navGuardTarget, setNavGuardTarget] = useState<number | null>(null);
  const [navGuardSaving, setNavGuardSaving] = useState(false);
  const requestJumpTo = (idx: number) => {
    const target = frames[idx];
    if (!target || !data) return;
    // 같은 프레임(경계 클램프로 인한 no-op)은 가드 없이 무시.
    if (target.srcSn === data.srcSn) return;
    if (dirtyCount > 0) {
      setNavGuardTarget(idx);
    } else {
      performJump(idx);
    }
  };
  // 저장 후 이동 — 저장 성공 시에만 이동, 실패 시 이동 취소 + 에러 토스트(현재 프레임 유지).
  const handleNavSaveAndMove = async () => {
    if (navGuardTarget === null) return;
    const targetIdx = navGuardTarget;
    if (!currentFrame) {
      setNavGuardTarget(null);
      performJump(targetIdx);
      return;
    }
    setNavGuardSaving(true);
    try {
      await updateLabels(labels);
      clearDirty();
      setNavGuardTarget(null);
      performJump(targetIdx);
    } catch (e) {
      pushToast({
        variant: 'error',
        message: e instanceof Error ? e.message : '저장 실패',
      });
      setNavGuardTarget(null); // 이동 취소 — 현재 프레임 유지
    } finally {
      setNavGuardSaving(false);
    }
  };
  // 저장 안 함 — 미저장 변경 폐기(clearDirty) 후 이동.
  const handleNavDiscardAndMove = () => {
    if (navGuardTarget === null) return;
    const targetIdx = navGuardTarget;
    clearDirty();
    setNavGuardTarget(null);
    performJump(targetIdx);
  };
  // 취소 — 현재 프레임 유지(변경/ dirty 보존).
  const handleNavCancel = () => setNavGuardTarget(null);

  // 데이터 동기화 — data 변경 시 store 의 labels 를 갱신.
  // setLabels 내부에서 dirtyLabels/undoStack/redoStack/selectedLabelId 를 초기화하므로
  // 프레임 전환 시점에 별도 reset() 호출은 불필요하다. (cleanup 에서 reset 호출하면
  // 매 data 변경마다 store 가 완전 초기화돼 깜빡임/라벨 사라짐 회귀가 발생함)
  //
  // HIGH #2 — 오토라벨/추적 병합(미저장) 보호:
  //  - 프레임 전환(srcSn 변경) 시점에만 setLabels 로 전체 교체한다.
  //  - 같은 프레임 refetch(백그라운드) 로 도착한 data 는 미저장 편집(dirty>0)이 있으면 덮어쓰지 않는다.
  //    (getState 로 최신 dirty 를 읽어 selector 재구독에 따른 stale 판단을 피한다.)
  const lastLoadedSrcSnRef = useRef<number | undefined>(undefined);
  useEffect(() => {
    if (!data) return;
    const nextLabels = Array.isArray(data.labels) ? data.labels : [];
    const frameChanged = lastLoadedSrcSnRef.current !== data.srcSn;
    if (frameChanged) {
      lastLoadedSrcSnRef.current = data.srcSn;
      setLabels(nextLabels);
      return;
    }
    // 같은 프레임 재조회 — 미저장 병합/편집이 없을 때만 최신 서버 라벨로 동기화.
    if (useLabelStore.getState().dirtyLabels.size === 0) {
      setLabels(nextLabels);
    }
  }, [data, setLabels]);

  // R12 — 보류 추적 결과 drain. 위 setLabels effect 다음에 선언해 프레임 진입 시 setLabels(서버
  // 라벨 로드 + dirty 초기화) 가 먼저 적용된 뒤 보류분을 병합하도록 순서를 보장한다(setLabels 가
  // drain 병합을 덮지 않게). 진입 프레임(srcSn)에 보류가 있으면 mergeAutoLabels 로 dedup 병합한다
  // — 기존 라벨 보존·중복 스킵이라 dirty 편집을 덮어쓰지 않는다. drain 후 해당 보류는 제거된다.
  useEffect(() => {
    const srcSn = data?.srcSn;
    if (srcSn === undefined) return;
    if (!useLabelStore.getState().pendingTracks[srcSn]) return;
    const drained = drainPendingTracks(srcSn);
    if (drained.length === 0) return;
    const added = mergeAutoLabels(drained);
    if (added > 0) {
      pushToast({ variant: 'info', message: `보류된 AI 추적 ${added}건 적용됨` });
    }
    // data?.srcSn 만 의존 — 같은 프레임 refetch(data 객체 교체)에는 재실행되지 않아 이중 병합 없음.
    // (drain 이 보류를 제거하므로 재실행돼도 no-op 이지만, 프레임 진입당 1회로 명확히 제한한다.)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data?.srcSn, drainPendingTracks, mergeAutoLabels, pushToast]);

  // 컴포넌트 unmount 시에만 store 를 완전 초기화 — 다른 화면으로 빠져나갈 때 잔존 상태 제거.
  useEffect(() => {
    return () => {
      reset();
    };
  }, [reset]);

  // 우측 히스토리 인라인 패널 토글 (포털 모드/미로그인 시 미노출 — showHistory 가드 재사용)
  const [historyOpen, setHistoryOpen] = useState(false);
  // 저장 이벤트 되돌리기 확인 대상. null 이면 확인모달 닫힘.
  const [revertTarget, setRevertTarget] = useState<LabelHistoryItem | null>(null);

  // R4 — 단축키 치트시트(도움말) 모달 열림 상태. ?(shift+/) 단축키 또는 헤더 도움말 버튼으로 토글.
  const [cheatSheetOpen, setCheatSheetOpen] = useState(false);

  // 우측 패널 탭 — 객체 / 메타 / 이슈(검수자↔작업자 소통). 이슈 스레드는 INTERNAL 채널만.
  //   객체 = 객체 목록 + 속성 / 메타 = 프레임 설명 + 시계열 메타(VLM) / 이슈 = 이슈 스레드
  const [rightTab, setRightTab] = useState<'objects' | 'meta' | 'issues'>('objects');
  // 이슈는 영상 단위(rawSn) — videoId(LS_DATA_RAW.RAW_SN)만 사용.
  // srcSn(프레임 PK) 폴백 금지: 프레임 PK를 영상 ID 자리에 넣으면 잘못된 영상의 이슈 조회/404.
  // videoId 부재 시 이슈 탭 비노출.
  const issueRawSn = data?.videoId;
  const showIssues = !portalMode && issueRawSn !== undefined;
  // 메타 탭(프레임 설명 + 시계열 메타)은 내부 채널만 노출 — 포털은 VLM/메타 미제공(ADR-013).
  const showMeta = !portalMode;
  const hasTabs = showMeta || showIssues;
  const { data: issueThreads } = useIssueThreads(showIssues ? issueRawSn : undefined);
  const unresolvedInquiries = (issueThreads ?? []).filter(
    (t) => t.issueTypeCd === 'INQUIRY' && t.issueSttsCd !== 'RESOLVED',
  ).length;

  // 프레임 썸네일 상태색 — issueThreads 를 INQUIRY srcSn 집합으로 가공해 DarkFrameStrip 에 주입한다.
  // resolveFrameStatus 우선순위: 현재>확인요청(빨강)>저장(연두). srcSn 이 null 인 영상 단위 이슈는
  // 특정 썸네일에 귀속할 수 없어 제외한다. v2 반려는 영상 단위(REJECTION.srcSn=null)라 프레임색 미대상.
  const inquirySrcSns = useMemo(() => {
    const set = new Set<number>();
    for (const t of issueThreads ?? []) {
      // 미해소 문의만 빨강 강조(해소된 문의는 더 이상 주의 대상 아님).
      if (t.issueTypeCd === 'INQUIRY' && t.issueSttsCd !== 'RESOLVED' && t.srcSn != null) {
        set.add(t.srcSn);
      }
    }
    return set;
  }, [issueThreads]);
  // 저장된 프레임(라벨 존재) 집합 — BE LabelResponse.siblings[].hasLabel 로 형제 프레임 전체를 판정.
  // 현재 프레임은 resolveFrameStatus 에서 CURRENT 가 우선하므로 SAVED 로 덮이지 않고, 저장된 형제
  // 프레임(현재 아님)만 연두(SAVED)로 표시된다 → 4색 전부 실동작.
  // 현재 프레임은 응답 labels 가 로드된 즉시(hasLabel 반영 전 경합 대비) 함께 포함해 정합을 보장한다.
  const savedSrcSns = useMemo(() => {
    const set = new Set<number>();
    for (const s of data?.siblings ?? []) {
      if (s.hasLabel) set.add(s.srcSn);
    }
    if (data && Array.isArray(data.labels) && data.labels.length > 0) set.add(data.srcSn);
    return set;
  }, [data]);

  // 비식별 누락 신고 — 영상 잠금 상태 추적.
  // 1) BE 응답 lockSttsCd='LOCKED_FOR_REDEIDENT' → 진입 시 잠금
  // 2) 신고 성공 직후 → 클라이언트 측 reportedLock=true 로 즉시 잠금
  //    (BE 가 lockSttsCd 를 보장하지 않는 케이스 대비 — Phase 3 보강 권고)
  const [reportedLock, setReportedLock] = useState(false);
  const isLocked = data?.lockSttsCd === 'LOCKED_FOR_REDEIDENT' || reportedLock;
  // 영상이 변경되면 클라이언트 측 잠금 마킹 초기화 (다른 영상 진입 시 잘못된 잠금 표시 방지)
  useEffect(() => {
    setReportedLock(false);
  }, [data?.videoId, data?.srcSn]);

  // 비식별 누락 신고 성공 처리.
  // BE 는 신고 접수 시 해당 영상(rawSn)의 라벨을 전체 삭제하고 영상을 잠근다. 따라서:
  //  1) 클라이언트 잠금 마킹(reportedLock) → 배너/저장 차단 즉시 반영
  //  2) 라벨 스토어 reset → 캔버스/객체 목록의 스테일 라벨 즉시 제거 + undo/redo 스택 초기화
  //     (잠금 상태에서 스테일 라벨을 편집/되돌리기 시도하는 경로 자체를 차단)
  //  3) 해당 프레임 범위의 LABEL 캐시만 무효화 → 서버가 비운 라벨로 재조회되어 캐시-화면 정합 유지.
  //     useLabels 는 LABEL_KEYS.byFrame(srcSn, 0) 으로 키잉되므로 그 prefix 인 byVideo(srcSn)로
  //     범위를 축소해 무관한 영상/프레임 캐시까지 일괄 재조회하던 LABEL_KEYS.all 무효화를 피한다.
  const handleDeidentReportSuccess = () => {
    setReportedLock(true);
    reset();
    if (data?.srcSn !== undefined) {
      queryClient.invalidateQueries({ queryKey: LABEL_KEYS.byVideo(data.srcSn) });
    } else {
      queryClient.invalidateQueries({ queryKey: LABEL_KEYS.all });
    }
  };

  // 저장 (PUT + commit) — 단축키와 헤더 버튼 공유.
  // useUpdateLabels 훅이 PUT 성공 시 LABEL/VIDEO/ASSIGNMENT/REVIEW_KEYS 를 일괄 invalidate 하여
  // 프레임 전환·작업 목록 진행률이 최신 상태로 갱신되도록 한다.
  const { mutateAsync: updateInternalLabels, isPending: savingInternal } = useUpdateLabels(
    currentFrame?.srcSn,
  );
  // R16 — 포털 저장은 원본 미수정, 본인 작업분을 LS_PORTAL_USER_LABEL 에 별도 적재.
  const { mutateAsync: savePortalLabels, isPending: savingPortal } = useSavePortalLabels(
    currentFrame?.srcSn,
    data?.videoId,
  );
  const updateLabels = portalMode ? savePortalLabels : updateInternalLabels;
  const saving = portalMode ? savingPortal : savingInternal;
  const handleSave = async () => {
    if (!currentFrame) return;
    // 중복 제출 차단(FE 방어) — 저장 in-flight 중 Ctrl+S 연타/버튼 재클릭 시 라벨 PUT 이
    // 중복 발화하지 않도록 saving(isPending) 을 선두에서 가드한다.
    if (saving) return;
    if (isLocked) {
      pushToast({
        variant: 'error',
        message: '비식별 재처리 중인 영상은 저장할 수 없습니다.',
      });
      return;
    }
    try {
      await updateLabels(labels);
      clearDirty();
      // 저장은 작업본 임시저장(LS_DATA_LBL upsert)만 수행 — 버전/히스토리 스냅샷은 검수 승인
      // 시점에 BE 가 생성한다(CLAUDE.md 2계층, SFR-08). 따라서 '버전 기록됨' 등 사실과 다른
      // 문구를 쓰지 않고 양쪽 채널 모두 '저장됨' 으로 통일한다.
      pushToast({ variant: 'success', message: '저장됨' });
    } catch (e) {
      pushToast({
        variant: 'error',
        message: e instanceof Error ? e.message : '저장 실패',
      });
    }
  };

  // "이 저장 되돌리기" — 카드 버튼 → 확인모달 오픈. 실제 역적용은 confirmRevert.
  const handleRevertRequest = useCallback((item: LabelHistoryItem) => {
    setRevertTarget(item);
  }, []);

  // 확인모달 승인 시 저장 이벤트를 현재 작업본에 역적용(즉시 DB 저장 아님 — dirty 로 저장 유도).
  const confirmRevert = useCallback(() => {
    const item = revertTarget;
    setRevertTarget(null);
    if (!item || !currentFrame) return;
    if (isLocked) {
      pushToast({ variant: 'error', message: '비식별 재처리 중인 영상은 되돌릴 수 없습니다.' });
      return;
    }
    const { reverted, skipped } = revertSaveEvent(item.changes, currentFrame.frameNo, snapshotToLabel);
    if (reverted === 0) {
      pushToast({ variant: 'warning', message: '되돌릴 항목이 현재 작업본에 없습니다.' });
      return;
    }
    if (skipped > 0) {
      pushToast({
        variant: 'warning',
        message: '일부 항목은 현재 작업본에 없어 되돌리지 못했습니다.',
      });
    }
    pushToast({ variant: 'success', message: '작업본에 되돌렸습니다. 저장해야 확정됩니다.' });
  }, [revertTarget, currentFrame, isLocked, revertSaveEvent, pushToast]);

  // Phase 4 — AI Tool 수동 트리거. 포털은 오토라벨 미제공(ADR-013 — 버튼 자체 미노출).
  // 검출 결과는 BE 미저장(Phase 3 전환) → 재조회가 아니라 작업본에 병합한다. mock 응답은 자동적용 차단.
  const { isAutolabeling, autolabel } = useAutolabel(currentFrame?.srcSn);
  // Phase 4 — AI Tool 팝업(형태 + 라벨 + 일반/트랙). 버튼 클릭 시 팝업을 열고, 확정 시 실행.
  const [autolabelModalOpen, setAutolabelModalOpen] = useState(false);
  // "즉시 그리기" 토글 — AI 분할 클릭마다 미리보기 즉시 그리기. 기본 OFF(false).
  // AiToolModal 에서 토글하고 CanvasShell→OverlayLayer 의 immediateSegment 로 배선된다.
  const [immediateDraw, setImmediateDraw] = useState(false);
  // Phase 2 [FE] — AI 정밀도 프리필. 시스템 설정값을 슬라이더 기본값으로 사용(실패/로딩 시 undefined →
  // 컴포넌트 코드 상수 폴백). 인식 민감도는 정수%(0~80) → /100(0~1) 변환, 경계 세밀함은 그대로.
  const { data: sysConfigs } = useConfigs();
  const defaultConfThreshold =
    sysConfigs?.YOLO_CONF_THRESHOLD != null ? sysConfigs.YOLO_CONF_THRESHOLD / 100 : undefined;
  const defaultSimplifyTolerance = sysConfigs?.POLYGON_SIMPLIFY_TOLERANCE;
  // AI 분할 경계 세밀함 조절값 — undefined=미조절(프리필만 표시, 요청 미포함). 조절 시 숫자로 채워져
  // CanvasShell → 분할 요청 payload 에 주입된다(무회귀).
  const [segmentTolerance, setSegmentTolerance] = useState<number | undefined>(undefined);
  // R12 — 트랙 모드 선택 시 팝업의 형태·라벨을 state 로 유지한다. 단 모달 형태는 더 이상
  // BBOX/POLYGON 객체의 추적 출력 형태를 강제하지 않는다(확정 사양). 실제 출력 형태는
  // ObjectAttributePanel 이 `shapeToDetectType(target.shape) ?? track.shape` 로 결정 —
  // 즉 선택 객체 형태가 우선이고, trackShape 는 예외형태(MASK/KEYPOINT) 폴백 + 라벨 힌트 용도로만 유지.
  const [trackShape, setTrackShape] = useState<DetectShapeType | undefined>(undefined);
  const [trackLabel, setTrackLabel] = useState<string | undefined>(undefined);
  const handleAutolabel = () => {
    if (!currentFrame) return;
    if (isLocked) {
      pushToast({ variant: 'error', message: '비식별 재처리 중인 영상은 AI 도구를 사용할 수 없습니다.' });
      return;
    }
    setAutolabelModalOpen(true);
  };

  // 후속 프레임 SRC_SN — 추적 대상(현재 프레임 이후 siblings). 트랙 실행 가능 여부 판정.
  const nextSrcSns = useMemo(
    () => frames.slice(frameIdx + 1).map((f) => f.srcSn),
    [frames, frameIdx],
  );

  // R12 — 추적 성공분(tracked)을 srcSn 별로 분리해 반영한다(사일런트 데이터 유실 수정).
  //  - tracked[].srcSn 은 현재가 아닌 후속(미래) 프레임 값이라, 과거 필터(=== data.srcSn)는 항상
  //    빈 배열이 되어 병합 0건이었다(성공 토스트만 뜨는 사일런트 유실). 이를 아래로 교체한다:
  //    · 현재 프레임(data.srcSn) 해당분 → 즉시 작업본 병합(기존 라벨 보존 + 중복 스킵).
  //    · 다른(미래) 프레임 해당분 → 보류 캐시(pendingTracks)에 stash → 해당 프레임 진입 시 drain 병합.
  const handleTracked = useCallback(
    (tracked: Sam2TrackedItem[], partial: boolean) => {
      if (!currentFrame) return;
      const curSrcSn = data?.srcSn;
      const forCurrent = tracked.filter((t) => t.srcSn === curSrcSn);
      const forFuture = tracked.filter((t) => t.srcSn !== curSrcSn);

      let applied = 0;
      if (forCurrent.length > 0) {
        applied += mergeAutoLabels(
          forCurrent.map((t) => trackedItemToLabel(t, currentFrame.frameNo)),
        );
      }
      if (forFuture.length > 0) {
        // srcSn 별로 그룹화 — 각 미래 프레임 frameNo 로 Label 변환 후 보류에 stash.
        const bySrcSn: Record<number, Label[]> = {};
        for (const t of forFuture) {
          const frameNo = frames.find((f) => f.srcSn === t.srcSn)?.frameNo ?? 0;
          (bySrcSn[t.srcSn] ??= []).push(trackedItemToLabel(t, frameNo));
        }
        stashPendingTracks(bySrcSn);
        applied += forFuture.length;
      }

      if (partial) {
        const total = nextSrcSns.length || tracked.length;
        pushToast({
          variant: 'warning',
          message: `${applied}/${total} 프레임만 추적됨 (일부 실패)`,
        });
      } else {
        pushToast({ variant: 'success', message: `AI 추적 완료 (${applied}프레임)` });
      }
    },
    [
      currentFrame,
      data?.srcSn,
      frames,
      nextSrcSns,
      mergeAutoLabels,
      stashPendingTracks,
      pushToast,
    ],
  );

  // AI Tool 확정 → 일반(단일 프레임 검출/분할) 또는 트랙(후속 프레임 추적) 실행.
  const runAiTool = async (
    shape: DetectShapeType,
    classIds: string[],
    mode: AiToolMode,
    opts?: AiToolOpts,
  ) => {
    setAutolabelModalOpen(false);
    if (!currentFrame) return;
    if (mode === 'track') {
      // 트랙 모드 — TRACK 도구 활성화 + 팝업의 형태/라벨을 기억(R12 shape 배선). 실제 전파는
      // 선택 객체 기준 Sam2TrackTool(속성 패널)에서 실행한다. 선택 객체가 없으면 안내한다.
      setActiveTool(ToolType.TRACK);
      // 출력 형태는 속성 패널(ObjectAttributePanel)에서 선택 객체 형태를 우선 적용한다
      // (`shapeToDetectType(target.shape) ?? track.shape`). 여기서 기억하는 모달 형태는
      // 예외형태(MASK/KEYPOINT) 폴백 + 라벨 힌트로만 사용되며 BBOX/POLYGON 출력을 바꾸지 않는다.
      setTrackShape(shape);
      // 팝업에서 단일 라벨을 골랐으면 그 표시명을 track 라벨로 우선 사용(없으면 캔버스 객체 클래스).
      const pickedLabel =
        classIds.length > 0
          ? YOLO_CLASSES.find((c) => c.id === classIds[0])?.label
          : undefined;
      setTrackLabel(pickedLabel);
      pushToast({
        variant: 'info',
        message: '추적할 객체를 선택한 뒤 속성 패널에서 자동추적을 실행하세요.',
      });
      return;
    }
    try {
      // opts 는 사용자가 슬라이더를 조절한 값만 담긴다(미조절이면 undefined → BE 기본값, 무회귀).
      const res = await autolabel(classIds, shape, opts);
      if (!res) return;
      // 내부 mock(모델 미로드) 시 BE 가 ApiResponse.message 를 세팅 → 경고 토스트로 자동적용 차단.
      if (res.message) {
        pushToast({ variant: 'warning', message: res.message });
        return;
      }
      // 미저장 — 재조회(invalidate)/PUT 없이 검출 결과를 작업본에 병합(기존 라벨 보존 + 중복 스킵).
      const detected = (res.labels ?? []).map((item) =>
        autolabelItemToLabel(item, currentFrame.frameNo),
      );
      const added = mergeAutoLabels(detected);
      const kind = shape === 'POLYGON' ? 'AI 분할' : 'AI 탐지';
      pushToast({ variant: 'success', message: `${kind} ${added}건 적용됨` });
    } catch (e) {
      pushToast({
        variant: 'error',
        message: e instanceof Error ? e.message : 'AI 탐지 실패',
      });
    }
  };

  // Phase 4 — 트랙 번호 변경(rename) 영속. 미사용 번호로의 병합=rename 이므로 mergeTracks 재사용.
  // ObjectClassTree 가 store(trackId) 를 낙관적 갱신하고, 여기서 BE 재보간까지 반영 후 재조회한다.
  const handleRenameTrack = async (fromTrackId: string, toTrackId: string) => {
    // Phase 10(축소) — 포털은 트랙 데이터모델 부재(프레임별 단건)라 rename/머지 미제공.
    // 내부 전용 mergeTracks(/v1/videos/{rawSn}/tracks/merge)는 PORTAL 채널 403 이므로 조기 return.
    // 버튼 숨김(ObjectClassTree portalMode)과 함께 이중 안전 가드.
    if (portalMode) return;
    const rawSn = data?.videoId;
    if (rawSn === undefined) return;
    if (isLocked) {
      pushToast({ variant: 'error', message: '비식별 재처리 중인 영상은 트랙을 변경할 수 없습니다.' });
      return;
    }
    try {
      await mergeTracks(rawSn, fromTrackId, toTrackId);
      queryClient.invalidateQueries({ queryKey: LABEL_KEYS.byVideo(rawSn) });
      pushToast({ variant: 'success', message: `트랙 번호 변경됨 (#${fromTrackId} → #${toTrackId})` });
    } catch (e) {
      pushToast({
        variant: 'error',
        message: e instanceof Error ? e.message : '트랙 번호 변경 실패 (겹치는 프레임일 수 있습니다)',
      });
    }
  };

  // R4 — 트랙 삭제(현재 프레임 이후 궤적). fromFrameNo 는 현재 보고 있는 프레임 번호.
  const handleDeleteTrack = async (trackId: string, fromFrameNo: number) => {
    if (portalMode) return;
    const rawSn = data?.videoId;
    if (rawSn === undefined) return;
    if (isLocked) {
      pushToast({ variant: 'error', message: '비식별 재처리 중인 영상은 트랙을 편집할 수 없습니다.' });
      return;
    }
    try {
      const res = await deleteTrack(rawSn, trackId, fromFrameNo);
      queryClient.invalidateQueries({ queryKey: LABEL_KEYS.byVideo(rawSn) });
      pushToast({
        variant: 'success',
        message: `트랙 삭제됨 (T:${trackId}, ${res.deletedCount}건)`,
      });
    } catch (e) {
      pushToast({
        variant: 'error',
        message: e instanceof Error ? e.message : '트랙 삭제 실패',
      });
    }
  };

  // R5 — 트랙 분할(현재 프레임 기준). atFrameNo 는 현재 보고 있는 프레임 번호.
  const handleSplitTrack = async (trackId: string, atFrameNo: number) => {
    if (portalMode) return;
    const rawSn = data?.videoId;
    if (rawSn === undefined) return;
    if (isLocked) {
      pushToast({ variant: 'error', message: '비식별 재처리 중인 영상은 트랙을 편집할 수 없습니다.' });
      return;
    }
    try {
      const res = await splitTrack(rawSn, trackId, atFrameNo);
      queryClient.invalidateQueries({ queryKey: LABEL_KEYS.byVideo(rawSn) });
      pushToast({
        variant: 'success',
        message: `트랙 분할됨 (T:${trackId} → T:${res.newTrackId}, ${res.movedCount}건)`,
      });
    } catch (e) {
      pushToast({
        variant: 'error',
        message: e instanceof Error ? e.message : '트랙 분할 실패',
      });
    }
  };

  // dirty 가드 — X(닫기) 클릭 시 미저장 변경이 있으면 확인 다이얼로그.
  // 권한/role 가드 redirect 경로(잘못된 ID / 라벨 조회 실패)에는 적용하지 않음 — 보안상 즉시 차단 유지.
  const [closeConfirmOpen, setCloseConfirmOpen] = useState(false);
  const [closing, setClosing] = useState(false);
  const handleClose = () => {
    if (dirtyCount > 0) {
      setCloseConfirmOpen(true);
    } else {
      navigate(-1);
    }
  };
  const handleConfirmSaveAndClose = async () => {
    if (!currentFrame) {
      setCloseConfirmOpen(false);
      navigate(-1);
      return;
    }
    setClosing(true);
    try {
      await updateLabels(labels);
      clearDirty();
      setCloseConfirmOpen(false);
      navigate(-1);
    } catch (e) {
      pushToast({
        variant: 'error',
        message: e instanceof Error ? e.message : '저장 실패',
      });
    } finally {
      setClosing(false);
    }
  };
  const handleDiscardAndClose = () => {
    setCloseConfirmOpen(false);
    navigate(-1);
  };
  const handleStayOnPage = () => {
    setCloseConfirmOpen(false);
  };

  // 브라우저 탭/창 닫기 시 dirty 경고 (브라우저 native 다이얼로그)
  useEffect(() => {
    if (dirtyCount === 0) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      // 일부 브라우저는 returnValue 설정 필요 — 메시지는 브라우저가 결정
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [dirtyCount]);

  // 폴리곤 편집(F/Q 단축키) 명령 핸들 — CanvasShell 이 OverlayLayer 의 imperative handle 을 중계.
  // 편집 state 는 OverlayLayer 내부 캡슐화를 유지하고, 상위는 이 ref 로 마우스와 동일 로직을 호출한다.
  const canvasHandleRef = useRef<OverlayLayerHandle | null>(null);

  useLabelingShortcuts(
    {
      // W/S — 첫/끝 프레임 (미저장 가드 경유 — requestJumpTo).
      onFirstFrame: () => requestJumpTo(0),
      onLastFrame: () => requestJumpTo(frames.length - 1),
      onPrevFrame: () => requestJumpTo(Math.max(0, frameIdx - 1)),
      onNextFrame: () => requestJumpTo(Math.min(frames.length - 1, frameIdx + 1)),
      onSave: handleSave,
      // ? — 단축키 치트시트 토글 (열림 상태는 페이지 state, 훅엔 콜백만 주입).
      onToggleCheatSheet: () => setCheatSheetOpen((v) => !v),
      // T — 선택된 라벨의 표시/숨김 토글 (세션 상태, 캔버스에서 렌더 skip).
      onToggleVisibility: () => {
        const id = useLabelStore.getState().selectedLabelId;
        if (id) toggleLabelVisibility(id);
      },
      // F/Q — 폴리곤 점 추가 / 완성. OverlayLayer 의 imperative handle 로 마우스와 동일 로직 호출.
      // 폴리곤 도구가 아니거나 진행 중 점이 부족하면 핸들 내부에서 no-op 처리한다.
      onPolygonAddPoint: () => canvasHandleRef.current?.addPointAtPointer(),
      onPolygonComplete: () => canvasHandleRef.current?.completePolygon(),
      // Ctrl+C(선택)/Ctrl+Shift+C(전체) — 라벨 복사. 빈 선택/프레임이면 no-op 토스트.
      onCopyLabels: ({ onlySelected }) => {
        const n = copyLabels({ onlySelected, sourceRawSn: data?.videoId ?? null });
        pushToast(
          n === 0
            ? { variant: 'warning', message: '복사할 라벨이 없습니다.' }
            : { variant: 'success', message: `라벨 ${n}건 복사됨` },
        );
      },
      // Ctrl+V/Ctrl+Shift+V — 현재 프레임에 붙여넣기. 잠금 영상은 차단.
      onPasteLabels: () => {
        if (isLocked) {
          pushToast({ variant: 'error', message: '비식별 재처리 중인 영상은 붙여넣을 수 없습니다.' });
          return;
        }
        if (!currentFrame) return;
        const n = pasteLabels({
          frameNo: currentFrame.frameNo,
          sourceRawSn: data?.videoId ?? null,
          // 실측 네이티브 dims 로 경계 clamp — 미확정(이미지 미로드) 시 undefined → 상한 미적용(하한 0 유지).
          imageWidth: frameNaturalSize?.width,
          imageHeight: frameNaturalSize?.height,
        });
        pushToast(
          n === 0
            ? { variant: 'warning', message: '붙여넣을 라벨이 없습니다.' }
            : { variant: 'success', message: `라벨 ${n}건 붙여넣음` },
        );
      },
    },
    // ADR-013 — 포털 모드에서는 오토라벨/키포인트 단축키 게이팅(툴바 숨김과 정합).
    // 모달(치트시트/프레임가드/닫기확인) 열림 중에는 단축키 억제 — 배경 프레임 이동·삭제 방지.
    {
      portalMode,
      suspended: navGuardTarget !== null || cheatSheetOpen || closeConfirmOpen,
    },
  );

  const [canvasRef, canvasSize] = useContainerSize<HTMLDivElement>();

  // KEYPOINT 순차 배치 진행 인덱스(0~16) — OverlayLayer→CanvasShell 이 보고.
  // 가이드는 좌측 라벨 패널(LabelSidebar) 내부에서 렌더하므로 캔버스와 패널의 공통 부모인
  // 이 페이지로 state 를 리프팅한다. 미진행/완료 시 null → 가이드 미표시.
  const [keypointPlacingIndex, setKeypointPlacingIndex] = useState<number | null>(null);

  // 잘못된 ID — 풀스크린 다크 에러
  if (Number.isNaN(numericId)) {
    return (
      <div
        className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white"
        style={{ zIndex: 50 }}
        data-testid="labeling-page"
      >
        <div className="text-center">
          <p className="text-lg font-semibold mb-2">잘못된 프레임 ID</p>
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="px-4 py-2 bg-primary-600 rounded-lg text-sm hover:bg-primary-500 transition-colors"
          >
            뒤로 가기
          </button>
        </div>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div
        className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white"
        style={{ zIndex: 50 }}
        data-testid="labeling-page"
      >
        <div className="flex flex-col items-center gap-3">
          <Spinner label="라벨 로딩" />
          <p className="text-sm text-gray-300">라벨 로딩 중...</p>
        </div>
      </div>
    );
  }

  // R17 이슈5 — 포털 모드에서 라벨 로드 403(미승인/미노출 영상) 시 graceful 차단 화면.
  // 빈 캔버스 노출(데이터 없는 UI) 대신 명확한 안내 + 뒤로 가기. (FORBIDDEN 만 별도 처리,
  // 그 외 에러는 기존 '라벨 조회 실패' 분기 유지.)
  const isPortalForbidden =
    portalMode &&
    !!error &&
    ((error as { status?: number }).status === 403 ||
      (error as { errorCode?: string }).errorCode === 'FORBIDDEN');
  if (isPortalForbidden) {
    return (
      <div
        className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white"
        style={{ zIndex: 50 }}
        data-testid="portal-forbidden-screen"
      >
        <div className="text-center">
          <p className="text-lg font-semibold mb-2">접근할 수 없는 영상입니다</p>
          <p className="text-sm text-gray-400 mb-4">
            데이터마트에 노출되지 않은 영상이거나 접근 권한이 없습니다.
          </p>
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="px-4 py-2 bg-primary-600 rounded-lg text-sm hover:bg-primary-500 transition-colors"
          >
            뒤로 가기
          </button>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div
        className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white"
        style={{ zIndex: 50 }}
        data-testid="labeling-page"
      >
        <div className="text-center">
          <p className="text-lg font-semibold mb-2">라벨 조회 실패</p>
          <p className="text-sm text-gray-400 mb-4">{error.message}</p>
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="px-4 py-2 bg-primary-600 rounded-lg text-sm hover:bg-primary-500 transition-colors"
          >
            뒤로 가기
          </button>
        </div>
      </div>
    );
  }

  const objectCount = labels.length;
  const isDirty = dirtyCount > 0;
  // CCTV명/이벤트는 향후 useVideoDetail 연동 시 채워짐 — 현재는 srcSn 표시
  const cctvName = data ? `프레임 #${data.srcSn}` : undefined;

  return (
    <div
      className="fixed inset-0 bg-gray-900 flex flex-col overflow-hidden"
      style={{ zIndex: 50 }}
      data-testid="labeling-page"
    >
      <LabelHeader
        cctvName={cctvName}
        eventType={undefined}
        currentFrame={frameIdx}
        totalFrames={Math.max(frames.length, 1)}
        objectCount={objectCount}
        dirty={isDirty}
        videoId={data?.srcSn}
        showHistory={!portalMode}
        onSave={handleSave}
        saving={saving}
        saveDisabled={isLocked}
        frameImageType={data?.frameImageType}
        onClose={handleClose}
        onHistoryClick={
          data?.srcSn !== undefined ? () => setHistoryOpen((v) => !v) : undefined
        }
        historyOpen={historyOpen}
        onHelpClick={() => setCheatSheetOpen(true)}
        deidentReportButton={
          canReportDeident && data?.srcSn !== undefined ? (
            <DeidentReportButton
              srcSn={data.srcSn}
              disabled={isLocked || data.frameImageType === 'RAW'}
              onSuccess={handleDeidentReportSuccess}
            />
          ) : null
        }
        submitButton={
          isWorker && data ? (
            <div className="flex items-center gap-2">
              {canCancelSubmit && (
                <Button
                  variant="secondary"
                  onClick={() => cancelSubmitForReview(data.videoId ?? data.srcSn)}
                  disabled={cancelling || isLocked}
                  loading={cancelling}
                  aria-label="검수 제출 취소"
                  data-testid="cancel-submit-review-button"
                  title="검수 시작 전이라 제출을 취소하고 작업 상태로 되돌립니다."
                >
                  제출 취소
                </Button>
              )}
              <Button
                variant="primary"
                onClick={() => submitForReview(data.videoId ?? data.srcSn)}
                disabled={submitting || isLocked || submitBlockedByStatus}
                loading={submitting}
                aria-label={submitButtonLabel}
                data-testid="submit-review-button"
                title={
                  submitBlockedByStatus
                    ? submitStatusHint
                    : isResubmitOfApproved
                      ? '검수 완료된 영상을 재검수에 다시 제출합니다.'
                      : undefined
                }
              >
                {submitButtonLabel}
              </Button>
            </div>
          ) : null
        }
      />

      {/* 영상 잠금 상태 배너 — LOCKED_FOR_REDEIDENT 시 라벨 수정 불가 안내 */}
      {isLocked && (
        <div
          data-testid="deident-locked-banner"
          role="status"
          aria-live="polite"
          className="bg-amber-900/60 text-amber-100 px-4 py-2 text-sm border-b border-amber-700 shrink-0"
        >
          비식별 재처리 중인 영상입니다. 처리가 완료될 때까지 라벨 수정·저장이 제한됩니다.
        </div>
      )}

      {/* dirty 가드 — X 닫기 시 미저장 변경 확인.
          3-옵션 다이얼로그(저장 후 닫기 / 저장 없이 닫기 / 머무름) 이므로 ConfirmDialog 대신
          Modal 직접 사용. ESC/백드롭/X = 머무름 (handleStayOnPage). */}
      <Modal
        open={closeConfirmOpen}
        onClose={handleStayOnPage}
        title="저장 안 한 변경사항이 있습니다"
        description={`${dirtyCount}개 객체에 미저장 변경이 있습니다. 어떻게 하시겠습니까?`}
        size="sm"
        footer={
          <>
            <Button
              variant="outline"
              onClick={handleStayOnPage}
              disabled={closing}
              data-testid="label-close-cancel"
            >
              취소
            </Button>
            <Button
              variant="outline"
              onClick={handleDiscardAndClose}
              disabled={closing}
              data-testid="label-close-discard"
            >
              저장 없이 닫기
            </Button>
            <Button
              variant="primary"
              onClick={handleConfirmSaveAndClose}
              loading={closing}
              data-testid="label-close-save"
            >
              저장 후 닫기
            </Button>
          </>
        }
      />

      {/* R5 — 프레임 이동 미저장 가드(저장 후 이동 / 저장 안 함 / 취소). */}
      <FrameNavGuardModal
        open={navGuardTarget !== null}
        dirtyCount={dirtyCount}
        saving={navGuardSaving}
        onSaveAndMove={handleNavSaveAndMove}
        onDiscardAndMove={handleNavDiscardAndMove}
        onCancel={handleNavCancel}
      />

      {/* R4 — 단축키 치트시트(도움말). ?(shift+/) 또는 헤더 도움말 버튼으로 토글. */}
      <ShortcutCheatSheet open={cheatSheetOpen} onClose={() => setCheatSheetOpen(false)} />

      {/* Phase 4 — AI Tool 팝업(형태 + 라벨 + 일반/트랙). 확정 시 shape/classIds/mode 로 실행. */}
      <AiToolModal
        open={autolabelModalOpen}
        onClose={() => setAutolabelModalOpen(false)}
        onConfirm={runAiTool}
        canTrack={nextSrcSns.length > 0}
        immediateDraw={immediateDraw}
        onImmediateDrawChange={setImmediateDraw}
        defaultConfThreshold={defaultConfThreshold}
        defaultSimplifyTolerance={defaultSimplifyTolerance}
      />

      {/* 본문 — 좌측 도구바 + 라벨 사이드바 + 캔버스 + 우측 패널 */}
      <div className="flex flex-1 overflow-hidden">
        <DarkToolbar
          onSave={handleSave}
          portalMode={portalMode}
          onAutolabel={handleAutolabel}
          isAutolabeling={isAutolabeling}
        />
        <LabelSidebar keypointPlacingIndex={keypointPlacingIndex} />

        {/* 캔버스 영역 — flex로 자동 채움 */}
        <div
          ref={canvasRef}
          className="flex-1 relative overflow-hidden flex items-center justify-center bg-gray-900"
        >
          {currentFrame ? (
            <Suspense
              fallback={
                <div className="flex items-center justify-center text-gray-400">
                  <Spinner label="캔버스 로딩" />
                </div>
              }
            >
              <CanvasShell
                ref={canvasHandleRef}
                frame={currentFrame}
                width={canvasSize.width || 1280}
                height={canvasSize.height || 720}
                labels={labels}
                readOnly={isLocked}
                onLabelAdd={(l) => addLabel({ ...l, frameNo: currentFrame.frameNo })}
                onKeypointPlacingChange={setKeypointPlacingIndex}
                onImageSize={handleImageSize}
                portalMode={portalMode}
                immediateSegment={immediateDraw}
                segmentSimplifyTolerance={segmentTolerance}
              />
            </Suspense>
          ) : (
            <div className="text-gray-400 text-sm">프레임 없음</div>
          )}
        </div>

        {/* 우측 패널 — 탭(객체 / 메타 / 이슈). 메타·이슈 탭은 INTERNAL 채널만 노출. */}
        <div className="w-72 flex flex-col bg-gray-800 border-l border-gray-700 overflow-hidden shrink-0">
          {hasTabs && (
            <div
              className="flex shrink-0 border-b border-gray-700"
              role="tablist"
              aria-label="우측 패널 탭"
            >
              <button
                type="button"
                role="tab"
                id="right-tab-objects"
                aria-selected={rightTab === 'objects'}
                aria-controls="right-panel-objects"
                data-testid="right-tab-objects"
                onClick={() => setRightTab('objects')}
                className={
                  rightTab === 'objects'
                    ? 'flex-1 px-3 py-2 text-xs font-semibold text-white border-b-2 border-primary-500'
                    : 'flex-1 px-3 py-2 text-xs font-semibold text-gray-400 hover:text-gray-200'
                }
              >
                객체
              </button>
              {showMeta && (
                <button
                  type="button"
                  role="tab"
                  id="right-tab-meta"
                  aria-selected={rightTab === 'meta'}
                  aria-controls="right-panel-meta"
                  data-testid="right-tab-meta"
                  onClick={() => setRightTab('meta')}
                  className={
                    rightTab === 'meta'
                      ? 'flex-1 px-3 py-2 text-xs font-semibold text-white border-b-2 border-primary-500'
                      : 'flex-1 px-3 py-2 text-xs font-semibold text-gray-400 hover:text-gray-200'
                  }
                >
                  메타
                </button>
              )}
              {showIssues && (
                <button
                  type="button"
                  role="tab"
                  id="right-tab-issues"
                  aria-selected={rightTab === 'issues'}
                  aria-controls="right-panel-issues"
                  data-testid="right-tab-issues"
                  onClick={() => setRightTab('issues')}
                  className={
                    rightTab === 'issues'
                      ? 'flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-semibold text-white border-b-2 border-primary-500'
                      : 'flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-semibold text-gray-400 hover:text-gray-200'
                  }
                >
                  이슈
                  {unresolvedInquiries > 0 && (
                    <span
                      data-testid="issue-tab-badge"
                      className="inline-flex min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold text-white"
                      aria-label={`미해소 문의 ${unresolvedInquiries}건`}
                    >
                      {unresolvedInquiries}
                    </span>
                  )}
                </button>
              )}
            </div>
          )}

          {showIssues && rightTab === 'issues' && issueRawSn !== undefined ? (
            <div
              className="flex-1 overflow-y-auto"
              data-testid="label-issue-panel"
              role="tabpanel"
              id="right-panel-issues"
              aria-labelledby="right-tab-issues"
            >
              <IssueThreadPanel rawSn={issueRawSn} mode="worker" dark />
            </div>
          ) : showMeta && rightTab === 'meta' ? (
            <div
              className="flex-1 flex flex-col overflow-y-auto"
              data-testid="label-meta-panel"
              role="tabpanel"
              id="right-panel-meta"
              aria-labelledby="right-tab-meta"
            >
              {/* 프레임 설명(NIA image.description) — 작업자 수기 입력. */}
              <FrameDescriptionPanel srcSn={data?.srcSn} />
              {/* VLM/시계열 메타는 외부 시스템 책임(ADR-013) — 내부 채널만 렌더. */}
              <TimeseriesSidePanel srcSn={data?.srcSn} />
              {/* event_annotation(외부 VQA/CoT) 수동입력·검토 — 영상(rawSn) 단위, 내부 채널만. */}
              <EventAnnotationPanel rawSn={data?.videoId} currentSrcSn={data?.srcSn} />
            </div>
          ) : (
            <div
              className="flex-1 flex flex-col overflow-hidden"
              {...(hasTabs
                ? {
                    role: 'tabpanel',
                    id: 'right-panel-objects',
                    'aria-labelledby': 'right-tab-objects',
                  }
                : {})}
            >
              <div className="flex-1 flex flex-col overflow-hidden border-b border-gray-700">
                <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide border-b border-gray-700 shrink-0">
                  객체 목록
                </div>
                <ObjectClassTree
                  labels={labels}
                  onRenameTrack={handleRenameTrack}
                  onDeleteTrack={handleDeleteTrack}
                  onSplitTrack={handleSplitTrack}
                  currentFrameNo={currentFrame?.frameNo}
                  portalMode={portalMode}
                />
              </div>
              <div className="flex-1 flex flex-col overflow-hidden">
                <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide border-b border-gray-700 shrink-0">
                  속성
                </div>
                <ObjectAttributePanel
                  labels={labels}
                  // 실측 네이티브 dims 로 좌표 clamp — 미확정 시 undefined → 상한 미적용(하드코딩 1920/1080 제거).
                  imageWidth={frameNaturalSize?.width}
                  imageHeight={frameNaturalSize?.height}
                  // Phase 9 (ADR-013 override) — 포털도 SAM2 자동추적 허용. 단 포털은 포털 전용
                  // /portal/frames/{id}/sam2-track 경로로 호출(persist 없이 좌표만) — portalMode 로 분기한다.
                  // 내부 /frames/{id}/sam2-track 은 PORTAL 채널 403 이므로 절대 호출하지 않는다.
                  track={{
                    srcSn: data?.srcSn,
                    nextSrcSns,
                    portalMode,
                    // R12 — 출력 형태는 선택 객체 형태가 우선(ObjectAttributePanel 의
                    // `shapeToDetectType(target.shape) ?? track.shape`). trackShape 는 예외형태
                    // (MASK/KEYPOINT) 폴백 + 라벨 힌트로만 쓰이며 BBOX/POLYGON 출력을 바꾸지 않는다.
                    shape: trackShape,
                    label: trackLabel,
                    // 미저장 병합 + 부분/전체 안내 토스트. tracked 는 후속 프레임 결과.
                    onTracked: handleTracked,
                  }}
                  // Phase 2 [FE] — AI 분할 도구 활성 시 경계 세밀함 조절. 프리필=시스템 설정값,
                  // 조절 시에만 segmentTolerance 로 올라가 분할 요청에 배선(미조절이면 BE 기본값).
                  segment={{
                    defaultTolerance: defaultSimplifyTolerance,
                    tolerance: segmentTolerance,
                    onToleranceChange: setSegmentTolerance,
                  }}
                />
              </div>
              {/* 이미지 조절(밝기/대비/투명도) — 포털 포함 노출. 세션 전용 상태(영속 안 함). */}
              <div className="shrink-0 border-t border-gray-700 p-2">
                <ImageAdjustPanel />
              </div>
            </div>
          )}
        </div>

        {/* 우측 슬라이드 — 히스토리 인라인 패널 (INTERNAL only). 본 영역은 기존 우측 패널 옆으로 펼침. */}
        {historyOpen && !portalMode && data?.srcSn !== undefined && (
          <div
            className="w-80 shrink-0 border-l border-gray-700 bg-gray-900 overflow-hidden"
            data-testid="inline-history-panel"
          >
            <HistoryPanel
              srcSn={data.srcSn}
              dark
              onClose={() => setHistoryOpen(false)}
              onRevert={handleRevertRequest}
            />
          </div>
        )}
      </div>

      {/* 저장 이벤트 되돌리기 확인 — 작업본 변경 전 확인(a11y 포커스/ESC 는 Modal 이 처리). */}
      <ConfirmDialog
        open={revertTarget !== null}
        title="이 저장으로 되돌리기"
        description="이 저장의 변경을 현재 작업본에 되돌립니다. 저장해야 확정됩니다."
        confirmLabel="되돌리기"
        cancelLabel="취소"
        onConfirm={confirmRevert}
        onCancel={() => setRevertTarget(null)}
      />

      {/* 하단 — 썸네일 strip + 슬라이더 */}
      <div className="shrink-0 flex flex-col border-t border-gray-700" style={{ height: 120 }}>
        <div style={{ height: 60 }}>
          <DarkFrameStrip
            frames={frames}
            currentIndex={frameIdx}
            onSelect={requestJumpTo}
            inquirySrcSns={inquirySrcSns}
            savedSrcSns={savedSrcSns}
            portalMode={portalMode}
          />
        </div>
        <div style={{ height: 60 }}>
          <DarkFrameSlider
            currentIndex={frameIdx}
            totalFrames={Math.max(frames.length, 1)}
            onSelect={requestJumpTo}
            dirtyGuard={dirtyCount > 0}
          />
        </div>
      </div>
    </div>
  );
}
