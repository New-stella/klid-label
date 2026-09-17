// SCR-LABEL-001 라벨링 캔버스 페이지 — 풀스크린 라이트 UI (앱 전역 톤과 동일).
//
// 레이아웃:
//   ┌─ LabelHeader (h-14, bg-white)
//   ├─ flex-1: [ToolBar w-52] [CanvasOptionBar + Canvas flex-1] [RightPanel w-72]
//   └─ Bottom (h-30): [FrameFilmstrip h-15] [DarkFrameSlider h-15]
//
// ★삭제·실행취소·다시실행·저장 + 프레임 이동 컨트롤은 **캔버스 상단 옵션바**(CanvasOptionBar)에
//   둔다 — 좌측 도구바·헤더에는 두지 않는다(SCREEN-005 확정). 양쪽에 두면 진입점이 갈린다.
//
// 라우트는 AppLayout 밖에서 직접 매칭되므로 LNB/GNB 없는 풀스크린.
// 보안: 사용자 입력 ID는 axios가 URL 인코딩. BE에서 IDOR/Mass Assignment 방어.

import {
  Suspense,
  lazy,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle } from 'lucide-react';
import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { Modal } from '@/components/common/Modal';
import { Spinner } from '@/components/common/Spinner';
import { LabelHeader } from '@/features/label/components/LabelHeader';
import { AiToolModal } from '@/features/label/components/AiToolModal';
import { BusyOverlay } from '@/features/label/components/BusyOverlay';
import { ToolBar } from '@/features/label/components/ToolBar';
import { CanvasOptionBar } from '@/features/label/components/CanvasOptionBar';
import { DeidentReportButton } from '@/features/label/components/DeidentReportButton';
import { KeypointGuide } from '@/features/label/components/KeypointGuide';
import { LabelPickerModal } from '@/features/label/components/LabelPickerModal';
import { ObjectClassTree } from '@/features/label/components/ObjectClassTree';
import {
  AutoTrackPanel,
  type AutoTrackApplyOutcome,
} from '@/features/label/components/AutoTrackPanel';
import {
  autolabelItemToLabel,
  deleteTrack,
  mergeTracks,
  normalizeLabel,
  snapshotToLabel,
  splitTrack,
  trackedItemToLabel,
  type AutolabelResponse,
  type DetectShapeType,
  type LabelHistoryItem,
  type Sam2TrackedItem,
} from '@/features/label/api';
// 회전각 타입만 가져온다 — `import type` 은 컴파일 시 완전히 지워지므로 아래 CanvasShell 의
// lazy 분리(konva 를 초기 번들에서 떼는 것)를 깨지 않는다.
import type { CanvasRotation } from '@/features/label/canvas/CanvasShell';
import type { AiToolMode, AiToolOpts } from '@/features/label/components/AiToolModal';
import { useDetectCandidates } from '@/features/label/hooks/useDetectCandidates';
import { DscdYn, LockSttsCd, TOOL_DISPLAY_NAME, ToolType } from '@/features/label/types';
import { ObjectAttributePanel } from '@/features/label/components/ObjectAttributePanel';
import { ImageAdjustPanel } from '@/features/label/components/ImageAdjustPanel';
import { AnnotationWindow } from '@/features/label/components/AnnotationWindow';
import { TimeseriesAnnotationSummaryCard } from '@/features/label/components/TimeseriesAnnotationSummaryCard';
import { reviewStatusLabel } from '@/features/label/components/annotationWording';
import { eventTypeNameOf } from '@/features/label/annotationSummary';
import { useAnnotationSummary } from '@/features/label/hooks/useAnnotationSummary';
import { useAnnotationWindow } from '@/features/label/hooks/useAnnotationWindow';
import { FrameDescriptionPanel } from '@/features/label/components/FrameDescriptionPanel';
import { EnvironmentMetaPanel } from '@/features/label/components/EnvironmentMetaPanel';
import { FramePrivacyMetaPanel } from '@/features/label/components/FramePrivacyMetaPanel';
import { VideoPrivacyMetaPanel } from '@/features/label/components/VideoPrivacyMetaPanel';
import { VideoTechnicalMetaPanel } from '@/features/label/components/VideoTechnicalMetaPanel';
import { ImportedMetaPanel } from '@/features/label/components/ImportedMetaPanel';
import { MetaHelpProvider, MetaHelpToggleButton } from '@/features/label/components/metaHelp';
import { useMetaHelpPreference } from '@/features/label/components/metaHelpPreference';
import { PortalWorkMetaTab } from '@/features/portal/work/components/PortalWorkMetaTab';
import {
  isPortalUnavailableError,
  portalWorkErrorMessage,
  PORTAL_WORK_ERROR_UNAVAILABLE,
} from '@/features/portal/work/workError';
import { IssueThreadPanel } from '@/features/review/components/IssueThreadPanel';
import { useIssueThreads } from '@/features/review/hooks/useIssueThreads';
import { FrameFilmstrip } from '@/features/label/components/FrameFilmstrip';
import { DarkFrameSlider } from '@/features/label/components/DarkFrameSlider';
import { FrameNavGuardModal } from '@/features/label/components/FrameNavGuardModal';
import { DiscardSaveConfirmModal } from '@/features/label/components/DiscardSaveConfirmModal';
import { DiscardSaveNotice } from '@/features/label/components/DiscardSaveNotice';
import {
  NO_DISCARD_CHANGE,
  hasDiscardChange,
  summarizeDiscardSave,
} from '@/features/label/discardSaveSummary';
import { ShortcutCheatSheet } from '@/features/label/components/ShortcutCheatSheet';

import { cn } from '@/lib/cn';
import { usePortalEditorFill } from '@/lib/portalEditorFill';
import { useImageBlob } from '@/features/label/hooks/useImageBlob';
import { useLabelingShortcuts } from '@/features/label/hooks/useLabelingShortcuts';
import { useToolLabelPicker } from '@/features/label/hooks/useToolLabelPicker';
import {
  useAutolabel,
  type AutolabelApplyContext,
} from '@/features/label/hooks/useAutolabel';
import { BUSY_KIND_NAME } from '@/features/label/busyPolicy';
import { busyRejectedMessage, useBusyTask } from '@/features/label/hooks/useBusyTask';
import { useAiWaitBudgetSync } from '@/features/label/hooks/useAiWaitBudgetSync';
import { useVideoDetail } from '@/features/video/hooks/useVideoDetail';
import { useLabels } from '@/features/label/hooks/useLabels';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';
import { resolveDeidentReportUnsupportedReason } from '@/features/label/utils/deidentReportEligibility';
import { resolveFrameDiscardUnsupportedReason } from '@/features/label/utils/frameDiscardEligibility';
import { resolveFrameImageErrorHint } from '@/features/label/utils/frameImageError';
import { resolveLabelIdByName } from '@/features/label/utils/labelMasterLookup';
import { useUpdateLabels } from '@/features/label/hooks/useUpdateLabels';
import { useSavePortalLabels } from '@/features/portal/hooks/useSavePortalLabels';
import {
  buildPortalDatamartLabelPath,
  buildPortalUploadLabelPath,
  type PortalLabelSource,
} from '@/features/portal/labelingEntry';
import { useUploadLabelSource } from '@/features/portal/uploads/hooks/useUploadLabelSource';
import { useSaveUploadLabels } from '@/features/portal/uploads/hooks/useSaveUploadLabels';
import type { FrameSummary, Label } from '@/features/label/types';
import type { OverlayLayerHandle } from '@/features/label/canvas/layers/OverlayLayer';
import { useSubmitReview, useCancelSubmitReview } from '@/features/review/hooks/useReviewActions';
import { useReview } from '@/features/review/hooks/useReview';
import { StartVersionModal } from '@/features/version/components/StartVersionModal';
import { useSaveVideoLabels } from '@/features/version/hooks/useSaveVideoLabels';
import { useVideoVersions } from '@/features/version/hooks/useVideoVersions';
import {
  captureFrameIntoDraft,
  discardChangesOf,
  frameOf,
  toLoadedDraft,
  toVideoSavePayload,
  unresolvedFrameCount,
  type LoadedVersionDraft,
} from '@/features/version/loadedVersionDraft';
import { ApiError } from '@/lib/api/errors';
import { extractBeMessage } from '@/lib/api/extractBeMessage';
import { Role } from '@/lib/api/types';
import { roleSatisfies } from '@/lib/authz';
import { LABEL_KEYS } from '@/lib/queryKeys';
import { useBeforeUnloadWarning, useUnsavedWorkFlag } from '@/lib/unsavedWork';
import { useAuthStore } from '@/stores/useAuthStore';
import {
  useIsEditBlocked,
  useLabelStore,
  isEditBlockedNow,
  isEditBlockedState,
  shouldResetView,
} from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';

// konva는 브라우저 전용 — lazy load로 초기 번들 분리
const CanvasShell = lazy(() =>
  import('@/features/label/canvas/CanvasShell').then((m) => ({ default: m.CanvasShell })),
);

// 캔버스 컨테이너에서 자동 측정해 ResponsiveCanvas로 전달
// ★콜백 ref 로 요소를 state 에 담는다 — 이 화면은 조회 중·오류 화면을 먼저 그리고 캔버스 영역은
//   그 뒤에야 생긴다. 객체 ref + 빈 의존성으로 첫 렌더에만 찾으면 그때는 요소가 없어 영영 붙지
//   않고, 크기가 0 으로 남아 캔버스가 기본값(1280×720)으로 그려진다(포털 좁은 슬롯에서 프레임이
//   잘려 보인 원인 — 2026-09-15 실측).
function useContainerSize<T extends HTMLElement>() {
  const [el, setEl] = useState<T | null>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });

  // useLayoutEffect — 첫 페인트 전 동기 측정으로 캔버스 마운트 가드(`size.width > 0`) 통과 보장
  useLayoutEffect(() => {
    if (!el) return;
    const update = () => {
      const rect = el.getBoundingClientRect();
      setSize({ width: Math.floor(rect.width), height: Math.floor(rect.height) });
    };
    const observer = new ResizeObserver(update);
    observer.observe(el);
    update();
    return () => observer.disconnect();
  }, [el]);

  return [setEl, size] as const;
}

export interface LabelingPageProps {
  /**
   * 포털 라벨링의 **자산 출처**. @design SCREEN-029
   *
   * 포털의 라벨링 화면은 하나뿐이고 두 출처를 이 한 화면이 연다. 갈리는 것은 화면이 아니라
   * **조회·이미지·저장 창구**이므로, 판정 결과를 여기로 받아 <b>데이터 계층으로만</b> 내린다 —
   * 화면 본문에 `source === 'upload'` 분기를 흩지 않는다.
   *
   * ⚠ 기본값은 `datamart` 다 — 내부 라우트(`/label/:id`)와 데이터마트 갈래는 한 글자도 바뀌지
   *   않는다. 표기가 없거나 아는 값이 아니면 데이터마트로 읽는 fail-closed 판정도 그대로다.
   */
  source?: PortalLabelSource;
}

/**
 * SCR-LABEL-001 라벨링 캔버스 페이지 (라이트 풀스크린).
 *
 * ★내부(`/label/:id`)와 포털(`/portal/label/:id`) 두 라우트가 **같은 컴포넌트를 재사용**한다.
 *   포털 갈래에서 가려지는 것(스켈레톤·선택 객체 AI 추적·트랙 편집·검수·버전관리·비식별 신고·
 *   프레임 폐기)과 갈리는 것(AI 보조 창구 경로·도구바 묶음·AI 탐지 팝업 실행 버튼)은 전부
 *   `portalMode` 단일 축이 판정한다 — <b>두 번째 게이팅 축을 만들지 말 것</b>.
 *   분기 축은 셋이다 — API 경로 / 노출 요소 / 이동 경로. AI 보조 창구 경로는 이 화면이 portalMode 를
 *   훅·부품에 넘기고, 경로 조립은 `lib/api/aiRoutes` 한 곳이 한다.
 * ⚠ [폐기] 구 서술 — *"포털 갈래에서 AI 보조가 가려진다"*. 2026-09-15 에 포털도 AI 탐지·AI 분할·
 *   AI 자동 추적을 쓰게 됐다(SCREEN-029).
 *
 * @design SCREEN-005, SCREEN-029, API-255, API-257, API-254, API-256, API-258
 */
export function LabelingPage({ source = 'datamart' }: LabelingPageProps = {}) {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const numericId = id ? Number(id) : NaN;

  // 채널/역할 가드
  const portalMode = useAuthStore((s) => s.claims?.channel === 'PORTAL');
  /*
    ★임베드에서는 편집기를 <b>흐름 안</b>에 세운다 — Host 가 우리 자리에 `contain: layout` 을
      걸어 두어 `fixed` 가 화면이 아니라 그 자리 안쪽을 덮고, 흐름 밖이라 그 자리가 제 최소
      높이로 주저앉는 되먹임이 생긴다(2026-09-17 실화면 실측 — 캔버스가 343 이었다).
      까닭·실험 근거는 `lib/portalEditorFill.ts` 가 갖는다.
    ⚠ 가르는 축은 사용자 채널이 아니라 <b>빌드 형상</b>이다 — 관제·독립 배포본은 문서 전체를
      우리가 가지므로 종전 `fixed` 그대로다.
  */
  const {
    embedded: editorEmbedded,
    ref: editorFillRef,
    height: editorFillHeight,
  } = usePortalEditorFill();
  const editorShellClass = editorEmbedded ? 'relative w-full' : 'fixed inset-0';
  const editorShellStyle: CSSProperties = editorEmbedded
    ? { height: editorFillHeight }
    : { zIndex: 50 };
  const role = useAuthStore((s) => s.claims?.role);
  const isWorker = role === Role.WORKER;
  const isReviewer = roleSatisfies(role, Role.REVIEWER);
  // INTERNAL 채널 + WORKER/REVIEWER 만 비식별 누락 신고 가능 (포털 회원은 미노출)
  const canReportDeident = !portalMode && (isWorker || isReviewer);
  const pushToast = useUiStore((s) => s.pushToast);

  /*
   * ★자산 출처는 **포털 채널에서만** 갈린다(fail-closed). 내부 채널에서 표기가 새어 들어와도
   *   업로드 창구를 부르지 않는다 — 그쪽에는 그 자산이 존재하지 않는다.
   *
   * ⚠ 업로드 출처에서 `:id` 는 프레임이 아니라 **자산**이다(진입 시 첫 프레임을 알 수 없다).
   *   현재 프레임은 주소의 `frame` 표기가 나르며 그 해석은 어댑터가 갖는다.
   */
  const uploadSource = portalMode && source === 'upload';
  const uploadLabelSource = useUploadLabelSource(uploadSource ? numericId : undefined);
  const datamartLabels = useLabels(
    !uploadSource && Number.isFinite(numericId) ? numericId : undefined,
    portalMode,
  );
  const { data, isLoading, error } = uploadSource ? uploadLabelSource : datamartLabels;
  // ⚠ 저장 충돌(409) 해소 재조회 전용이다 — 그 경로는 낙관적 동시성 토큰을 싣는 **내부 저장**
  //   에만 있어 업로드 갈래에서는 도달하지 않는다(그래서 데이터마트 조회의 refetch 를 쓴다).
  const refetchLabels = datamartLabels.refetch;
  const uploadNotice = uploadLabelSource.notice;

  // 라벨 마스터 — SAM2 Track 결과(라벨명만 옴)의 마스터 PK 역해석에 쓴다. 캔버스/속성 패널이
  // 이미 같은 쿼리를 구독하므로(staleTime 5분 공유 캐시) 추가 요청은 사실상 발생하지 않는다.
  const { data: labelMasters } = useLabelMasters();

  // 영상 속성(파생 여부·검수 상태) — 비식별 누락 신고 버튼을 <b>미리</b> 비활성화하기 위해서만 쓴다.
  // BE 는 파생영상·검수 승인 영상의 신고를 412 로 거부하는데,
  // 그 사실을 제출 후에야 알리면 사용자는 사유를 다 적고 나서 막힌다. 영상 상세 쿼리는 영상현황 화면과
  // 같은 캐시 키를 공유하므로 대개 추가 요청 없이 재사용된다(신고 가능한 내부 채널에서만 조회).
  // ⚠ 조회 조건이 canReportDeident 가 아니라 !portalMode 다 — 이 응답은 신고 버튼 비활성화뿐
  //   아니라 **헤더 이벤트 유형 배지(UI-055)** 의 값 출처이기도 하다. 신고 조건으로 좁히면
  //   신고 불가 사용자에게 이벤트 배지가 통째로 사라진다. 포털 채널은 /videos/{id} 접근 권한이
  //   없어 제외한다.
  const { data: videoDetail } = useVideoDetail(
    !portalMode && data?.videoId ? data.videoId : null,
  );
  // @design DFEAT-048 · @req R2 — 신고 불가 사유 판정은 <b>복제하지 않고</b> 단일 지점에 위임한다
  //   (파생영상 · 검수 승인 영상). 사유가 늘 때 화면마다 조건을 이어붙이면 한쪽만 갱신돼 어긋난다.
  const deidentReportUnsupportedReason = resolveDeidentReportUnsupportedReason(videoDetail);
  // P2b — 한번이라도 검수 완료된 영상에서는 폐기·복원 조작을 비활성으로 두고 사유를 안내한다.
  //   판정은 단일 지점(frameDiscardEligibility)에 위임한다 — 화면이 조건을 이어붙이면 어긋난다.
  //   ⚠ 회차 적용(확정 저장이 불러온 회차의 폐기 상태를 싣는 경로)은 이 판정과 무관하다 — 서버가
  //     예외로 허용하며, 여기서 가리는 것은 화면의 토글 조작뿐이다.
  const frameDiscardUnsupportedReason = resolveFrameDiscardUnsupportedReason(videoDetail);

  // BE 의 인증 보호된 프레임 이미지 API 를 axios 로 fetch → blob URL 발급.
  // <img>/Image() 직접 호출은 Bearer 토큰 누락으로 401. CSP 의 img-src blob: 허용 활용.
  // R16 — 포털 모드는 /portal/frames/{id}/image (내부 /frames/{id}/image 는 PORTAL 채널 403).
  // 이미지 로드 실패(error)를 반드시 받아 화면에 표시한다 — 구현상 실패하면 imageBlobUrl 이 null 로
  // 남아 캔버스가 <b>아무 안내 없이 백지</b>가 됐다(파생영상 404 실측). 원인 파악 불가 = 사용자·운영
  // 모두에게 최악의 실패 모드라, 실패 사실만이라도 캔버스 영역에 노출한다.
  const {
    url: imageBlobUrl,
    loading: imageLoading,
    error: imageError,
  } = useImageBlob(data?.srcSn, { portalMode, uploadSource });
  // 사유 힌트는 상태코드로만 만든다 — 서버 메시지(내부 경로 등)를 그대로 화면에 싣지 않는다(CWE-209).
  // 판정은 검수 캔버스와 **공유**한다(`resolveFrameImageErrorHint`) — 화면마다 조건을 복제하면
  // 상태코드가 늘거나 문구가 바뀔 때 한쪽만 갱신돼 같은 실패가 다르게 보인다.
  const frameImageErrorHint = resolveFrameImageErrorHint(imageError);

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
  // FrameFilmstrip 내부 FrameThumbnail 이 srcSn 별로 useImageBlob 을 호출해 자체 fetch.
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

  // ── 편집 차단(장시간 작업 진행 중) 단일 판정원 ────────────────────────────────
  // 캔버스·툴바·프레임 전환·단축키·패널이 전부 이 값 하나를 본다. 각 진입점이 조건을 다시
  // 세우면 판정원이 갈라져 한쪽만 갱신됐을 때 조용히 열린 구멍이 생긴다.
  const isEditBlocked = useIsEditBlocked(currentFrame?.srcSn);
  // 진행 오버레이 표시값 — 같은 판정원(isEditBlockedState)에서 파생시킨다. 별도 조건을 세우면
  // "차단됐는데 오버레이는 없는"(또는 그 반대) 어긋남이 생긴다. 셀렉터는 원시값만 반환해
  // 매 store 갱신마다 재렌더되지 않게 한다.
  const busyKind = useLabelStore((s) =>
    isEditBlockedState(s, currentFrame?.srcSn) ? (s.busy?.kind ?? null) : null,
  );
  const busyStartedAt = useLabelStore((s) =>
    isEditBlockedState(s, currentFrame?.srcSn) ? (s.busy?.startedAt ?? undefined) : undefined,
  );
  // 이 실행에 실제로 적용된 대기 상한 — 오버레이가 «최대 N초» 로 보여준다. 화면이 다시 계산하지
  // 않고 잠금을 건 주체가 기록한 값을 그대로 읽는다(두 값이 갈리면 안내가 거짓이 된다).
  const busyLimitMs = useLabelStore((s) =>
    isEditBlockedState(s, currentFrame?.srcSn) ? s.busy?.maxDurationMs : undefined,
  );
  const cancelBusy = useLabelStore((s) => s.cancelBusy);

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

  // @design SCREEN-029 — 포털 라벨링 화면(포털 채널이 이 컴포넌트를 재사용한다).
  // 프레임 이동 경로 — 이 화면은 내부(`/label/:id`)와 포털(`/portal/label/:id`) 두 라우트가
  // **같은 컴포넌트를 재사용**하므로 이동 경로도 채널을 따라가야 한다. 내부 경로로 고정하면
  // 포털 사용자는 프레임을 넘기는 순간 INTERNAL 채널 가드에 걸려 접근 거부 화면으로 튕기고,
  // 결과적으로 포털 라벨링이 첫 프레임 한 장으로 제한된다(실제 결함).
  // 조립은 이 한 곳에만 둔다 — 이동 지점이 늘어날 때 같은 하드코딩이 복제되지 않게 한다.
  // ★주소 조립은 `labelingEntry` 한 곳이 갖는다 — 화면이 문자열을 만들면 표기를 바꿀 때
  //   목록과 화면이 갈린다. 업로드 축은 `:id` 가 자산이라 **프레임 표기만** 바꾼다.
  const frameRoute = (srcSn: number) =>
    portalMode
      ? uploadSource
        ? buildPortalUploadLabelPath(numericId, srcSn)
        : buildPortalDatamartLabelPath(srcSn)
      : `/label/${srcSn}`;

  // 다른 프레임으로 실제 이동 — URL 전환 (useLabels 가 재조회).
  // replace=true: history stack 에 push 하지 않음 — X(닫기) 버튼이 뒤로가기 시
  // 이전 프레임이 아닌 진입 이전 경로(작업 목록)로 빠져나가도록 한다.
  const performJump = (idx: number) => {
    // 렌더 값이 낡았을 수 있으므로 실시간 store 값도 함께 본다(fail-closed).
    if (isEditBlockedNow(currentFrame?.srcSn)) return;
    const target = frames[idx];
    if (!target || !data) return;
    if (target.srcSn !== data.srcSn) {
      navigate(frameRoute(target.srcSn), { replace: true });
    }
  };

  // ── R4·R5 프레임 폐기·복원 ───────────────────────────────────────────────────
  // 폐기는 별도 엔드포인트가 없다 — 화면에서 전환하면 **미저장 변경**으로 남고 저장이 확정한다(D8).
  // 그래서 서버값(data.dscdYn)과 사용자의 전환(draft)을 분리해 들고, 화면은 둘을 합친 값을 본다.
  //
  // ⚠ 서버값을 되돌려 보내지 않는다 — BE 는 "필드가 없으면 현재 값 유지"로 처리하므로, 전환하지
  //   않은 저장에 값을 실으면 그 규약이 무너져 다른 사람의 폐기 결정을 조용히 덮어쓴다.
  const [discardDraft, setDiscardDraft] = useState<DscdYn | null>(null);
  const serverDscdYn = data?.dscdYn ?? null;
  // 화면 기준 폐기여부 — 축이 없는 응답(null)은 '사용 중'으로 그린다(모른다고 잠그지 않는다).
  const effectiveDscdYn: DscdYn = discardDraft ?? serverDscdYn ?? DscdYn.N;
  const isDiscarded = effectiveDscdYn === DscdYn.Y;
  const discardPending = discardDraft !== null;
  const handleToggleDiscard = () => {
    const next = isDiscarded ? DscdYn.N : DscdYn.Y;
    // 서버값과 같아지면 전환을 지운다 — 되돌린 것을 "변경"으로 세면 미저장 경고가 거짓이 된다.
    // ⚠ 서버값을 모르면(null) 지우지 않는다 — 지우면 필드가 빠져 복원이 서버에 도달하지 못한다.
    setDiscardDraft(serverDscdYn !== null && next === serverDscdYn ? null : next);
    // R6 — 불러온 세트가 대기 중이면 편집으로 기록한다. ⚠ 폐기 토글도 편집이다 — 본문이 그대로여도
    //   이 축만 바뀌면 edits 에 실려야 한다(안 실으면 화면에는 바뀐 것처럼 보이는데 저장되지 않는다).
    const srcSn = data?.srcSn;
    if (srcSn !== undefined) {
      setLoadedDraft((prev) =>
        prev ? captureFrameIntoDraft(prev, srcSn, useLabelStore.getState().labels, next) : prev,
      );
    }
  };
  // 프레임이 바뀌면 전환을 버린다 — 저장하지 않고 떠나면 되돌아간다는 사양 그대로다.
  useEffect(() => {
    setDiscardDraft(null);
  }, [data?.srcSn]);

  // R5 — 프레임 이동 미저장 가드. dirtyLabels.size>0 이면 확인 모달(저장 후 이동/저장 안 함/취소),
  // 아니면 즉시 이동. 슬라이더/썸네일/단축키 프레임 이동이 모두 이 함수를 경유한다.
  // navGuardTarget 에 대기 중인 이동 대상 인덱스를 보관한다(null = 가드 비활성).
  const [navGuardTarget, setNavGuardTarget] = useState<number | null>(null);
  const [navGuardSaving, setNavGuardSaving] = useState(false);
  const requestJumpTo = (idx: number) => {
    // 진행 중 프레임을 떠나면 도착 결과가 갈 곳을 잃는다 — 전환 자체를 막는다.
    if (isEditBlocked || isEditBlockedNow(currentFrame?.srcSn)) return;
    const target = frames[idx];
    if (!target || !data) return;
    // 같은 프레임(경계 클램프로 인한 no-op)은 가드 없이 무시.
    if (target.srcSn === data.srcSn) return;
    // R4·R5 — 폐기 전환도 미저장 변경이다. 라벨 변경만 세면 폐기 전환이 안내 없이 사라진다.
    //   R6 — 불러온 회차 세트도 미저장이다(저장하지 않고 떠나면 서버는 그대로다).
    if (dirtyCount > 0 || discardPending || loadedDraft !== null) {
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
      // 폐기된 저장이면 이동하지 않는다 — 저장되지 않은 채 프레임을 떠나면 작업이 소실된다.
      //   회차를 불러온 상태면 영상 전체 확정으로 갈린다(persistPendingWork) — 프레임 하나만
      //   저장하면 한 영상에 서로 다른 회차가 섞인 채 확정된다.
      if ((await persistPendingWork()) === 'rejected') {
        setNavGuardTarget(null);
        return;
      }
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
  // ⚠ **이동 가능 여부를 먼저 판정한다** — 모달이 열린 뒤 시작된 작업 때문에 performJump 가
  //   조용히 막히면, 파기만 실행돼 같은 프레임에 남은 채 미저장분만 사라진다.
  const handleNavDiscardAndMove = () => {
    if (navGuardTarget === null) return;
    const targetIdx = navGuardTarget;
    if (isEditBlocked || isEditBlockedNow(currentFrame?.srcSn)) {
      // 모달은 열어 둔다 — 작업이 끝난 뒤 그대로 다시 선택할 수 있어야 미저장분이 보존된다.
      pushToast({
        variant: 'warning',
        message: busyRejectedMessage(useLabelStore.getState().busy?.kind ?? null),
      });
      return;
    }
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
  //
  // ★ R6/API-195 — 불러온 회차 세트가 있으면 그것이 캔버스 내용의 진실원이다. 서버 작업본으로 덮으면
  //   프레임을 넘기는 순간 불러온 내용이 조용히 사라져(사용자는 여전히 "불러왔다"고 믿는데) 그 상태로
  //   저장하면 되돌리려던 프레임만 원래대로 확정된다 — 회차가 섞인 혼합 영상이다.
  const lastLoadedSrcSnRef = useRef<number | undefined>(undefined);
  // 떠나는 프레임의 폐기 전환값 — 프레임이 바뀌면 discardDraft 가 초기화되므로 미리 붙잡아 둔다.
  const leavingDscdYnRef = useRef<DscdYn | null>(null);
  useEffect(() => {
    leavingDscdYnRef.current = discardDraft;
  }, [discardDraft]);
  useEffect(() => {
    if (!data) return;
    const draft = loadedDraftRef.current;
    const leavingDscdYn = leavingDscdYnRef.current;
    const nextLabels = Array.isArray(data.labels) ? data.labels : [];
    const frameChanged = lastLoadedSrcSnRef.current !== data.srcSn;
    if (frameChanged) {
      const leaving = lastLoadedSrcSnRef.current;
      lastLoadedSrcSnRef.current = data.srcSn;
      if (draft) {
        // ① 떠나는 프레임의 캔버스 편집을 세트에 되쓴다(전환으로 편집이 유실되지 않게).
        if (leaving !== undefined) {
          setLoadedDraft((prev) =>
            prev
              ? captureFrameIntoDraft(prev, leaving, useLabelStore.getState().labels, leavingDscdYn)
              : prev,
          );
        }
        // ② 새 프레임은 <b>편집분이 있으면 그것을</b>, 없으면 회차 본문을 그린다(그 프레임이 세트에
        //    있을 때만 — 없으면 서버값). 편집분을 무시하면 프레임을 왕복할 때 편집이 사라진다.
        const edited = draft.editedBy[data.srcSn];
        const entry = frameOf(draft, data.srcSn);
        const source = edited?.items ?? entry?.items;
        setLabels(source ? source.map(normalizeLabel) : nextLabels);
        return;
      }
      setLabels(nextLabels);
      return;
    }
    // 같은 프레임 재조회 — 불러온 세트가 대기 중이면 서버 라벨로 덮지 않는다(위 근거).
    if (draft) return;
    // 미저장 병합/편집이 없을 때만 최신 서버 라벨로 동기화.
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

  // ── R6/D4 「시작 버전 선택」 ─────────────────────────────────────────────────
  // 헤더의 히스토리 진입을 폐지하고 이 모달로 옮겼다(**제거가 아니라 재배치**). 모달은 영상 산출
  // 버전 목록뿐 아니라 프레임 버전 이력(버전 간 diff · 작업본 diff · 롤백)까지 함께 품는다.
  //
  // ⚠ 자동 노출은 **화면 진입당 1회**다. 사용자가 닫은 뒤 다시 띄우지 않으며(작업 중 모달이 다시
  //   덮으면 편집을 방해한다), 재진입은 캔버스 상단 옵션바의 버튼이 담당한다 — 그 진입점이 없으면
  //   닫는 순간 네 기능이 그 세션 내내 도달 불가가 된다.
  const [startVersionOpen, setStartVersionOpen] = useState(false);
  const [startVersionAutoShown, setStartVersionAutoShown] = useState(false);
  // ★ 불러온 회차 세트(=저장 대기 중인 영상 전체 작업본). null 이면 평상시 프레임 단위 저장이다.
  //   불러오기(API-195)는 서버에 아무것도 쓰지 않으므로 확정 전까지 이 상태가 **화면에만** 존재한다.
  //   저장하지 않고 떠나면 이 상태가 사라지고 서버 작업본이 그대로 남는다(되돌릴 창).
  const [loadedDraft, setLoadedDraft] = useState<LoadedVersionDraft | null>(null);
  // 프레임 전환 시 "떠나는 프레임의 캔버스"를 세트에 되쓰기 위한 최신 참조(렌더 클로저 stale 방지).
  const loadedDraftRef = useRef<LoadedVersionDraft | null>(null);
  useEffect(() => {
    loadedDraftRef.current = loadedDraft;
  }, [loadedDraft]);
  const { mutateAsync: confirmVideoLabels, isPending: confirmingVideo } =
    useSaveVideoLabels(data?.videoId);
  // 포털은 버전관리 미제공(ADR-013) — 조회 자체를 걸지 않는다.
  const { data: videoVersions } = useVideoVersions(
    !portalMode ? data?.videoId : undefined,
  );
  // 자동 노출 조건 — 산출 버전이 **둘 이상**일 때만. 하나도 없으면 고를 것이 없고, 하나뿐이면
  // 고를 것이 하나뿐이라 어느 쪽도 띄우지 않는다(확정 사양).
  useEffect(() => {
    if (portalMode || startVersionAutoShown) return;
    if ((videoVersions?.length ?? 0) < 2) return;
    setStartVersionAutoShown(true);
    setStartVersionOpen(true);
  }, [portalMode, startVersionAutoShown, videoVersions]);
  // 영상이 바뀌면 자동 노출을 다시 허용하고, 불러온 세트도 버린다(다른 영상의 회차 내용이 잔존하면
  // 그 세트로 엉뚱한 영상을 확정하게 된다). 프레임 이동만으로는 둘 다 유지된다.
  useEffect(() => {
    setStartVersionAutoShown(false);
    setLoadedDraft(null);
  }, [data?.videoId]);
  // 저장 이벤트 되돌리기 확인 대상. null 이면 확인모달 닫힘.
  const [revertTarget, setRevertTarget] = useState<LabelHistoryItem | null>(null);
  /**
   * C-ISSUE-21 — 저장 충돌(409) 안내. 다른 사용자가 같은 프레임을 먼저 저장해 내 화면이 낡은 경우
   * BE 가 저장을 거부한다. 이때 <b>내 작업 내용을 말없이 버리지 않고</b> 사용자에게 선택을 준다:
   * 최신 라벨을 다시 불러오거나(내 미저장 변경은 사라짐 — 명시 동의), 일단 화면을 유지한다.
   */
  const [saveConflictMessage, setSaveConflictMessage] = useState<string | null>(null);

  // R4 — 단축키 치트시트(도움말) 모달 열림 상태. ?(shift+/) 단축키 또는 헤더 도움말 버튼으로 토글.
  const [cheatSheetOpen, setCheatSheetOpen] = useState(false);

  // 우측 패널 탭 — 객체 / 메타 / 이슈(검수자↔작업자 소통). 이슈 스레드는 INTERNAL 채널만.
  //   객체 = 객체 목록 + 속성 / 메타 = 프레임 설명 + 시계열 메타(VLM) / 이슈 = 이슈 스레드
  const [rightTab, setRightTab] = useState<'objects' | 'meta' | 'issues'>('objects');
  // 이슈는 영상 단위(rawSn) — videoId(LS_DATA_RAW.RAW_SN)만 사용.
  // srcSn(프레임 PK) 폴백 금지: 프레임 PK를 영상 ID 자리에 넣으면 잘못된 영상의 이슈 조회/404.
  // videoId 부재 시 이슈 탭 비노출.
  const issueRawSn = data?.videoId;
  // ★탭 존재 여부와 이용 가능 여부를 분리한다(사양: "영상 정보가 없으면 탭은 노출하되 이용 불가
  //   안내"). 구 코드는 한 플래그로 **탭 버튼 자체를 감춰** 사용자가 안내를 볼 방법이 없었다.
  //   포털은 ADR-013 상 이슈 소통 자체가 미제공이라 탭도 두지 않는다(별개 축).
  const showIssues = !portalMode;
  const issuesReady = showIssues && issueRawSn !== undefined;
  // 메타 탭 — ★<b>두 채널 모두</b> 연다. 포털에 미제공인 것은 외부 시계열 분석 서버로 나가는
  //   위탁 연동(호출·콜백) 축이지 그 결과물의 표시·수정이 아니다(ADR-013 v10). 구 주석이 「포털
  //   메타·어노테이션 창구가 0건이라 켜면 전량 403」이라 적었는데 그 창구들이 생겼다.
  //   ⚠ 탭은 같아도 <b>본문은 채널마다 다른 컴포넌트</b>다 — 내부 패널은 내부 창구를 부르므로
  //     포털이 그것을 그대로 쓰면 원장 수정·재검토 표시·관제 통지가 일어나 단방향 불변이 깨진다.
  const showMeta = true;
  const hasTabs = showMeta || showIssues;

  /**
   * 「영상 분석 설명 · 이벤트 어노테이션」 창 — 우측 메타 탭의 요약 카드가 연다.
   * [@design SCREEN-005] [@design UI-156] [@design UI-157]
   *
   * ★상태를 창이 아니라 화면이 든다 — 요약 카드가 같은 상태를 보고 버튼 문구(「크게 보기 · 작성」/
   *   「창 앞으로 가져오기」/「창 펼치기」)를 바꾸기 때문이다. 창 안에 두면 닫힌 동안 그 상태를
   *   아는 주체가 사라진다.
   * ★포털 채널은 이 창을 두지 않는다 — 내부 창구(메타·이벤트 어노테이션)를 부르므로 단방향 불변이
   *   깨진다(포털 본문은 PortalWorkMetaTab 이 따로 그린다).
   */
  const annotationWindow = useAnnotationWindow();
  // 요약 조회는 <b>그 카드가 보이거나 창이 떠 있을 때만</b> 켠다 — 페이지 진입마다 켜면 메타 탭을
  // 한 번도 열지 않는 작업자에게도 요청이 두 건 늘어난다.
  const annotationDataOn = !portalMode && (rightTab === 'meta' || annotationWindow.mounted);
  const annotationSummary = useAnnotationSummary(
    annotationDataOn ? data?.videoId : undefined,
    annotationDataOn ? data?.srcSn : undefined,
  );
  // 메타 탭 도움말 — 설명문을 한꺼번에 여닫는다. ★기본은 감춤이며 창의 도움말과 <b>별개 키</b>다
  //   (기본값이 서로 반대라 한 키를 공유하면 의도하지 않은 상태로 넘어간다 — `metaHelpPreference`).
  const [metaHelpVisible, toggleMetaHelp] = useMetaHelpPreference();
  const { data: issueThreads } = useIssueThreads(issuesReady ? issueRawSn : undefined);
  const unresolvedInquiries = (issueThreads ?? []).filter(
    (t) => t.issueTypeCd === 'INQUIRY' && t.issueSttsCd !== 'RESOLVED',
  ).length;

  // 프레임 썸네일 상태색 — issueThreads 를 INQUIRY srcSn 집합으로 가공해 FrameFilmstrip 에 주입한다.
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

  // R4·R5 — 폐기된 프레임 집합(썸네일 표식). 형제 응답의 폐기여부를 그대로 쓰되, **현재 프레임은
  // 화면 기준 값(미저장 전환 포함)** 으로 덮는다 — 방금 누른 전환이 썸네일에 즉시 보이지 않으면
  // 사용자는 눌린 건지 알 수 없다.
  // ⚠ 목록에서 빼는 용도가 아니다(D2 — 총량 불변). 표식만 붙인다.
  const discardedSrcSns = useMemo(() => {
    const set = new Set<number>();
    for (const sib of data?.siblings ?? []) {
      if (sib.dscdYn === DscdYn.Y) set.add(sib.srcSn);
    }
    if (data) {
      if (isDiscarded) set.add(data.srcSn);
      else set.delete(data.srcSn);
    }
    return set;
  }, [data, isDiscarded]);

  // 비식별 누락 신고 — 영상 잠금 상태 추적.
  // 1) BE 응답 lockSttsCd='LOCKED_FOR_REDEIDENT' → 진입 시 잠금
  // 2) 신고 성공 직후 → 클라이언트 측 reportedLock=true 로 즉시 잠금
  //    (BE 가 lockSttsCd 를 보장하지 않는 케이스 대비 — Phase 3 보강 권고)
  const [reportedLock, setReportedLock] = useState(false);
  // 판정값은 리터럴이 아니라 계약 상수(LockSttsCd)를 쓴다 — BE(LabelResponse.LOCK_STTS_LOCKED_FOR_REDEIDENT)
  // 와 문자열이 어긋나면 서버 잠금이 화면에 전혀 반영되지 않는다(H-ISSUE-41 실사고).
  const isLocked = data?.lockSttsCd === LockSttsCd.LOCKED_FOR_REDEIDENT || reportedLock;
  // 영상이 변경되면 클라이언트 측 잠금 마킹 초기화 (다른 영상 진입 시 잘못된 잠금 표시 방지)
  useEffect(() => {
    setReportedLock(false);
  }, [data?.videoId, data?.srcSn]);

  // 비식별 누락 신고 성공 처리.
  // ★BE 는 신고 접수 시 영상을 잠그고 `DE_IDNTF_YN='F'` 로 만들지만 <라벨은 삭제하지 않는다>
  // (2026-07-27 사용자 확정 — 구 "신고 시 라벨 전체 삭제" 정책 폐기). 대신 신고 구간 동안 라벨
  // 조회·저장이 신고 게이트로 412 가 되고, 해소되면 게이트가 풀려 보존된 라벨을 그대로 재사용한다.
  // 따라서:
  //  1) 클라이언트 잠금 마킹(reportedLock) → 배너/저장 차단 즉시 반영
  //  2) 라벨 스토어 reset → 캔버스/객체 목록의 스테일 라벨 즉시 제거 + undo/redo 스택 초기화
  //     (잠금 상태에서 스테일 라벨을 편집/되돌리기 시도하는 경로 자체를 차단. 라벨이 서버에서
  //      사라져서가 아니라, 잠긴 영상의 라벨을 화면에 띄워둔 채 편집·저장하는 경로를 막기 위함)
  //  3) 해당 프레임 범위의 LABEL 캐시를 <b>제거</b>(removeQueries) — 무효화(invalidateQueries)가
  //     아니다. 활성 화면에서는 둘 다 재조회를 유발해 412 안내가 뜨지만, <b>재진입 동선</b>에서
  //     갈린다. useLabels 는 staleTime 30s · refetchOnWindowFocus:false · gcTime 기본 5분이라
  //     invalidate 로는 캐시 <b>항목이 남는다</b>:
  //       · 신고 후 30초 내 재진입 → invalidate 가 붙인 stale 표식은 남지만 캐시 항목이 살아 있어
  //         <b>즉시 렌더</b>되고, reportedLock 은 컴포넌트 상태라 재마운트로 초기화돼 배너도 없다.
  //       · 30초~5분 → 캐시를 먼저 그린 뒤 백그라운드 412 → 그 사이 노출된다.
  //     라벨 좌표는 <b>PII 위치 특정 정보</b>라(CWE-359) 이 창이 서버 신고 게이트(412)를 그대로
  //     우회한다. removeQueries 는 항목 자체를 버리므로 재진입이 반드시 서버를 다시 때리고
  //     게이트가 적용된다. ⚠ <b>invalidateQueries 로 되돌리지 말 것</b> — 활성 화면 테스트만으로는
  //     차이가 드러나지 않아 "동등하다"고 오판하기 쉽다(회귀 가드는 재진입 케이스에 있다).
  //     키 범위: useLabels 는 LABEL_KEYS.byFrame(srcSn, 0) 으로 키잉되므로 그 prefix 인
  //     byVideo(srcSn)로 좁혀 무관한 영상/프레임 캐시까지 날리지 않는다(srcSn 미상일 때만 all).
  //  ※ 이 핸들러는 <b>신고 성공 시에만</b> 호출된다(DeidentReportButton onSuccess). 프레임 이동 등
  //    정상 동선은 이 경로를 타지 않으므로 깜빡임 방지(keepPreviousData) 동작에 영향이 없다.
  const handleDeidentReportSuccess = () => {
    setReportedLock(true);
    reset();
    if (data?.srcSn !== undefined) {
      queryClient.removeQueries({ queryKey: LABEL_KEYS.byVideo(data.srcSn) });
    } else {
      queryClient.removeQueries({ queryKey: LABEL_KEYS.all });
    }
  };

  // 저장 (PUT + commit) — 단축키와 헤더 버튼 공유.
  // useUpdateLabels 훅이 PUT 성공 시 LABEL/VIDEO/ASSIGNMENT/REVIEW_KEYS 를 일괄 invalidate 하여
  // 프레임 전환·작업 목록 진행률이 최신 상태로 갱신되도록 한다.
  const { mutateAsync: updateInternalLabels, isPending: savingInternal } = useUpdateLabels(
    currentFrame?.srcSn,
    // C-ISSUE-21 — 조회로 받은 라벨셋 버전을 저장 요청에 되돌려 보낸다(낙관적 동시성 토큰).
    //   보내지 않으면 BE 가 검사를 건너뛰어, 그사이 다른 사용자가 추가한 라벨이 full-replace 로
    //   조용히 삭제된다(실측된 lost update).
    {
      labelVersion: data?.labelVersion,
      // R4·R5 — **사용자가 전환했을 때만** 싣는다. 서버값을 되돌려 보내면 "현재 값 유지" 규약이
      //   깨져, 폐기를 건드리지 않은 저장이 남의 폐기 결정을 덮어쓴다.
      dscdYn: discardDraft,
    },
  );
  // 불러오기(LOAD) 배타 실행용 — 저장/AI 작업과 같은 busy 축을 공유한다.
  const { runExclusiveOrNotify } = useBusyTask({ srcSn: currentFrame?.srcSn });
  // R16 — 포털 저장은 원본 미수정, 본인 작업분을 LS_PORTAL_USER_LABEL 에 별도 적재.
  const { mutateAsync: savePortalLabels, isPending: savingPortal } = useSavePortalLabels(
    uploadSource ? undefined : currentFrame?.srcSn,
    uploadSource ? undefined : data?.videoId,
  );
  // SCREEN-029 — 업로드 자산은 **자산 축 창구**로 현재 프레임 전체교체 PUT 1회.
  //   ⚠ 저장 성공 후 `persistPendingWork` 가 `clearDirty()` 를 부른다 — 비우지 않으면 미저장
  //     편집 보호 가드가 계속 걸려 그 프레임이 서버와 영영 재동기화되지 않는다.
  const { mutateAsync: saveUploadLabels, isPending: savingUpload } = useSaveUploadLabels(
    uploadSource ? currentFrame?.srcSn : undefined,
  );
  const updateLabels = portalMode
    ? uploadSource
      ? saveUploadLabels
      : savePortalLabels
    : updateInternalLabels;
  const saving =
    (portalMode ? (uploadSource ? savingUpload : savingPortal) : savingInternal) || confirmingVideo;

  /**
   * API-196 — 불러온 회차 세트를 <b>영상 전체 한 트랜잭션</b>으로 확정한다.
   *
   * <p>왜 프레임 단위 저장으로 대신할 수 없나: 불러온 세트를 프레임 하나씩 저장하면 중간에 실패하거나
   * 사용자가 그만두는 순간 <b>서로 다른 회차의 프레임이 섞인 채</b> 확정된다. 그 상태가 그대로 학습
   * 데이터 산출물·관제로 나가기 때문에 확정은 영상 축이어야 한다(AC-008 ⑥).
   */
  const confirmLoadedVersion = async (draft: LoadedVersionDraft) => {
    const payload = toVideoSavePayload(draft, currentFrame?.srcSn, labels, discardDraft);
    await confirmVideoLabels(payload);
    // 확정됐다 — 대기 세트와 미저장 표식을 함께 내린다(캐시 무효화는 훅이 한다).
    setLoadedDraft(null);
    clearDirty();
    setDiscardDraft(null);
    pushToast({
      variant: 'success',
      message: `v${draft.version} 상태로 저장했습니다 (프레임 ${payload.frameVersions.length}개`
        + `${payload.edits.length > 0 ? `, 수정 ${payload.edits.length}개` : ''}).`,
    });
  };

  /**
   * 미저장 작업을 서버에 확정한다 — <b>저장 축의 단일 진입점</b>.
   *
   * <h3>왜 한 곳으로 모으나 (Critical)</h3>
   * 저장은 두 갈래다(SCREEN-005): 평상시에는 프레임 단위(API-019), 회차를 불러온 뒤에는 영상 전체
   * 확정(API-196). 이 분기를 호출부마다 두면 한 곳만 빠뜨려도 <b>불러온 세트에서 프레임 하나만
   * 저장</b>되어 한 영상에 서로 다른 회차가 섞인 채 확정된다(그 상태가 그대로 관제로 나간다).
   * 실제로 저장을 호출하는 지점이 셋이다 — 헤더 저장 · 프레임 이동 가드 · 닫기 가드.
   *
   * @returns {@code 'rejected'} 면 저장이 폐기·거부됐다는 뜻이므로 <b>이동·닫기를 하지 않는다</b>
   *          (저장되지 않은 채 화면을 떠나면 작업이 소실된다). {@code 'confirmed'} 는 영상 단위 확정
   *          경로로 갔다는 뜻이며 안내를 이미 마쳤다(호출부가 토스트를 겹쳐 띄우지 않게 구분한다).
   */
  const persistPendingWork = async (): Promise<'saved' | 'confirmed' | 'rejected'> => {
    if (!portalMode && loadedDraft) {
      await confirmLoadedVersion(loadedDraft);
      return 'confirmed';
    }
    // null === 취소·리셋·프레임 전환으로 폐기된 저장(내부 경로). 포털 저장은 void(undefined)를
    // 돌려주므로 falsy 가 아니라 `=== null` 로만 폐기를 판정한다.
    const saved = await updateLabels(labels);
    if (saved === null) return 'rejected';
    clearDirty();
    // 폐기 전환이 서버에 반영됐다 — 미저장 표식을 내리고 이후 판정은 응답의 서버값을 따른다.
    setDiscardDraft(null);
    return 'saved';
  };

  /**
   * R4·R5 — 이번 저장이 <b>실어 보내는</b> 폐기 전환. 「폐기 프레임 저장 확인」 모달의 입력이다.
   *
   * <p>저장 축이 둘이라 세는 축도 둘이다(둘 다 <b>보내는 값</b>만 센다 — 서버가 알아서 정하는
   * 프레임을 추정해 세면 안내가 사실이 아니게 된다):
   * <ul>
   *   <li>프레임 단위 저장 — 보내는 값은 {@code discardDraft} 하나이고 기준선은 서버값이다.</li>
   *   <li>회차 확정 저장 — {@code edits} 에 실린 프레임들이며 기준선은 회차 스냅샷 값이다
   *       (같은 축으로 세지 않으면 안내와 실제 저장이 갈린다).</li>
   * </ul>
   *
   * <p>포털 저장은 폐기 축을 싣지 않는다(폐기·복원은 내부 파이프라인 산출물 축이라 포털에 없다).
   */
  const discardSaveSummary = useMemo(() => {
    if (portalMode) return NO_DISCARD_CHANGE;
    if (loadedDraft) {
      const payload = toVideoSavePayload(loadedDraft, currentFrame?.srcSn, labels, discardDraft);
      return summarizeDiscardSave(discardChangesOf(loadedDraft, payload));
    }
    return summarizeDiscardSave([{ baseline: serverDscdYn, next: discardDraft }]);
  }, [portalMode, loadedDraft, currentFrame?.srcSn, labels, discardDraft, serverDscdYn]);
  const [discardSaveConfirmOpen, setDiscardSaveConfirmOpen] = useState(false);

  /**
   * 저장 공통 차단 조건 — 통과하면 true. 확인 모달을 거치는 경로에서도 <b>같은 판정</b>을 다시 태운다
   * (모달이 떠 있는 동안 잠금·진행 상태가 바뀔 수 있다).
   */
  const canSaveNow = (): boolean => {
    if (!currentFrame) return false;
    // 중복 제출 차단(FE 방어) — 저장 in-flight 중 Ctrl+S 연타/버튼 재클릭 시 라벨 PUT 이
    // 중복 발화하지 않도록 saving(isPending) 을 선두에서 가드한다.
    if (saving) return false;
    if (isEditBlocked || isEditBlockedNow(currentFrame?.srcSn)) return false;
    if (isLocked) {
      pushToast({
        variant: 'error',
        message: '비식별 재처리 중인 영상은 저장할 수 없습니다.',
      });
      return false;
    }
    return true;
  };

  const runSave = async () => {
    if (!canSaveNow()) return;
    try {
      // 저장 축은 persistPendingWork 한 곳이다(프레임 단위 / 영상 단위 확정 분기 포함).
      const outcome = await persistPendingWork();
      if (outcome === 'rejected') return;
      // 영상 단위 확정은 안내를 이미 마쳤다 — 여기서 또 띄우면 토스트가 겹친다.
      if (outcome === 'confirmed') return;
      // 저장은 작업본 임시저장(LS_DATA_LBL upsert)만 수행 — 버전/히스토리 스냅샷은 검수 승인
      // 시점에 BE 가 생성한다(CLAUDE.md 2계층, SFR-08). 따라서 '버전 기록됨' 등 사실과 다른
      // 문구를 쓰지 않고 양쪽 채널 모두 '저장됨' 으로 통일한다.
      pushToast({ variant: 'success', message: '저장됨' });
    } catch (e) {
      // C-ISSUE-21 — 409(CONFLICT)는 "다른 사용자가 먼저 저장했다"는 뜻이다. 일반 에러 토스트로
      //   흘려보내면 사용자는 원인을 모른 채 재시도만 반복하므로, 별도 안내 다이얼로그를 띄운다.
      //   dirty 는 유지한다 — 사용자의 작업 내용을 동의 없이 버리지 않는다.
      if (e instanceof ApiError && e.status === 409) {
        setSaveConflictMessage(
          extractBeMessage(e, '다른 사용자가 먼저 저장했습니다. 최신 라벨을 불러온 뒤 다시 저장하세요.'),
        );
        return;
      }
      pushToast({
        variant: 'error',
        message: extractBeMessage(e, '저장 실패'),
      });
    }
  };

  /**
   * 저장 버튼·단축키의 진입점.
   *
   * <p>저장에 <b>폐기 상태 변경</b>이 실려 있으면 몇 개가 산출물에서 빠지고 몇 개가 되돌아오는지
   * 먼저 드러내고 확인을 받는다(SCREEN-005). 폐기는 되돌릴 수 있지만 산출물에서 빠지는 결정이고,
   * 한번이라도 검수가 완료된 영상에서는 서버가 새 폐기·복원을 막아 되돌릴 회차가 없으면 그 프레임이
   * 산출물에서 영구 누락된다 — 조용히 저장되면 그 사실이 어디에도 드러나지 않는다.
   *
   * <p>⚠ 폐기 변경이 <b>없으면</b> 끼어들지 않는다. 모든 저장에 확인을 끼우면 작업 흐름이 망가진다.
   */
  const handleSave = async () => {
    if (!canSaveNow()) return;
    if (hasDiscardChange(discardSaveSummary)) {
      setDiscardSaveConfirmOpen(true);
      return;
    }
    await runSave();
  };

  /** 확인 모달의 '확인하고 저장' — 기존 저장 축(runSave)을 그대로 탄다(복제·우회 금지). */
  const handleConfirmDiscardSave = async () => {
    setDiscardSaveConfirmOpen(false);
    await runSave();
  };

  /**
   * 저장 충돌 해소 — 최신 라벨을 다시 불러온다. 사용자가 <b>명시적으로 선택</b>했을 때만 실행되며,
   * 미저장 변경은 이 시점에 사라진다(다이얼로그에 명시). dirty 를 먼저 비워야 같은 프레임 재조회가
   * 서버 라벨로 화면을 갱신한다(미저장 편집 보호 규칙 때문에 dirty 가 있으면 덮어쓰지 않음).
   */
  const handleReloadAfterConflict = async () => {
    // 불러오기도 장시간 작업이다 — 저장/AI 작업과 같은 배타 축(busy 'LOAD')에서 실행해야
    // 진행 중 작업과 겹쳐 작업본이 두 축에서 동시에 갈리지 않는다(R1 "저장·불러오기").
    // ⚠ 다이얼로그는 **성공한 뒤에** 닫는다 — 먼저 닫으면 거부(다른 작업 진행 중)됐을 때
    //   충돌 안내까지 사라져, 불러오지도 못하고 재시도 동선도 없는 화면이 된다.
    // ⚠ 미저장 표식(dirty)은 **재조회가 성공한 뒤에만** 해제한다. 먼저 비우면 재조회가 실패했을 때
    //   화면에는 내 편집이 그대로 남은 채 "저장됨"처럼 보여, 이탈 경고 없이 작업이 사라진다.
    //   성공 시에는 응답 라벨을 직접 반영한다 — "미저장 편집 보호" effect 는 dirty>0 이면
    //   덮어쓰지 않으므로 순서를 바꾸는 것만으로는 화면이 갱신되지 않는다.
    let loaded: boolean | null = null;
    try {
      loaded = await runExclusiveOrNotify(
        'LOAD',
        { srcSn: currentFrame?.srcSn },
        async (isAlive) => {
          const res = await refetchLabels();
          if (res.isError || !res.data) return false;
          // ⚠ **병합 직전 생존 확인**(AC5) — 다른 6개 호출부와 동일 계약이다. 이게 없으면
          //   사용자가 취소한 뒤 도착한 응답이 그대로 반영된다: setLabels 는 dirtyLabels 와
          //   undo/redo 스택까지 비우므로 미저장 작업이 **복구 불가능하게** 사라진다.
          //   여기서 멈추면 runExclusive 가 결과를 폐기(discarded → null)하고, 호출측은
          //   `loaded === null` 로 조용히 빠져나간다(취소는 무음이 정상).
          if (!isAlive()) return false;
          setLabels(Array.isArray(res.data.labels) ? res.data.labels : []);
          clearDirty();
          return true;
        },
      );
    } catch {
      loaded = false; // 예외도 실패로 취급 — dirty 는 유지된다.
    }
    if (loaded === null) return; // 거부(안내 토스트는 훅) 또는 폐기(무음이 정상)
    if (!loaded) {
      // 실패 — 안내만 하고 다이얼로그/미저장 상태를 그대로 둔다(재시도 동선 유지).
      pushToast({ variant: 'error', message: '최신 라벨을 불러오지 못했습니다. 다시 시도하세요.' });
      return;
    }
    setSaveConflictMessage(null);
    pushToast({ variant: 'success', message: '최신 라벨을 불러왔습니다.' });
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
    // 되돌리기는 작업본(labels/dirty)을 바꾸는 편집이다. 저장 in-flight 중에 실행되면 저장 성공 시
    // clearDirty() 가 되돌린 분의 미저장 표식까지 지워, 이탈 경고·프레임 가드가 풀린 채 서버본이
    // 화면을 덮는다. 버튼 비활성화와 별개로 실행 경로에서도 막는다(이중 방어 · fail-closed).
    if (isEditBlockedNow(currentFrame.srcSn)) {
      pushToast({
        variant: 'warning',
        message: busyRejectedMessage(useLabelStore.getState().busy?.kind ?? null),
      });
      return;
    }
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

  // Phase 4 — AI Tool 수동 트리거. 포털 채널도 쓴다 — 포털 전용 창구로 보낸다(portal 플래그).
  // 검출 결과는 BE 미저장(Phase 3 전환) → 재조회가 아니라 작업본에 병합한다. mock 응답은 자동적용 차단.
  //
  // 병합·안내는 onApply 로 넘겨 **진행 중(busy) 보호 구간 안에서** 수행한다. 바깥에서 병합하면
  // busy 가 먼저 풀려, 미완료 후처리와 재실행이 작업본을 동시에 건드린다.
  //
  // ⚠ 요청 형태(shape)는 **호출별 컨텍스트**로 받는다 — 공유 ref 로 읽으면 진행 중 요청 A 뒤에
  //   트리거된 B(곧 거부됨)가 ref 를 덮어써 A 결과의 안내 문구가 뒤바뀐다.
  const applyAutolabelResult = useCallback(
    (res: AutolabelResponse, ctx: AutolabelApplyContext) => {
      if (!currentFrame) return;
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
      // 작업명은 단일 소스에서 가져온다 — 여기서 문구를 인라인으로 만들면 오버레이/거부 안내와
      // 조용히 달라진다(모델명 미노출 규칙도 그 단일 소스가 보증한다).
      const kind = BUSY_KIND_NAME[ctx.shape === 'POLYGON' ? 'AI_SEGMENT' : 'AI_DETECT'];
      pushToast({ variant: 'success', message: `${kind} ${added}건 적용됨` });
    },
    [currentFrame, mergeAutoLabels, pushToast],
  );
  const { isAutolabeling, autolabel } = useAutolabel(currentFrame?.srcSn, {
    onApply: applyAutolabelResult,
    portal: portalMode,
  });
  // Phase 4 — AI Tool 팝업(형태 + 라벨 + 일반/트랙). 버튼 클릭 시 팝업을 열고, 확정 시 실행.
  const [autolabelModalOpen, setAutolabelModalOpen] = useState(false);
  // "즉시 그리기" 토글 — AI 분할 클릭마다 미리보기 즉시 그리기. 기본 OFF(false).
  // AI 분할 도구 활성 시 우측 속성 패널의 "AI 분할 정밀도" 섹션(ObjectAttributePanel)에서
  // 토글하고 CanvasShell→OverlayLayer 의 immediateSegment 로 배선된다.
  const [immediateDraw, setImmediateDraw] = useState(false);
  // Phase 2 [FE] — AI 정밀도 프리필. 저장된 기본값을 슬라이더 초기값으로 사용(실패/로딩/미저장 시
  // undefined → 컴포넌트 코드 상수 폴백). 인식 민감도는 정수%(0~80) → /100(0~1) 변환, 경계 세밀함은 그대로.
  // ★ 역할과 무관하게 조회한다 — 전용 읽기 경로 `/v1/ai-defaults`(검수자·작업자 공통, 값 두 개만)를
  // 쓴다. 구 방식(`/v1/manage/configs` + `enabled: isReviewer`)은 검수자 전용이라 작업자 진입마다
  // 403 이 쌓였고, 막고 나니 작업자는 저장된 기본값을 아예 받지 못했다.
  //   ★ 같은 응답이 **AI 대기 예산**(작업 종류별 제한시간·분할 단위·잠금 상한)도 싣는다. 그래서
  //   조회를 두 번 하지 않고 예산 발행까지 겸하는 훅을 쓴다 — 예산이 화면 상수로 남아 있으면
  //   서버가 재시도 예산을 바꿀 때 화면만 조용히 어긋나고, «화면이 더 짧은» 방향이면 정상 동작이
  //   «AI 실패» 로 보인다.
  //   ★<b>포털 채널은 포털 전용 창구(`/v1/portal/ai-defaults`)로 조회한다</b> (2026-09-15).
  //   내부 창구는 포털 토큰으로 403 이라 그대로 부르면 진입마다 실패가 쌓인다(작업자 403 이 쌓였던
  //   결함과 같은 계열). ⚠ [폐기] 구 서술 — *"포털 채널에서는 이 조회를 하지 않는다 · 그 채널에는
  //   AI 도구가 없다"*. 지금은 포털도 AI 보조를 쓰므로 조회를 막으면 대기 예산이 폴백으로 남는다.
  const { data: aiDefaults } = useAiWaitBudgetSync(true, portalMode);
  const defaultConfThreshold =
    aiDefaults?.confThreshold != null ? aiDefaults.confThreshold / 100 : undefined;
  const defaultSimplifyTolerance = aiDefaults?.simplifyTolerance;
  // AI 탐지 팝업 후보 — 활성 라벨 마스터 + COCO 매핑 여부. 매핑된 라벨만 검출 대상(BE 재검증).
  // 팝업이 열릴 때만 조회(enabled)하고, 실패 시 팝업에서 재시도(refetch) 노출.
  const {
    data: detectCandidates,
    isLoading: detectCandidatesLoading,
    isError: detectCandidatesError,
    refetch: refetchDetectCandidates,
  } = useDetectCandidates(autolabelModalOpen);
  // AI 분할 경계 세밀함 조절값 — undefined=미조절(프리필만 표시, 요청 미포함). 조절 시 숫자로 채워져
  // CanvasShell → 분할 요청 payload 에 주입된다(무회귀).
  const [segmentTolerance, setSegmentTolerance] = useState<number | undefined>(undefined);
  // ── 보기 전용 상태(회전·격자·영역 확대) ──────────────────────────────────────
  // ★세 값은 **표시 전용**이라 서버에 저장하지 않고 라벨 좌표에도 관여하지 않는다.
  //   좌측 도구바(토글)와 캔버스(표시)가 같은 값을 봐야 하므로 공통 상위인 이 화면이 단독 보유한다.
  const [rotation, setRotation] = useState<CanvasRotation>(0);
  const [showGrid, setShowGrid] = useState(false);
  const [zoomAreaMode, setZoomAreaMode] = useState(false);
  // 회전은 4단계 순환이다. 닫힌 집합 안의 모듈러 연산이라 별도 정규화가 필요 없다.
  const handleRotate = useCallback(
    (deltaDeg: -90 | 90) => {
      setRotation((prev) => ((((prev + deltaDeg) % 360) + 360) % 360) as CanvasRotation);
      // 회전 구간에는 캔버스가 편집 입력을 봉인하므로, 그리기 도구가 활성인 채로 들어가면
      // "도구는 켜졌는데 아무 일도 안 일어나는" 상태가 된다 — 선택 도구로 되돌려 둔다.
      setActiveTool(ToolType.SELECT);
    },
    [setActiveTool],
  );
  const handleToggleGrid = useCallback(() => setShowGrid((prev) => !prev), []);
  // 영역 확대도 캔버스가 그 구간의 편집 입력을 봉인하므로 회전과 같은 이유로 선택 도구로 되돌린다.
  const handleToggleZoomArea = useCallback(() => {
    const next = !zoomAreaMode;
    setZoomAreaMode(next);
    if (next) setActiveTool(ToolType.SELECT);
  }, [zoomAreaMode, setActiveTool]);
  // R12 — 트랙 모드 선택 시 팝업의 형태·라벨을 state 로 유지한다. 단 모달 형태는 더 이상
  // BBOX/POLYGON 객체의 추적 출력 형태를 강제하지 않는다(확정 사양). 실제 출력 형태는
  // ObjectAttributePanel 이 `shapeToDetectType(target.shape) ?? track.shape` 로 결정 —
  // 즉 선택 객체 형태가 우선이고, trackShape 는 예외형태(MASK/KEYPOINT) 폴백 + 라벨 힌트 용도로만 유지.
  const [trackShape, setTrackShape] = useState<DetectShapeType | undefined>(undefined);
  const [trackLabel, setTrackLabel] = useState<string | undefined>(undefined);
  const handleAutolabel = () => {
    if (!currentFrame) return;
    if (isEditBlocked || isEditBlockedNow(currentFrame.srcSn)) return;
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

  // (포털) 좌측 도구바 「AI 자동 추적」 — 실행하지 않고 우측 「객체」 탭의 자동 추적 패널로 이동·포커스.
  //   탭 전환은 렌더를 거쳐야 패널이 서므로, 요청 번호를 올려 두고 커밋 뒤 effect 에서 옮긴다.
  const autoTrackFocusRef = useRef<HTMLDivElement>(null);
  const [autoTrackFocusRequest, setAutoTrackFocusRequest] = useState(0);
  const handleFocusAutoTrack = useCallback(() => {
    setRightTab('objects');
    setAutoTrackFocusRequest((n) => n + 1);
  }, []);
  useEffect(() => {
    if (autoTrackFocusRequest === 0) return;
    const el = autoTrackFocusRef.current;
    if (!el) return;
    // jsdom 등 scrollIntoView 가 없는 환경에서도 포커스 이동은 이어간다.
    el.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' });
    el.focus({ preventScroll: true });
  }, [autoTrackFocusRequest]);
  // 도구바에서 미리 보일 비활성 사유 — 패널의 실행 조건과 같은 판정(뒤따르는 프레임 유무)을 쓴다.
  const autoTrackUnavailableReason =
    nextSrcSns.length === 0 ? '뒤따르는 프레임이 없어 AI 자동 추적을 쓸 수 없습니다.' : undefined;

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

      // BE TrackedItem 은 라벨명만 돌려주므로 마스터 PK 를 여기서 해석해 붙인다.
      // ⚠ 붙이지 않으면 labelId=null 로 저장돼 재조회 시 마스터 조인이 끊긴다(색·라벨명·속성 소실).
      const labelIdOf = (name: string) => resolveLabelIdByName(labelMasters, name);

      let applied = 0;
      if (forCurrent.length > 0) {
        applied += mergeAutoLabels(
          forCurrent.map((t) => trackedItemToLabel(t, currentFrame.frameNo, labelIdOf(t.label))),
        );
      }
      if (forFuture.length > 0) {
        // srcSn 별로 그룹화 — 각 미래 프레임 frameNo 로 Label 변환 후 보류에 stash.
        const bySrcSn: Record<number, Label[]> = {};
        for (const t of forFuture) {
          const frameNo = frames.find((f) => f.srcSn === t.srcSn)?.frameNo ?? 0;
          (bySrcSn[t.srcSn] ??= []).push(trackedItemToLabel(t, frameNo, labelIdOf(t.label)));
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
      labelMasters,
      nextSrcSns,
      mergeAutoLabels,
      stashPendingTracks,
      pushToast,
    ],
  );

  // 온디맨드 자동 추적 결과 반영 — 프레임(srcSn) 별 라벨을 받아 작업본에만 올린다(저장 아님).
  //
  // ★ 라벨 마스터 식별자는 **서버 응답값이 이미 실려 있다** — 여기서 검출 클래스명으로 마스터를
  //   다시 찾지 않는다(그 해석은 서버 몫이며, 화면이 재판정하면 규칙이 두 곳으로 갈린다).
  //   그래서 위 handleTracked 와 달리 resolveLabelIdByName 을 부르지 않는다.
  // ★ 현재 프레임 해당분은 즉시 병합하고, 미래 프레임 해당분은 기존 보류 스테이징에 stash 해
  //   그 프레임 진입 시 drain 병합된다(사일런트 유실 방지 — SAM2 추적과 같은 배선).
  // ★ 반영 결과를 **돌려준다** — 현재 프레임 병합은 이미 있는 라벨과 겹치는 검출을 건너뛰므로
  //   요청 건수와 실제 반영 건수가 다르다. 패널이 그 차이를 알 수 없으면 전부 걸러진 경우에도
  //   "올렸습니다" 라고 알리게 된다(반환값을 버리면 그 결함이 되돌아온다).
  const handleAutoTrackApply = useCallback(
    (labelsBySrcSn: Record<number, Label[]>): AutoTrackApplyOutcome => {
      const curSrcSn = data?.srcSn;
      let applied = 0;
      let requested = 0;
      const future: Record<number, Label[]> = {};
      let futureCount = 0;
      for (const [key, labels] of Object.entries(labelsBySrcSn)) {
        const srcSn = Number(key);
        if (labels.length === 0) continue;
        requested += labels.length;
        if (srcSn === curSrcSn) {
          applied += mergeAutoLabels(labels);
        } else {
          future[srcSn] = labels;
          futureCount += labels.length;
        }
      }
      if (futureCount > 0) stashPendingTracks(future);
      const total = applied + futureCount;
      // 걸러진 것은 현재 프레임 병합분뿐이다 — 미래 프레임 몫은 그 프레임에 진입할 때 병합된다.
      const outcome: AutoTrackApplyOutcome = {
        appliedLabels: total,
        skippedDuplicates: Math.max(0, requested - total),
      };
      if (total === 0) return outcome;
      // 작업명은 단일 소스에서 가져온다(모델명 미노출 규칙을 그 소스가 보증한다).
      pushToast({
        variant: 'success',
        message: `${BUSY_KIND_NAME.AI_AUTO_TRACK} ${total}건 적용됨`,
      });
      return outcome;
    },
    [data?.srcSn, mergeAutoLabels, stashPendingTracks, pushToast],
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
          ? detectCandidates?.find((c) => c.dtctTypeCd === classIds[0])?.name
          : undefined;
      setTrackLabel(pickedLabel);
      pushToast({
        variant: 'info',
        message: '추적할 객체를 선택한 뒤 속성 패널에서 AI 추적을 실행하세요.',
      });
      return;
    }
    try {
      // 요청 형태는 autolabel 이 onApply 컨텍스트로 되돌려준다(공유 ref 미사용). 병합도 onApply 담당.
      // opts 는 사용자가 슬라이더를 조절한 값만 담긴다(미조절이면 undefined → BE 기본값, 무회귀).
      // 반환값이 null 이면 거부(다른 작업 진행 중) 또는 취소·프레임 전환으로 폐기된 응답이다.
      await autolabel(classIds, shape, opts);
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
    // 목록 패널의 버튼은 차단 중 감춰지지만, 콜백 자체도 막아 둔다(진입점이 늘어나도 새지 않게).
    if (isEditBlockedNow(currentFrame?.srcSn)) return;
    // Phase 10(축소) — 포털은 트랙 번호 변경·병합을 제공하지 않는다.
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
    if (isEditBlockedNow(currentFrame?.srcSn)) return;
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
    if (isEditBlockedNow(currentFrame?.srcSn)) return;
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
  /**
   * X(닫기).
   *
   * ⚠ **의도된 허용** — 상단의 다른 액션(저장·제출·취소·신고·프레임이동·트랙편집·되돌리기·롤백)과
   *   달리 진행 중(busy) 게이트를 걸지 않는다. 이탈은 사용자의 명시적 의도이고, 닫기 자체는
   *   작업본을 바꾸지 않아 잃을 것이 없기 때문이다. 진행 중에 막으면 오버레이가 아직 없는
   *   지연 창(<300ms)에서는 X 가 아무 반응 없이 무시되는 화면이 된다.
   *   미저장분은 아래 확인 모달이 지키고, 그 모달의 "저장 후 닫기" 는 배타 실행에 거부되면
   *   이동하지 않고 안내한다(handleConfirmSaveAndClose). 모달과 오버레이가 함께 떠도 포커스는
   *   모달이 갖는다(BusyOverlay 의 모달 감지 — D2).
   */
  const handleClose = () => {
    // R4·R5 — 폐기 전환도 미저장 변경이므로 같은 가드를 태운다.
    //   R6 — 불러온 회차 세트도 미저장이다(확인 없이 나가면 사용자는 되돌린 줄 안다).
    if (dirtyCount > 0 || discardPending || loadedDraft !== null) {
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
      // 폐기·거부된 저장이면 이동하지 않고 미저장 상태를 유지한다 — 저장되지 않은 채 화면을 떠나면
      // 작업이 소실된다(handleSave/handleNavSaveAndMove 와 동일 계약, 같은 저장 축을 탄다).
      if ((await persistPendingWork()) === 'rejected') {
        setCloseConfirmOpen(false);
        return;
      }
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
  // [@design ADR-012] [@design AC-1105] [@design AC-1106]
  // ★ 공용 훅으로 건다 — 세션을 끝내고 떠나는 경로(관제 세션 강제 로그아웃·확인을 거친 사용자
  //   로그아웃)가 이동 직전에 이 경고를 끈다. 여기서 `window.addEventListener` 로 직접 걸면 그 표식을
  //   모르는 경고가 되어, 끝난 세션에서 브라우저 확인창에 갇힌다. 조건은 종전 그대로다.
  useBeforeUnloadWarning(dirtyCount > 0 || discardPending);
  // 미저장 여부를 전역에 알린다 — 세션 연장 팝업이 「저장하지 않은 작업이 있습니다」를 보이고,
  // 사용자가 누른 「로그아웃」에 확인 절차를 붙인다. 판정은 X(닫기) 가드와 같은 세 축이다.
  useUnsavedWorkFlag('labeling', dirtyCount > 0 || discardPending || loadedDraft !== null);

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
    // 포털 모드에서는 포털 미제공 도구(선택 객체 AI 추적·스켈레톤) 단축키 게이팅(툴바 숨김과 정합).
    //
    // 모달 열림 중 단축키 억제는 **여기서 나열하지 않는다**(NF-4②) — 훅이 열린 모달을 DOM
    // 단일 판정(hasOpenModalDialog)으로 직접 본다. 손으로 나열하던 방식은 새 모달(신고·삭제
    // 확인)이 빠진 채 남아 그 모달 위에서 R/Del 이 배경 라벨을 지웠다.
    {
      portalMode,
      // 버튼만 막고 키보드를 열어두면 차단이 그대로 우회된다(ESC 취소 동선만 예외).
      blocked: isEditBlocked,
    },
  );

  const [canvasRef, canvasSize] = useContainerSize<HTMLDivElement>();

  // KEYPOINT 순차 배치 진행 인덱스(0~16) — OverlayLayer→CanvasShell 이 보고.
  // 가이드는 우측 패널(탭 위 상시 영역)에서 렌더하므로 캔버스와 패널의 공통 부모인
  // 이 페이지로 state 를 리프팅한다. 미진행/완료 시 null → 가이드 미표시.
  // (구 좌측 라벨 패널 폐지 — 2026-08-03. 캔버스 절대위치 오버레이는 좁은 폭에서 잘려 폐기됐던
  //  방식이라 되돌리지 않고, 좌측 패널(w-56)보다 넓은 우측 패널(w-72)로 옮겼다.)
  const [keypointPlacingIndex, setKeypointPlacingIndex] = useState<number | null>(null);

  // 도형 도구 ↔ 라벨 선택 모달 (2026-08-03) — 툴바 클릭·단축키 어느 경로로 도구가 바뀌든
  // 이 훅 하나가 판정한다. 라벨을 고르기 전에는 모달이 캔버스를 덮어 드로잉이 시작되지 않는다.
  const labelPicker = useToolLabelPicker();
  // 도구를 고르면 영역 확대 모드를 내린다 — 켜 둔 채로 그리기 도구를 고르면 캔버스가 입력을
  // 봉인한 상태라 "도구는 켜졌는데 아무 일도 안 일어나는" 죽은 조작이 된다(회전과 같은 함정).
  const handleSelectTool = useCallback(
    (tool: ToolType) => {
      setZoomAreaMode(false);
      labelPicker.requestTool(tool);
    },
    [labelPicker],
  );

  // 잘못된 ID — 풀스크린 에러. 업로드 갈래는 `:id` 가 자산이라 안내 문구가 다르며,
  // 그 판정(형식·범위)은 아래 `uploadNotice` 한 곳이 갖는다.
  if (!uploadSource && Number.isNaN(numericId)) {
    return (
      <div
        ref={editorFillRef}
        className={cn('bg-gray-50 flex items-center justify-center text-gray-900', editorShellClass)}
        style={editorShellStyle}
        data-testid="labeling-page"
      >
        <div className="text-center">
          <p className="text-title-md font-semibold mb-2">잘못된 프레임 ID</p>
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="px-4 py-2 bg-primary-600 text-white rounded-lg text-body-md hover:bg-primary-700 transition-colors"
          >
            뒤로 가기
          </button>
        </div>
      </div>
    );
  }

  /*
   * 업로드 자산 갈래의 상태 안내 — 캔버스를 렌더하지 않는다. @design SCREEN-029
   *
   * ★네 사유 모두 **상태 안내(`role="status"`)** 다. 프레임 0건은 오류가 아니고(마킹으로 위치를
   *   정한 뒤에 프레임이 생긴다), 「없다」와 「남의 것이다」도 문구를 가르지 않는다(가르면 그
   *   구분이 남의 저작물 존재를 알아내는 수단이 된다). 처리 실패 ↔ 준비 중만 갈리며 그 둘은
   *   본인 자산의 진행 상태라 갈라도 남의 것이 드러나지 않는다.
   */
  if (uploadNotice) {
    return (
      <div
        ref={editorFillRef}
        className={cn('bg-gray-50 flex items-center justify-center text-gray-900', editorShellClass)}
        style={editorShellStyle}
        data-testid="labeling-page"
      >
        <div className="text-center">
          <p
            role="status"
            data-testid="portal-upload-notice"
            data-notice-kind={uploadNotice.kind}
            className="text-body-md text-gray-700 mb-4"
          >
            {uploadNotice.message}
          </p>
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="px-4 py-2 bg-primary-600 text-white rounded-lg text-body-md hover:bg-primary-700 transition-colors"
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
        ref={editorFillRef}
        className={cn('bg-gray-50 flex items-center justify-center text-gray-900', editorShellClass)}
        style={editorShellStyle}
        data-testid="labeling-page"
      >
        <div className="flex flex-col items-center gap-3">
          <Spinner label="라벨 로딩" />
          <p className="text-body-md text-gray-600">라벨 로딩 중...</p>
        </div>
      </div>
    );
  }

  // R17 이슈5 — 포털 모드에서 라벨 로드가 거부되면 graceful 차단 화면.
  // 빈 캔버스 노출(데이터 없는 UI) 대신 명확한 안내 + 뒤로 가기.
  //
  // ★★403 과 404 를 <b>가르지 않는다</b>. 백엔드는 미노출·미승인에 403 을, 프레임 부재에 404 를
  //   내므로 화면이 두 상태를 다른 문구로 나누면 그 분기가 <b>실재 여부 오라클</b>이 된다 — 서버가
  //   감춘 것이 화면 층에서 그대로 풀린다. 같은 포털 화면의 메타·이벤트 어노테이션 축이 이미 두
  //   상태를 한 문구로 모으고 있어, 라벨 축만 가르면 <b>한 화면 안에서 규칙이 갈린다</b>.
  //   판정·문구는 포털 작업 화면의 단일 지점(workError)이 소유한다.
  // ⚠ 내부 채널은 이 규칙의 대상이 아니다 — 아래 일반 분기가 그대로 서버 문구를 보여 준다.
  const isPortalUnavailable = portalMode && !!error && isPortalUnavailableError(error);
  if (isPortalUnavailable) {
    return (
      <div
        ref={editorFillRef}
        className={cn('bg-gray-50 flex items-center justify-center text-gray-900', editorShellClass)}
        style={editorShellStyle}
        data-testid="portal-forbidden-screen"
      >
        <div className="text-center">
          <p className="text-title-md font-semibold mb-2">접근할 수 없는 영상입니다</p>
          {/* ★문구는 상태코드로만 만든다 — 서버 메시지를 실으면 「프레임을 찾을 수 없습니다」가
              그대로 나가 404 와 403 이 구분된다(CWE-209). 세 축이 이 상수 하나를 쓴다. */}
          <p className="text-body-md text-gray-600 mb-4" data-testid="portal-unavailable-message">
            {PORTAL_WORK_ERROR_UNAVAILABLE}
          </p>
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="px-4 py-2 bg-primary-600 text-white rounded-lg text-body-md hover:bg-primary-700 transition-colors"
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
        ref={editorFillRef}
        className={cn('bg-gray-50 flex items-center justify-center text-gray-900', editorShellClass)}
        style={editorShellStyle}
        data-testid="labeling-page"
      >
        <div className="text-center">
          <p className="text-title-md font-semibold mb-2">라벨 조회 실패</p>
          {/*
            ★포털 모드는 서버 메시지를 <b>에코하지 않는다</b> — 백엔드 문구가 대상의 실재 여부를
              드러내기 때문이다(CWE-209). 문구는 상태코드로만 만든다.
            ⚠ 내부 채널은 종전대로 서버 문구를 그대로 보여 준다 — 그쪽은 감출 대상이 없고,
              신고 게이트(412)처럼 서버가 준 행위 중립 안내가 그 자체로 필요하다.
          */}
          <p className="text-body-md text-gray-600 mb-4">
            {portalMode ? portalWorkErrorMessage(error) : error.message}
          </p>
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="px-4 py-2 bg-primary-600 text-white rounded-lg text-body-md hover:bg-primary-700 transition-colors"
          >
            뒤로 가기
          </button>
        </div>
      </div>
    );
  }

  const objectCount = labels.length;
  // R4·R5 — 폐기 전환도 미저장 변경이다(헤더 '편집 중' 표시가 이 값을 따른다).
  // R6/API-195 — 불러온 회차 세트도 **미저장**이다. 이 축이 없으면 "불러왔는데 저장하지 않은" 상태가
  //   깨끗한 화면으로 보여, 이탈 경고 없이 불러온 내용이 사라진다(서버는 그대로라 데이터 손실은
  //   아니지만 사용자는 되돌린 줄 안다).
  const isDirty = dirtyCount > 0 || discardPending || loadedDraft !== null;
  // CCTV명은 향후 연동 — 현재는 srcSn 표시
  //
  // ★<b>업로드 갈래에서는 파일명을 쓴다.</b> 프레임 일련번호는 서버가 매긴 값이라 <b>이용자가
  //   고른 적도 본 적도 없다</b> — 자기 영상 여럿을 오가며 작업할 때 어느 것인지 분간할 근거가
  //   되지 못한다. 이 갈래를 관제 도구로 합치기 전의 화면은 파일명을 머리에 두고 있었고, 합치면서
  //   그 정보만 떨어져 나갔다. 몇 번째 프레임인지는 옵션바가 이미 `n / 전체` 로 말한다.
  //   ⚠ 사용자가 올린 문자열이라 텍스트 노드로만 렌더한다(머리글 컴포넌트가 그렇게 받는다).
  const cctvName = uploadSource
    ? (uploadLabelSource.assetName ?? undefined)
    : data
      ? `프레임 #${data.srcSn}`
      : undefined;
  // UI-055 — 헤더 이벤트 유형 배지. 구 코드는 `eventType={undefined}` 를 **항상 고정 전달**해
  // 배지가 영영 뜨지 않았다(기능이 죽어 있었다). 값 출처는 영상 상세이며 한글 표시명(eventName)을
  // 우선하고 없으면 EV-코드로 폴백한다. categoryKey(그룹 대표코드)는 전달하지 않는다.
  const headerEventType = videoDetail?.eventName ?? videoDetail?.eventTypeCd ?? undefined;

  return (
    <div
      ref={editorFillRef}
      className={cn('bg-gray-50 flex flex-col overflow-hidden', editorShellClass)}
      style={editorShellStyle}
      data-testid="labeling-page"
    >
      <LabelHeader
        portalMode={portalMode}
        cctvName={cctvName}
        eventType={headerEventType}
        currentFrame={frameIdx}
        dirty={isDirty}
        // 저장 버튼은 캔버스 상단 옵션바로 일원화. 헤더는 진행/저장 상태만 표시한다.
        // ⚠ 히스토리 진입은 헤더에 두지 않는다(R6/D4) — 「시작 버전 선택」 모달이 단독 진입점이다.
        saving={saving}
        onClose={handleClose}
        onHelpClick={() => setCheatSheetOpen(true)}
        deidentReportButton={
          canReportDeident && data?.srcSn !== undefined ? (
            <DeidentReportButton
              srcSn={data.srcSn}
              // 신고 성공은 reset() 으로 이어지고 reset 은 진행 중 작업(busy)을 조용히 취소한다 —
              // 사용자는 취소한 적이 없는데 저장/AI 작업이 사라지므로 진행 중에는 진입을 막는다.
              // ★`frameImageType === 'RAW'` 게이팅은 헤더 DEID/RAW 배지가 폐지된 뒤에도 유지한다 —
              //   배지는 표시일 뿐이고 이 값은 REVIEWER 가 원본을 보는 중의 오신고를 막는 축이다.
              //   (표시가 사라졌다고 값 배선까지 지우면 원본 화면에서 신고 버튼이 열린다.)
              disabled={isLocked || isEditBlocked || data.frameImageType === 'RAW'}
              unsupportedReason={deidentReportUnsupportedReason}
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
                  disabled={cancelling || isLocked || isEditBlocked}
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
                disabled={submitting || isLocked || isEditBlocked || submitBlockedByStatus}
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

      {/* 영상 잠금 상태 배너 — LOCKED_FOR_REDEIDENT 시 라벨 수정 불가 안내.
          ★라이트 톤이다 — 어두운 배색(구 `bg-amber-900/60 text-amber-100`)을 쓰지 않는다.
            이 앱에 다크 표면은 없고(2026-08-06 다크 테마 폐지) 유일한 예외는 **영상 프레임을 얹는
            미디어 매트와 그 위 오버레이**인데, 이 배너는 헤더 아래 상시 안내라 그 예외가 아니다.
            팔레트는 표준 배너(components/common/Alert)의 의미색 단계(50 배경 · 200 테두리 ·
            600 아이콘 · 700 제목)를 그대로 따른다 — 그 컴포넌트 자체를 쓰지 않는 이유는 이 자리가
            둥근 카드가 아니라 폭을 꽉 채우는 스트립이고 제목/본문 2층 구조도 아니기 때문이다. */}
      {isLocked && (
        <div
          data-testid="deident-locked-banner"
          role="status"
          aria-live="polite"
          className="flex shrink-0 items-center gap-2 border-b border-warning-200 bg-warning-50 px-4 py-2 text-body-md text-warning-700"
        >
          <AlertTriangle className="h-4 w-4 shrink-0 text-warning-600" aria-hidden="true" />
          비식별 재처리 중인 영상입니다. 처리가 완료될 때까지 라벨 수정·저장이 제한됩니다.
        </div>
      )}

      {/* R4·R5 — 폐기 프레임 안내. 캔버스가 읽기 전용이 된 **이유**를 말한다(잠긴 이유를 알리지
          않으면 도구가 왜 안 먹는지 알 수 없다). 라벨은 그대로 보이며 복원하면 다시 편집된다.
          미저장 전환 중에는 그 사실도 함께 알린다 — 저장 전까지는 서버가 아직 모른다. */}
      {isDiscarded && (
        <div
          data-testid="frame-discarded-notice"
          role="status"
          aria-live="polite"
          className="shrink-0 border-b border-warning/40 bg-warning/10 px-4 py-2 text-body-md text-gray-800"
        >
          폐기한 프레임입니다. 학습데이터 산출물에서 빠지며 복원하기 전까지 라벨을 고칠 수 없습니다.
          라벨과 이미지는 지우지 않고 그대로 보관합니다.
          {discardPending && ' 아직 저장하지 않았습니다 — 저장해야 확정됩니다.'}
        </div>
      )}

      {/* dirty 가드 — X 닫기 시 미저장 변경 확인.
          3-옵션 다이얼로그(저장 후 닫기 / 저장 없이 닫기 / 머무름) 이므로 ConfirmDialog 대신
          Modal 직접 사용. ESC/백드롭/X = 머무름 (handleStayOnPage).
          R4·R5 — '저장 후 닫기' 도 persistPendingWork(저장 축)를 그대로 타므로, 그 저장이 실어
          보낼 폐기 전환을 본문에 함께 알린다(변경이 없으면 렌더 없음 — 기존 다이얼로그 그대로). */}
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
      >
        {hasDiscardChange(discardSaveSummary) ? (
          <DiscardSaveNotice summary={discardSaveSummary} testId="label-close-discard-notice" />
        ) : null}
      </Modal>

      {/* R5 — 프레임 이동 미저장 가드(저장 후 이동 / 저장 안 함 / 취소).
          R4·R5 — '저장 후 이동' 은 persistPendingWork(저장 축)를 그대로 타므로, 그 저장이 실어
          보낼 폐기 전환을 본문에 함께 알린다(변경이 없으면 렌더 없음 — 기존 다이얼로그 그대로). */}
      <FrameNavGuardModal
        open={navGuardTarget !== null}
        dirtyCount={dirtyCount}
        saving={navGuardSaving}
        discardSummary={discardSaveSummary}
        onSaveAndMove={handleNavSaveAndMove}
        onDiscardAndMove={handleNavDiscardAndMove}
        onCancel={handleNavCancel}
      />

      {/* R4 — 단축키 치트시트(도움말). ?(shift+/) 또는 헤더 도움말 버튼으로 토글. */}
      <ShortcutCheatSheet
        open={cheatSheetOpen}
        onClose={() => setCheatSheetOpen(false)}
        portalMode={portalMode}
      />

      {/* Phase 4 — AI Tool 팝업(형태 + 라벨 + 실행). 확정 시 shape/classIds/mode 로 실행.
          포털은 [탐지 실행] 하나 — 트랙 진입은 우측 「AI 자동 추적」 패널이 유일한 자리다(SCREEN-029). */}
      <AiToolModal
        open={autolabelModalOpen}
        onClose={() => setAutolabelModalOpen(false)}
        onConfirm={runAiTool}
        runMode={portalMode ? 'detectOnly' : 'detectOrTrack'}
        portalMode={portalMode}
        canTrack={nextSrcSns.length > 0}
        candidates={detectCandidates}
        candidatesLoading={detectCandidatesLoading}
        candidatesError={detectCandidatesError}
        onRetryCandidates={() => void refetchDetectCandidates()}
        defaultConfThreshold={defaultConfThreshold}
        defaultSimplifyTolerance={defaultSimplifyTolerance}
        disabled={isEditBlocked}
      />

      {/* 2026-08-03 — 도형 도구 클릭/전환 시 라벨 선택. 취소하면 도구가 활성화되지 않는다. */}
      <LabelPickerModal
        open={labelPicker.open}
        toolName={labelPicker.pendingTool ? TOOL_DISPLAY_NAME[labelPicker.pendingTool] : undefined}
        onSelect={labelPicker.confirm}
        onCancel={labelPicker.cancel}
      />

      {/* 본문 — 좌측 도구바 + 캔버스 + 우측 패널 (좌측 상시 라벨 패널은 2026-08-03 폐지) */}
      <div className="flex flex-1 overflow-hidden">
        <ToolBar
          portalMode={portalMode}
          onAutolabel={handleAutolabel}
          isAutolabeling={isAutolabeling}
          onSelectTool={handleSelectTool}
          rotation={rotation}
          onRotate={handleRotate}
          zoomAreaMode={zoomAreaMode}
          onToggleZoomArea={handleToggleZoomArea}
          showGrid={showGrid}
          onToggleGrid={handleToggleGrid}
          onFocusAutoTrack={portalMode ? handleFocusAutoTrack : undefined}
          autoTrackUnavailableReason={portalMode ? autoTrackUnavailableReason : undefined}
        />

        {/* 캔버스 열 — 상단 옵션바 + 캔버스 */}
        <div className="flex flex-1 flex-col overflow-hidden">
          {/* 캔버스 상단 옵션바 — 프레임 이동 · 삭제 · 실행취소/다시실행 · 저장(유일 진입점).
              ★잠금(LOCKED_FOR_REDEIDENT)은 편집 차단(busy)과 다른 축이라 옵션바가 자체 판정할 수
                없다 — 전달이 빠지면 잠긴 영상에서 저장 버튼이 활성으로 보인다. */}
          <CanvasOptionBar
            frameIndex={frameIdx}
            frameCount={frames.length}
            onRequestGoTo={requestJumpTo}
            srcSn={currentFrame?.srcSn}
            labels={labels}
            portalMode={portalMode}
            locked={isLocked}
            onRequestSave={handleSave}
            saving={saving}
            // R4·R5 — 폐기·복원은 포털 채널에 없다(내부 파이프라인 산출물 축).
            dscdYn={portalMode ? null : effectiveDscdYn}
            discardPending={discardPending}
            onToggleDiscard={portalMode ? undefined : handleToggleDiscard}
            discardUnsupportedReason={frameDiscardUnsupportedReason}
            // R6/D4 — 「시작 버전 선택」 재진입. 자동 노출은 진입당 1회뿐이라 이 버튼이 없으면
            //   모달을 닫는 순간 버전 목록·diff·롤백이 그 세션 내내 도달 불가가 된다.
            onOpenStartVersion={portalMode ? undefined : () => setStartVersionOpen(true)}
          />

        {/* 캔버스 영역 — flex로 자동 채움 */}
        {/* ★미디어 뷰포트 매트 — 라이트 전환의 유일한 예외다. 여기는 UI 크롬이 아니라 영상
            프레임을 얹는 바탕이라 순백으로 두면 어두운 CCTV 화면과 대비가 극심해 눈부심이
            생기고 라벨 색 판별이 나빠진다. 중립 회색으로 낮춰 둔다. */}
        <div
          ref={canvasRef}
          className="flex-1 relative overflow-hidden flex items-center justify-center bg-gray-200"
        >
          {currentFrame ? (
            <Suspense
              fallback={
                <div className="flex items-center justify-center text-gray-700">
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
                // D7 — 폐기된 프레임은 읽기 전용이다. BE 는 폐기 프레임의 라벨 저장을 막지
                //   않으므로(실측) 이 차단은 화면이 단독으로 진다. 라벨은 지우지 않고 그대로
                //   보여주며, 복원하면 다시 편집할 수 있다.
                readOnly={isLocked || isEditBlocked || isDiscarded}
                onLabelAdd={(l) => addLabel({ ...l, frameNo: currentFrame.frameNo })}
                onKeypointPlacingChange={setKeypointPlacingIndex}
                onImageSize={handleImageSize}
                immediateSegment={immediateDraw}
                segmentSimplifyTolerance={segmentTolerance}
                rotation={rotation}
                showGrid={showGrid}
                zoomAreaMode={zoomAreaMode}
                portalMode={portalMode}
              />
            </Suspense>
          ) : (
            <div className="text-gray-700 text-body-md">프레임 없음</div>
          )}
          {/* 프레임 이미지 로드 실패 안내 — 캔버스는 그대로 두고(라벨/도구는 계속 조작 가능) 실패
              사실만 겹쳐 알린다. 이게 없으면 이미지 404/412 가 "그냥 백지"로 보인다. */}
          {/* 진행 오버레이 — 무엇이 진행 중인지 캔버스 위에 보이고 거기서 바로 취소한다(AC4/AC5).
              300ms 지연 표시라 즉시 그리기처럼 짧은 작업에는 깜빡이지 않는다(AC7). */}
          <BusyOverlay
            kind={busyKind}
            startedAt={busyStartedAt}
            limitMs={busyLimitMs}
            onCancel={cancelBusy}
          />
          {imageError && !imageLoading && (
            <div
              role="alert"
              data-testid="frame-image-error"
              className="absolute top-4 left-1/2 -translate-x-1/2 z-10 max-w-[90%] rounded border border-red-300 bg-red-50 px-4 py-2 text-center text-body-md text-red-800 shadow-lg"
            >
              프레임 이미지를 불러오지 못했습니다.
              {frameImageErrorHint && (
                <span className="ml-2 text-caption text-red-700">{frameImageErrorHint}</span>
              )}
            </div>
          )}
        </div>
        </div>

        {/* 우측 패널 — 탭(객체 / 메타 / 이슈). 메타·이슈 탭은 INTERNAL 채널만 노출. */}
        <div
          data-testid="labeling-right-panel"
          className="w-72 flex flex-col bg-white border-l border-gray-200 overflow-hidden shrink-0"
        >
          {/* 키포인트(COCO-17) 순차 배치 가이드 — 탭 위 상시 영역이라 어느 탭을 보고 있어도
              배치 중에는 계속 보인다(구 좌측 라벨 패널에서 이전, 2026-08-03). */}
          <div data-testid="keypoint-guide-slot" className="shrink-0 px-2">
            <KeypointGuide placingIndex={keypointPlacingIndex} />
          </div>
          {hasTabs && portalMode && (
            /*
              ★**KRDS 탭의 생김새를 그대로 쓴다** (2026-09-16 사용자 지적). 종전에는 같은 모양을
                Tailwind 로 손수 그렸는데 밑줄 굵기·활성 글자색이 부모 포털과 갈려 있었다.
                킷 클래스를 입으면 **토큰이 값을 정한다** — 우리 포털 테마가 그 토큰을 부모 포털과
                같은 값으로 덮어 두었으므로 저절로 맞는다.
              ★여기는 셸 이동 탭과 달리 **진짜 탭**이다(같은 문서 안의 tabpanel 을 가른다).
                그래서 킷과 같은 ARIA(`tablist`/`presentation`/`tab`)를 그대로 쓴다.
              ★이슈 탭은 포털에 오지 않는다(`showIssues` 가 내부 채널 전용) — 감추는 것이 아니라
                값이 없어 서지 않는다.
              ⚠ 아래 관제 블록은 **손대지 않는다**(관제향 화면 불변 구속).
            */
            <div className="krds-tab-area shrink-0">
              {/* ★`full` — 두 탭이 패널 폭을 **반씩 나눠 채운다**(킷 `<Tab size="full">` 과 같은 값).
                  이 한 낱말이 빠져 있어 탭이 왼쪽에 몰려 서고 패널 오른쪽이 비어 있었다
                  (2026-09-16 사용자 지적). 킷 규칙이 `.tab.full>ul{display:flex}` ·
                  `.tab.full>ul>li{flex:1 1 0}` 로 폭을 나누므로 우리가 값을 적지 않는다. */}
              <div className="tab line full">
                <ul role="tablist" aria-label="우측 패널 탭">
                  {[
                    { key: 'objects' as const, label: '객체', show: true },
                    { key: 'meta' as const, label: '메타', show: showMeta },
                  ]
                    .filter((t) => t.show)
                    .map((t) => {
                      const on = rightTab === t.key;
                      return (
                        <li
                          key={t.key}
                          role="presentation"
                          className={cn('tab-item', on && 'active')}
                        >
                          <button
                            type="button"
                            role="tab"
                            id={`right-tab-${t.key}`}
                            aria-selected={on}
                            aria-controls={`right-panel-${t.key}`}
                            data-testid={`right-tab-${t.key}`}
                            className="btn-tab"
                            onClick={() => setRightTab(t.key)}
                          >
                            {t.label}
                            {/* 지금 어느 탭인지는 색·밑줄로만 보인다 — 킷이 두는 화면 밖 글을 그대로 둔다. */}
                            {on && <i className="sr-only">선택됨</i>}
                          </button>
                        </li>
                      );
                    })}
                </ul>
              </div>
            </div>
          )}
          {hasTabs && !portalMode && (
            <div
              className="flex shrink-0 border-b border-gray-200"
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
                    ? 'flex-1 px-3 py-2 text-label font-semibold text-primary-700 border-b-2 border-primary-500'
                    : 'flex-1 px-3 py-2 text-label font-semibold text-gray-500 hover:text-gray-900'
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
                      ? 'flex-1 px-3 py-2 text-label font-semibold text-primary-700 border-b-2 border-primary-500'
                      : 'flex-1 px-3 py-2 text-label font-semibold text-gray-500 hover:text-gray-900'
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
                      ? 'flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2 text-label font-semibold text-primary-700 border-b-2 border-primary-500'
                      : 'flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-2 text-label font-semibold text-gray-500 hover:text-gray-900'
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

          {showIssues && rightTab === 'issues' ? (
            <div
              className="flex-1 overflow-y-auto"
              data-testid="label-issue-panel"
              role="tabpanel"
              id="right-panel-issues"
              aria-labelledby="right-tab-issues"
            >
              {issuesReady ? (
                <IssueThreadPanel rawSn={issueRawSn} mode="worker" />
              ) : (
                // 영상 정보(rawSn)가 없으면 이슈는 조회 대상이 없다. 탭을 감추는 대신 사유를 알린다.
                <p
                  data-testid="label-issue-unavailable"
                  role="status"
                  className="px-3 py-6 text-center text-body-md text-gray-500"
                >
                  이 프레임의 영상 정보를 불러오지 못해 이슈를 이용할 수 없습니다.
                </p>
              )}
            </div>
          ) : showMeta && rightTab === 'meta' ? (
            <div
              className="flex-1 flex flex-col overflow-y-auto"
              data-testid="label-meta-panel"
              role="tabpanel"
              id="right-panel-meta"
              aria-labelledby="right-tab-meta"
            >
              {portalMode ? (
                /*
                 * ★포털 채널은 <b>포털 전용 창구</b>만 부르는 별도 본문을 그린다. 아래 내부 패널을
                 *   그대로 쓰면 내부 창구가 불려 원장 컬럼 쓰기·재검토 표시·관제 통지·동결본
                 *   재동결이 일어나 「원본·데이터마트를 수정하지 않는다(단방향)」가 깨진다.
                 *   이 위반은 「중복 구현을 피하자」는 가장 자연스러운 판단에서 나온다.
                 * ⚠ [폐기] 구 서술 — *"세로 순서(… → 시계열 메타 → 이벤트 어노테이션)는 두 채널이
                 *   같다"*. 2026-09-14 이후 <b>내부 채널만</b> 그 두 패널이 요약 카드 한 장으로
                 *   바뀌었다(전문은 창에서 다룬다). 포털 채널은 두 패널을 그대로 두므로 지금은
                 *   앞의 네 패널까지만 같고 그 뒤가 갈린다. 「같다」를 근거로 한쪽을 다른 쪽에
                 *   맞추지 말 것 — 갈린 것이 확정 사양이다.
                 */
                <PortalWorkMetaTab srcSn={data?.srcSn} rawSn={data?.videoId} />
              ) : (
                <MetaHelpProvider visible={metaHelpVisible}>
              {/* 도움말 토글 — 메타 탭 머리에 하나만 두고 전 구역의 설명문을 한꺼번에 여닫는다.
                  ★기본은 감춤이다(창의 도움말과 반대 — 근거는 `metaHelpPreference`).
                  검수 화면(ReviewMetaPanel)과 같은 부품·같은 자리다. */}
              <div className="flex items-center justify-end border-b border-gray-100 px-4 py-2">
                <MetaHelpToggleButton visible={metaHelpVisible} onToggle={toggleMetaHelp} />
              </div>
              {/* 촬영환경(날씨·시간대·계절) — 영상(rawSn) 단위, 내부 채널만. */}
              <EnvironmentMetaPanel rawSn={data?.videoId} />
              {/* 개인정보(익명·가명·개인정보 포함여부) — 영상(rawSn) 단위. export video 블록 원천. */}
              <VideoPrivacyMetaPanel rawSn={data?.videoId} />
              {/* ★패널 순서는 사양 고정이다: 촬영환경 → 영상축 개인정보 → 프레임 설명 →
                  프레임축 개인정보 → <b>영상 분석 설명 · 이벤트 어노테이션 요약 카드</b> →
                  이관 원문 정보 → 영상 기술 정보. 임의로 바꾸지 말 것.
                  ⚠ [폐기] 구 서술 — *"… → 시계열 메타 → 이벤트 어노테이션"*. 그 두 패널은
                    2026-09-14 에 요약 카드 한 장으로 옮겨갔다. 이 문장이 지시문으로 남아 있으면
                    다음 라운드가 두 패널을 여기에 되살려 그 확정을 되돌린다. */}
              {/* 프레임 설명(NIA image.description) — 작업자 수기 입력. */}
              <FrameDescriptionPanel srcSn={data?.srcSn} />
              {/* 개인정보(익명·가명·개인정보 포함여부) — 프레임(srcSn) 단위. export image 블록 원천. */}
              <FramePrivacyMetaPanel srcSn={data?.srcSn} />
              {/* 영상 분석 설명(저장 축은 시계열 메타) + 이벤트 어노테이션 — 이 자리에는 요약과
                  버튼 하나만 두고 전문은 큰 창에서 다룬다. 폭 288px 패널에서 수백 자 서술과
                  후보 목록을 읽고 쓰는 것이 불가능해서다(SCREEN-005 · 2026-09-14 확정).
                  ★두 패널이 사라진 것이 아니라 창의 두 칸으로 옮겨갔다 — 되돌려 여기에 다시
                    붙이지 말 것. */}
              <TimeseriesAnnotationSummaryCard
                mode="editable"
                windowState={annotationWindow.state}
                eventTypeCd={annotationSummary.eventTypeCd}
                eventTypeName={eventTypeNameOf(
                  videoDetail?.allVrfcEvntTypes,
                  annotationSummary.eventTypeCd,
                )}
                descriptionFirstLine={annotationSummary.descriptionFirstLine}
                reviewStatus={reviewStatusLabel(annotationSummary.reviewStatus)}
                onOpenWindow={annotationWindow.openOrFocus}
              />
              {/* 참고 정보 — 이관 원문(읽기 전용). ★위 여섯 패널 <b>뒤</b>가 사양 고정 자리이며
                  여섯의 나열 순서는 바꾸지 않는다. 이관으로 들어온 영상에서만 스스로 렌더한다
                  (그 밖의 영상에서는 목록이 비어 있는 것이 정상이라 패널째 감춘다). */}
              <ImportedMetaPanel srcSn={data?.srcSn} />
              {/* 참고 정보 — 영상 기술 정보(읽기 전용). ★검수 화면과 <b>같은 부품</b>이다 —
                  사양이 두 화면의 문구를 글자 단위로 같게 정해 두었고, 화면마다 따로 그리면
                  한쪽만 다듬어져 같은 값이 서로 다른 이름·단위로 보인다. 네 항목이 하나도 없는
                  영상에서는 스스로 렌더하지 않는다. */}
              <VideoTechnicalMetaPanel srcSn={data?.srcSn} />
                </MetaHelpProvider>
              )}
            </div>
          ) : (
            /* ★'객체' 탭의 세로 구성은 사양 고정이다 — 객체 목록 → (AI 자동 추적) → 속성 →
               이미지 보정·라벨링 투명도(SCREEN-005 §객체 목록·속성 패널). 객체 목록이 **최상단**이며
               다른 블록이 그 자리를 밀어내지 않는다.

               ★스크롤 계약 (2026-08-18 — 실측 렌더 결함 해소): 구 구조는 목록 블록이
               `flex-1 overflow-hidden` 인데 그 안에 `shrink-0` 인 AI 자동 추적 패널이 함께 들어
               있었다. 그 패널의 자연 높이가 블록 높이를 넘으면 ①`flex-1`(basis 0)인 객체 목록이
               **0px 로 짜부라져 통째로 사라지고** ②넘친 패널 자신이 `overflow-hidden` 에 잘려
               실행 버튼이 아래 '속성' 헤더에 겹쳐 보였다(캡처로 확인된 결함).
               이제 ①탭 자체가 세로 스크롤을 갖고 ②각 블록에 최소 높이를 줘 짜부라짐을 막는다 —
               합이 넘치면 블록이 잘리는 대신 탭이 스크롤된다. 되돌리면 같은 결함이 재발한다. */
            <div
              className="flex-1 flex flex-col overflow-y-auto"
              {...(hasTabs
                ? {
                    role: 'tabpanel',
                    id: 'right-panel-objects',
                    'aria-labelledby': 'right-tab-objects',
                  }
                : {})}
            >
              {/* ★최소 높이가 채널마다 다르다 — 줄 높이가 다르기 때문이다.
                  관제 줄은 한 줄(약 30)이라 160 이면 다섯 줄이 보인다. 포털 줄은 시안대로
                  **두 줄 + 도구**(실측 88)라 같은 160 에서는 한 줄 반밖에 못 보고, 목록이
                  거의 스크롤 상자가 된다. 세 줄이 보이도록 머리 줄(40)까지 더해 304 로 둔다.
                  ⚠ 값을 줄이려면 줄 높이를 함께 재고 줄여야 한다 — 숫자만 되돌리면 다시 막힌다. */}
              <div
                className={cn(
                  'flex flex-1 flex-col overflow-hidden',
                  portalMode ? 'min-h-[304px]' : 'min-h-[160px]',
                )}
              >
                {/* 객체 수 배지 — 헤더에서 폐지되며 이 자리로 이관됐다(SCREEN-005 §헤더 바
                    `[폐기] N개 객체`). 표시 지점은 여기 한 곳뿐이다.
                    ★생김새가 채널마다 갈린다 — 포털은 킷 판 머리 줄(이름 15 · 오른쪽 보조 글 13)이고
                      <b>건수를 배지로 두르지 않는다</b>(부모 포털 규칙 「수치는 배지 없이 글자만」).
                      ⚠ <b>사라지는 정보는 없다</b> — 같은 글이 같은 자리에 서고 이름·시험 후크도 그대로다. */}
                <div
                  className={cn(
                    'shrink-0 px-3 py-2',
                    portalMode
                      ? 'klid-tool-panel-head'
                      : 'flex items-center gap-2 text-label font-semibold text-gray-500 uppercase tracking-wide border-b border-gray-200',
                  )}
                >
                  <span className={portalMode ? 'klid-tool-panel-title' : undefined}>객체 목록</span>
                  <span
                    data-testid="object-count-badge"
                    aria-label="객체 수"
                    className={cn(
                      portalMode
                        ? 'klid-tool-panel-aside'
                        : 'ml-auto rounded-full bg-gray-100 px-2 py-0.5 text-caption font-medium normal-case text-gray-700',
                    )}
                  >
                    {objectCount}개 객체
                  </span>
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
              {/* 온디맨드 자동 추적 — 트랙 편집(삭제·분할·병합)과 같은 자리(객체 목록 바로 아래)에 둔다.
                  포털 채널도 노출한다(SCREEN-029 — 포털 전용 창구). 포털 좌측 도구바의 「AI 자동 추적」
                  버튼이 이 패널 제목으로 포커스를 옮긴다(focusTargetRef — 포털에서만 지정).
                  ⚠ [폐기] 구 서술 — *"포털은 오토라벨·추적 미제공이라 진입 자체를 두지 않는다"*.
                  ★목록 블록 **밖**에 둔다 — 안에 두면 `shrink-0` 인 이 패널이 목록의 높이를 먹어
                    객체 목록이 0px 로 사라진다(위 스크롤 계약 주석 참조). */}
              <AutoTrackPanel
                srcSn={data?.srcSn}
                frames={frames}
                nextSrcSns={nextSrcSns}
                onApply={handleAutoTrackApply}
                disabled={isEditBlocked || isLocked}
                portal={portalMode}
                focusTargetRef={portalMode ? autoTrackFocusRef : undefined}
              />
              <div className="flex min-h-[192px] flex-1 flex-col overflow-hidden border-t border-gray-200">
                {/* ★포털은 킷 판 머리 줄 꼴이다 — 좌측 도구 칸의 묶음 이름과 같은 층(15)으로 선다.
                    ⚠ 블록 사이 선은 <b>두 채널 모두 남긴다</b>. 시안은 여백으로 가르지만 우리 칸은
                      목록과 속성이 각자 스크롤하는 두 칸이라, 선이 없으면 어디까지가 한 묶음인지
                      스크롤 중에 사라진다. */}
                <div
                  className={cn(
                    'shrink-0 px-3 py-2',
                    portalMode
                      ? 'klid-tool-panel-head'
                      : 'text-label font-semibold text-gray-500 uppercase tracking-wide border-b border-gray-200',
                  )}
                >
                  <span className={portalMode ? 'klid-tool-panel-title' : undefined}>속성</span>
                </div>
                <ObjectAttributePanel
                  labels={labels}
                  // 포털이면 속성 라디오를 포털 라디오로 그린다(Host 스타일이 네이티브 라디오를 숨긴다).
                  portalMode={portalMode}
                  // 실측 네이티브 dims 로 좌표 clamp — 미확정 시 undefined → 상한 미적용(하드코딩 1920/1080 제거).
                  imageWidth={frameNaturalSize?.width}
                  imageHeight={frameNaturalSize?.height}
                  // 선택 객체 AI 추적은 내부(INTERNAL) 채널 전용이다 — 포털은 AI 자동 추적 패널로 대신한다
                  // (SCREEN-029 — 같은 목적의 중복 진입을 포털에 두지 않는다). AI 분할 정밀도(segment)는
                  // 두 채널 모두 넘긴다.
                  // ★채널 분기를 **여기서 명시적으로** 건다 (2026-08-18). 구 코드는 분기 없이 항상
                  //   넘기고 패널이 `activeTool === TRACK` 으로 게이트하는 데 기대고 있었는데, 그
                  //   도구 모드 게이트가 사양 정합으로 제거되면서(선택 객체가 있으면 상시 노출)
                  //   포털에서도 실행 버튼이 드러난다. 도구바·단축키 차단은 진입 경로를 막을 뿐
                  //   **패널 노출을 막지 못한다** — 게이트가 사라진 지금은 이 분기가 유일한 차단이다.
                  //   (서버의 포털 전용 SAM2 경로는 이미 제거돼 있어 2중 방어가 된다.)
                  track={
                    portalMode
                      ? undefined
                      : {
                          srcSn: data?.srcSn,
                          nextSrcSns,
                          // R12 — 출력 형태는 선택 객체 형태가 우선(ObjectAttributePanel 의
                          // `shapeToDetectType(target.shape) ?? track.shape`). trackShape 는 예외형태
                          // (MASK/KEYPOINT) 폴백 + 라벨 힌트로만 쓰이며 BBOX/POLYGON 출력을 바꾸지 않는다.
                          shape: trackShape,
                          label: trackLabel,
                          // 미저장 병합 + 부분/전체 안내 토스트. tracked 는 후속 프레임 결과.
                          onTracked: handleTracked,
                        }
                  }
                  // Phase 2 [FE] — AI 분할 도구 활성 시 경계 세밀함 조절. 프리필=시스템 설정값,
                  // 조절 시에만 segmentTolerance 로 올라가 분할 요청에 배선(미조절이면 BE 기본값).
                  // "즉시 그리기"도 같은 섹션에서 토글 — 값은 CanvasShell 의 immediateSegment 로 배선.
                  segment={{
                    defaultTolerance: defaultSimplifyTolerance,
                    tolerance: segmentTolerance,
                    onToleranceChange: setSegmentTolerance,
                    immediateDraw,
                    onImmediateDrawChange: setImmediateDraw,
                  }}
                />
              </div>
              {/* 이미지 조절(밝기/대비/투명도) — 포털 포함 노출. 세션 전용 상태(영속 안 함).
                  ★포털은 킷 판·킷 막대를 입는다(부품만 갈리고 범위·배선·문구는 같다). */}
              <div className="shrink-0 border-t border-gray-200 p-2">
                <ImageAdjustPanel portalMode={portalMode} />
              </div>
            </div>
          )}
        </div>

      </div>

      {/* R6/D4 — 「시작 버전 선택」. 헤더 히스토리 패널이 옮겨 온 자리이며 프레임 버전 이력
          (버전 간 diff · 작업본 diff · 롤백)도 이 안에 있다. 포털은 버전관리 미제공(ADR-013). */}
      {!portalMode && data?.srcSn !== undefined && data.videoId !== undefined && (
        <StartVersionModal
          open={startVersionOpen}
          rawSn={data.videoId}
          srcSn={data.srcSn}
          dirty={isDirty}
          onClose={() => setStartVersionOpen(false)}
          onRevert={handleRevertRequest}
          onLoaded={(loaded) => {
            // ★ 서버는 아무것도 바뀌지 않았다 — 화면에만 올린다. 저장을 눌러야 확정된다.
            const draft = toLoadedDraft(loaded);
            setLoadedDraft(draft);
            // 현재 프레임 캔버스를 그 회차 내용으로 교체한다(세트에 그 프레임이 있을 때만).
            const entry = draft.frames.find((f) => f.srcSn === data.srcSn);
            if (entry) {
              setLabels(entry.items.map(normalizeLabel));
              // 폐기 전환은 서버값과 다를 때만 미저장 변경으로 남긴다(같으면 거짓 경고가 된다).
              setDiscardDraft(
                entry.dscdYn === (data.dscdYn ?? DscdYn.N) ? null : (entry.dscdYn as DscdYn),
              );
            }
            const unresolved = unresolvedFrameCount(draft);
            pushToast({
              variant: 'info',
              message:
                `v${draft.version} 상태를 불러왔습니다 (${draft.frames.length}개 프레임). 저장해야 확정됩니다.`
                + (unresolved > 0 ? ` 기록이 없어 그대로 둔 프레임 ${unresolved}개.` : ''),
            });
          }}
        />
      )}

      {/* 「영상 분석 설명 · 이벤트 어노테이션」 창 — 비모달이라 창이 떠 있어도 캔버스·타임라인·
          우측 탭을 그대로 조작한다(근거로 쓸 프레임·객체를 화면에서 골라야 하기 때문이다).
          ★닫으면 언마운트된다 — 다시 열면 서버값으로 새로 시작한다(그래서 미저장 닫기는 확인을
            거친다). 접힘·근거 지정 중에는 마운트를 유지해 입력값을 잃지 않는다. */}
      {!portalMode && annotationWindow.mounted && (
        <AnnotationWindow
          mode="editable"
          rawSn={data?.videoId}
          srcSn={data?.srcSn}
          state={annotationWindow.state}
          focusRequestedAt={annotationWindow.focusRequestedAt}
          onClose={annotationWindow.close}
          onFold={annotationWindow.fold}
          onExpand={annotationWindow.expand}
          onPickingChange={annotationWindow.setPicking}
          // 이벤트 분류 이름은 영상 상세의 전체 유형 목록에서 찾는다 — 관리 화면 조회 경로는
          // 검수자 전용이라 작업자에게 403 이다(API-043).
          eventTypes={videoDetail?.allVrfcEvntTypes}
          frameIndex={frameIdx + 1}
          frameTotal={frames.length}
        />
      )}

      {/* R4·R5 — 폐기 프레임 저장 확인. 취소해도 편집 상태는 그대로 남는다(동의 없이 버리지 않는다). */}
      <DiscardSaveConfirmModal
        open={discardSaveConfirmOpen}
        summary={discardSaveSummary}
        onConfirm={handleConfirmDiscardSave}
        onCancel={() => setDiscardSaveConfirmOpen(false)}
      />

      {/* C-ISSUE-21 — 저장 충돌(409) 안내. 작업 내용을 임의로 버리지 않고 사용자가 선택한다. */}
      <ConfirmDialog
        open={saveConflictMessage !== null}
        title="다른 사용자가 먼저 저장했습니다"
        description={`${saveConflictMessage ?? ''} 최신 라벨을 불러오면 저장하지 않은 변경은 사라집니다. 작업 내용을 남기려면 '내 작업 유지'를 선택한 뒤 필요한 부분을 다시 확인하세요.`}
        confirmLabel="최신 라벨 불러오기"
        cancelLabel="내 작업 유지"
        variant="danger"
        onConfirm={handleReloadAfterConflict}
        onCancel={() => setSaveConflictMessage(null)}
      />

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

      {/* 하단 — 썸네일 strip + 슬라이더
          ★높이가 채널마다 다르다. 관제는 종전 고정 60+60 이고, <b>포털은 내용 높이</b>다 —
            킷 낱장이 76×16:9 에 번호 줄을 그림 «아래» 두고 킷 재생 줄의 누르는 자리가 44 라,
            60 에 밀어 넣으면 번호와 막대가 잘린다(시안도 이 줄을 내용 높이로 둔다). */}
      <div
        className={cn('shrink-0 flex flex-col border-t border-gray-200')}
        style={portalMode ? undefined : { height: 120 }}
      >
        <div style={portalMode ? undefined : { height: 60 }}>
          <FrameFilmstrip
            frames={frames}
            currentIndex={frameIdx}
            onSelect={requestJumpTo}
            inquirySrcSns={inquirySrcSns}
            savedSrcSns={savedSrcSns}
            discardedSrcSns={discardedSrcSns}
            portalMode={portalMode}
            uploadSource={uploadSource}
            disabled={isEditBlocked}
          />
        </div>
        <div
          className={portalMode ? 'border-t border-gray-200' : undefined}
          style={portalMode ? undefined : { height: 60 }}
        >
          <DarkFrameSlider
            currentIndex={frameIdx}
            totalFrames={Math.max(frames.length, 1)}
            onSelect={requestJumpTo}
            dirtyGuard={dirtyCount > 0}
            disabled={isEditBlocked}
            portalMode={portalMode}
          />
        </div>
      </div>
    </div>
  );
}
