// @design SCREEN-019 검수 상세 화면 — 확정 고충실 디자인(design-main) 정합 레이아웃.
//
// 레이아웃:
//   ┌─ ReviewHeader (h-16, light) — 영상 메타 + 상태 배지 + 승인/반려          [2열 span]
//   ├─ 상단 프레임 이동 바 — 처음/이전/번호 입력/다음/마지막 + **전폭** 위치 슬라이더 [2열 span]
//   └─ 본문 (1fr): [캔버스 컬럼] [aside (360px)]
//        캔버스 컬럼 = 프레임 썸네일 스트립(auto) + 캔버스(남은 높이 전부)
//
// ★스트립은 **캔버스 컬럼 안**이다(디자인 `.rv-main` = filmstrip + canvas-area). 전폭으로 두면
//   우측 패널이 스트립 아래에서 시작해 패널의 세로 공간을 스트립 높이만큼 잃는다 — 디자인은
//   우측 패널이 본문 상단부터 시작한다.
// ★스트립은 자기 높이만 갖고 캔버스가 남은 공간을 전부 차지한다(컬럼은 flex-col, 캔버스만 flex-1).
//   스트립에 flex-1 을 주면 썸네일 개수·스크롤 내용에 따라 캔버스 높이가 따라 흔들린다.
//
// 내부 영역(캔버스/객체 트리/타임라인/메모)은 Phase 3·4·6 에서 채움.
//
// UI/UX §4-9 정합:
// - 캔버스는 읽기 전용 (좌표 마커 절대 미사용 — 회귀 방지)
// - 이슈는 텍스트 카드만으로 표현 (프레임 단위 누적)
//
// 보안:
// - reviewId path 파라미터 number 변환 (NaN 가드).
// - REVIEWER 권한은 라우터 RoleGuard에서 검증.
// - 라벨 데이터 접근(IDOR)은 BE에서 본인 배정 검증.

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Lock } from 'lucide-react';

import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ErrorState } from '@/components/common/ErrorState';
import { Spinner } from '@/components/common/Spinner';
// 프레임 이동 컨트롤은 라벨링 화면과 **같은 컴포넌트를 공유**한다(UI-052) — 사양이 두 화면에
// 동일한 구성(처음/이전/번호 입력/다음/마지막 + 슬라이더)을 요구하므로 복제하면 한쪽만 고쳐진다.
// 검수 화면은 읽기 전용이라 미저장 가드가 없을 뿐, 컨트롤 계약은 동일하다.
import { FrameNavigator } from '@/features/label/components/FrameNavigator';
import { AnnotationWindow } from '@/features/label/components/AnnotationWindow';
import { missingFrameNoticeText } from '@/features/label/components/annotationWording';
import { useAnnotationWindow } from '@/features/label/hooks/useAnnotationWindow';
import { useVideoDetail } from '@/features/video/hooks/useVideoDetail';
import { FrameTimeline } from '@/features/review/components/FrameTimeline';
import { IssueThreadPanel } from '@/features/review/components/IssueThreadPanel';
import { LabelCanvas } from '@/features/review/components/LabelCanvas';
import { ObjectAttributesPanel } from '@/features/review/components/ObjectAttributesPanel';
import { ObjectListPanel } from '@/features/review/components/ObjectListPanel';
import { RejectModal } from '@/features/review/components/RejectModal';
import { ReviewHeader } from '@/features/review/components/ReviewHeader';
import { ReviewMemoPanel } from '@/features/review/components/ReviewMemoPanel';
import { ReviewMetaPanel } from '@/features/review/components/ReviewMetaPanel';
import {
  ReviewSidePanelTabs,
  reviewPanelId,
  reviewTabId,
  type ReviewSideTab,
} from '@/features/review/components/ReviewSidePanelTabs';
import { useIssueThreads } from '@/features/review/hooks/useIssueThreads';
import { useReview } from '@/features/review/hooks/useReview';
import {
  useApproveReview,
  useStartReview,
} from '@/features/review/hooks/useReviewActions';
import { useReviewFrames } from '@/features/review/hooks/useReviewFrames';
import {
  useReviewSelectionStore,
  type PendingIssue,
} from '@/features/review/store/useReviewSelectionStore';
import { ISSUE_STATUS, ISSUE_TYPE } from '@/features/review/types';
import { ApiError } from '@/lib/api/errors';
import { extractBeMessage } from '@/lib/api/extractBeMessage';
import { useUiStore } from '@/stores/useUiStore';

/**
 * 검수 의견 + pending 이슈를 반려 사유 문자열로 합성한다.
 * 형식 (사용자 입력 reason 을 우선):
 *
 *   {사용자 입력}
 *
 *   [전체 의견]
 *   {reviewComment}
 *
 *   [이슈 N건]
 *   - {text} (#{labelId})
 *   - ...
 */
export function composeRejectReason(
  userReason: string,
  reviewComment: string,
  pendingIssues: PendingIssue[],
): string {
  const parts: string[] = [userReason.trim()];
  if (reviewComment.trim()) {
    parts.push(`[전체 의견]\n${reviewComment.trim()}`);
  }
  if (pendingIssues.length > 0) {
    const bullets = pendingIssues
      .map((p) => {
        const label = p.labelId != null ? ` (#${p.labelId})` : '';
        return `- ${p.text}${label}`;
      })
      .join('\n');
    parts.push(`[이슈 ${pendingIssues.length}건]\n${bullets}`);
  }
  return parts.filter((s) => s.length > 0).join('\n\n');
}

/**
 * SCR-REVIEW-002 검수 화면 (3분할 — Phase 2).
 */
export function ReviewPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const numericId = id ? Number(id) : NaN;
  const reviewId = Number.isFinite(numericId) ? numericId : undefined;

  const [rejectOpen, setRejectOpen] = useState(false);
  const [approveConfirmOpen, setApproveConfirmOpen] = useState(false);
  /**
   * D-ISSUE-04 / H6 — 라벨 0건(negative sample) 승인 재확인 다이얼로그. BE 가 409 로 거부했을 때만
   * 열리며, 여기서 확인해야 noLabelConfirmed=true 로 재요청한다(플래그 상시 전송 방지).
   */
  const [noLabelConfirmOpen, setNoLabelConfirmOpen] = useState(false);
  const [didStart, setDidStart] = useState(false);
  /**
   * 우측 패널 탭. 기본은 '객체' — 사양의 탭 옵션 순서 첫 항목이자 라벨링 화면 우측 패널의
   * 기본 탭과 같다(두 화면의 진입 화면이 갈리면 검수자가 매번 다시 찾는다).
   *
   * ★탭은 표시만 전환한다 — 객체 선택(selectedLabelId)·검수 의견·pending 이슈는 모두
   *   `useReviewSelectionStore` 가 소유하므로 탭을 옮겨 패널이 내려가도 값이 유지되고,
   *   캔버스와의 양방향 동기화도 끊기지 않는다.
   */
  const [sideTab, setSideTab] = useState<ReviewSideTab>('objects');

  // Phase 5·6 — store 구독 (selector 패턴, rules/state-management.md).
  const currentFrameIdx = useReviewSelectionStore((s) => s.currentFrameIdx);
  const setCurrentFrameIdx = useReviewSelectionStore(
    (s) => s.setCurrentFrameIdx,
  );
  const clearSelection = useReviewSelectionStore((s) => s.clear);
  // Phase 6 — reject 시 사유 합성을 위해 현재 값 스냅샷.
  const reviewComment = useReviewSelectionStore((s) => s.reviewComment);
  const pendingIssues = useReviewSelectionStore((s) => s.pendingIssues);

  const pushToast = useUiStore((s) => s.pushToast);
  const { data: review, isLoading, error } = useReview(reviewId);
  const { data: frameList, isLoading: framesLoading } = useReviewFrames(
    review?.videoId,
  );
  // @design SCREEN-019 — 이 화면에서 `useReviewIssues`(검수 단위 이슈 목록)를 부르지 않는다.
  // 유일한 소비처가 `ReviewMemoPanel` 의 서버 이슈 목록이었고, 그 목록이 「이슈」 탭과 중복이라
  // 제거됐다. 호출만 남기면 화면이 쓰지 않는 요청이 매 진입마다 나간다.
  // ⚠ 훅 자체(`hooks/useReviewIssues`)는 지우지 않았다 — `IssueSidebar` 가 여전히 쓴다.
  // ⚠ 「이슈」 탭 배지의 미해소 건수는 이 훅이 아니라 아래 `useIssueThreads` 로 계산된다
  //   (`unresolvedInquiries`) — 이 호출을 지워도 배지는 그대로다.
  //
  // R1 — 영상 단위 이슈 스레드로 프레임 상태색 srcSn 집합 산출.
  // v2 반려는 영상 단위(REJECTION.srcSn=null)라 프레임 매핑 불가 → 주황(반려) 프레임색 미대상.
  // 미해소이슈(빨강)·저장(연두)·현재(강조)만 반영한다.
  const { data: issueThreads } = useIssueThreads(review?.videoId);

  // 미해소(RESOLVED 아님) 문의(INQUIRY) 프레임 srcSn → 빨강.
  const inquirySrcSns = useMemo(
    () =>
      new Set(
        (issueThreads ?? [])
          .filter(
            (t) =>
              t.issueTypeCd === ISSUE_TYPE.INQUIRY &&
              t.issueSttsCd !== ISSUE_STATUS.RESOLVED &&
              t.srcSn != null,
          )
          .map((t) => t.srcSn as number),
      ),
    [issueThreads],
  );
  // '이슈' 탭 배지용 미해소 문의 건수. `IssueThreadPanel` 이 헤더에 같은 수치를 표시하는데,
  // 탭으로 접으면 다른 탭을 보는 동안 그 신호가 사라지므로 탭 배지로 되살린다.
  // ★같은 `useIssueThreads(videoId)` 결과를 재사용한다 — 배지 때문에 요청을 추가하지 않는다.
  const unresolvedInquiries = useMemo(
    () =>
      (issueThreads ?? []).filter(
        (t) =>
          t.issueTypeCd === ISSUE_TYPE.INQUIRY &&
          t.issueSttsCd !== ISSUE_STATUS.RESOLVED,
      ).length,
    [issueThreads],
  );
  // 라벨 저장된 프레임 srcSn → 연두(부가). 검수 프레임엔 hasLabel 없어 labels 로 판정.
  const savedSrcSns = useMemo(
    () =>
      new Set(
        (frameList?.frames ?? [])
          .filter((f) => f.labels.length > 0)
          .map((f) => f.srcSn),
      ),
    [frameList],
  );

  const { mutate: doStart } = useStartReview({
    onSuccess: () => setDidStart(true),
  });

  const { mutate: doApprove, isPending: approving } = useApproveReview({
    onSuccess: () => {
      setNoLabelConfirmOpen(false);
      pushToast({ variant: 'success', message: '승인 완료' });
      navigate('/review');
    },
    onError: (err) => {
      // 라벨 0건 영상은 BE 가 409(REVIEW_NO_LABEL) 로 막는다 — 실패 토스트로 끝내면 검수자는 반려밖에
      // 못 하고 더미 라벨을 넣도록 유도된다. 확인 다이얼로그를 띄워 '라벨 없음'을 명시 확인받는다.
      //
      // DEV_FIX H12(H6) — 상태코드(409)만 보고 분기하면 안 된다. 승인 경로의 409 에는 <b>동시 승인 충돌</b>
      //   ("다른 검수자가 먼저 처리했습니다")과 <b>상태 전이 불가</b>도 포함되므로, 라벨이 있는 영상의
      //   낙관적 잠금 충돌에도 "라벨이 없는 영상입니다" 다이얼로그가 떴다. 사용자가 확인을 누르면
      //   noLabelConfirmed=true 재요청 → BE 400 → "승인 실패" 로 끝나는 오도 경로가 된다.
      //   errorCode 로 사유를 구분한다(메시지 문자열 매칭 금지).
      if (err instanceof ApiError && err.errorCode === 'REVIEW_NO_LABEL') {
        setNoLabelConfirmOpen(true);
        return;
      }
      pushToast({ variant: 'error', message: extractBeMessage(err, '승인 실패') });
    },
  });

  // 검수 화면 진입 시 자동으로 startReview 호출 (REVIEW_PENDING → REVIEWING)
  useEffect(() => {
    if (review && review.status === 'REVIEW_PENDING' && !didStart) {
      doStart(review.id);
    }
  }, [review, didStart, doStart]);

  // Phase 5 — frames 로드 완료 시 currentFrameIdx 가 범위 밖이면 0 으로 reset.
  const frames = frameList?.frames;
  useEffect(() => {
    if (!frames || frames.length === 0) return;
    if (currentFrameIdx < 0 || currentFrameIdx >= frames.length) {
      setCurrentFrameIdx(0);
    }
  }, [frames, currentFrameIdx, setCurrentFrameIdx]);

  // Phase 5 — 페이지 언마운트 시 store reset (다른 검수 진입 시 잔존 상태 방지).
  useEffect(() => {
    return () => {
      clearSelection();
      setCurrentFrameIdx(0);
    };
  }, [clearSelection, setCurrentFrameIdx]);

  /**
   * 프레임 이동 단일 경로 — 상단 이동 바(처음/이전/번호 입력/다음/마지막·슬라이더)와
   * 캔버스 위 썸네일 스트립이 모두 이 하나를 부른다. 진입점마다 범위 보정을 따로 두면 한 곳이 샌다.
   * ★스트립을 접어도 이 경로는 그대로다 — 접힘은 표시 여부일 뿐 이동 수단을 갈라놓지 않는다.
   */
  const frameCount = frames?.length ?? 0;
  const handleGoToFrame = useCallback(
    (index: number) => {
      if (frameCount === 0) return;
      setCurrentFrameIdx(Math.min(frameCount - 1, Math.max(0, index)));
    },
    [frameCount, setCurrentFrameIdx],
  );

  /**
   * 「영상 분석 설명 · 이벤트 어노테이션」 창 — 메타 탭의 요약 카드가 연다(읽기 전용).
   * [@design SCREEN-019] [@design UI-156] [@design UI-157]
   */
  const annotationWindow = useAnnotationWindow();
  // 이벤트 분류 <b>이름</b> 조달 — 유형 이름을 주는 관리 조회 경로와 달리 영상 상세는 이 화면의
  // 권한으로 부를 수 있다(API-043). 이름을 못 찾으면 코드만 보인다.
  const { data: videoDetail } = useVideoDetail(review?.videoId ?? null);
  /** 이 영상이 실제로 가진 프레임 번호 — 근거에 적힌 「없는 번호」 판정에 쓴다. */
  const frameSrcSns = useMemo(
    () => new Set((frames ?? []).map((f) => f.srcSn)),
    [frames],
  );

  /**
   * 근거에 적힌 프레임 번호로 이동한다. 이동했으면 true(그때만 창이 접힌다).
   *
   * ★없는 번호는 <b>이동하지 않고</b> 알림만 한다 — 근거는 사람이 적은 값이라 이 영상에 없는
   * 번호가 들어 있을 수 있다. 그때 아무 프레임으로나 옮기면 검수자가 엉뚱한 화면을 근거로 본다.
   */
  const handleJumpToEvidenceFrame = useCallback(
    (frameId: number): boolean => {
      const index = (frames ?? []).findIndex((f) => f.srcSn === frameId);
      if (index < 0) {
        pushToast({ variant: 'warning', message: missingFrameNoticeText(frameId) });
        return false;
      }
      handleGoToFrame(index);
      return true;
    },
    [frames, handleGoToFrame, pushToast],
  );

  const handleClose = useCallback(() => {
    navigate('/review');
  }, [navigate]);

  const handleApproveClick = useCallback(() => {
    setApproveConfirmOpen(true);
  }, []);

  const handleRejectClick = useCallback(() => {
    setRejectOpen(true);
  }, []);

  const handleApproveConfirm = useCallback(() => {
    if (!review) return;
    setApproveConfirmOpen(false);
    doApprove({ reviewId: review.id });
  }, [doApprove, review]);

  /**
   * H6 — ' 라벨 없음' 명시 확인 후 재승인. 이 경로에서만 noLabelConfirmed 를 싣는다(기본값 아님).
   * BE 는 이 승인을 통합 이벤트 로그(APPROVE + 사유)에 남겨 누가 언제 확인했는지 감사 가능하게 한다.
   */
  const handleNoLabelApprove = useCallback(() => {
    if (!review) return;
    doApprove({ reviewId: review.id, body: { noLabelConfirmed: true } });
  }, [doApprove, review]);

  if (Number.isNaN(numericId)) {
    return (
      <div
        className="fixed inset-0 z-50 flex items-center justify-center bg-gray-50 text-gray-900"
        data-testid="review-page"
      >
        <ErrorState title="잘못된 검수 ID" />
      </div>
    );
  }

  if (isLoading) {
    return (
      <div
        className="fixed inset-0 z-50 flex items-center justify-center bg-gray-50 text-gray-900"
        data-testid="review-page"
      >
        <div
          className="flex flex-col items-center gap-3"
          data-testid="review-page-loading"
        >
          <Spinner label="검수 로딩" />
          <p className="text-body-md text-gray-600">검수 정보 로드 중...</p>
        </div>
      </div>
    );
  }

  if (error || !review) {
    return (
      <div
        className="fixed inset-0 z-50 flex items-center justify-center bg-gray-50 text-gray-900"
        data-testid="review-page"
      >
        <ErrorState title="검수 정보를 불러올 수 없습니다" />
      </div>
    );
  }

  return (
    <div
      className="fixed inset-0 z-50 grid overflow-hidden bg-gray-50"
      data-testid="review-page"
      style={{
        gridTemplateColumns: '1fr 360px',
        // 헤더(64px) / 프레임 이동 바(auto) / 본문 = 캔버스 컬럼 + 우측 패널(1fr)
        gridTemplateRows: '64px auto 1fr',
      }}
    >
      {/* Header — col-span-2 */}
      <div style={{ gridColumn: '1 / span 2' }}>
        {/* 승인·반려 진입점은 헤더가 단독으로 담당한다(하단 액션 바 없음) —
            같은 액션을 두 곳에 두면 상태별 활성 조건·진행 표시 판정이 갈린다. */}
        <ReviewHeader
          videoId={review.videoId}
          cctvName={review.cctvName}
          workerName={review.workerName}
          submittedAt={review.submittedAt}
          status={review.status}
          needsRecheck={review.needsRecheck}
          isApproving={approving}
          onClose={handleClose}
          onApprove={handleApproveClick}
          onReject={handleRejectClick}
        />
      </div>

      {/* 상단 프레임 이동 바 — 헤더 바로 아래의 별도 상단바(col-span-2).
          처음/이전/프레임 번호 입력/다음/마지막 이동 컨트롤 + 위치 슬라이더로 구성되며,
          현재 프레임 위치(현재 번호 / 전체 개수)를 이 영역에서 표시한다.
          ★위치 표시는 헤더가 아니라 여기 한 곳이다 — 두 곳에 두면 어느 쪽이 진실인지 갈린다.
          아래 썸네일 스트립과 같은 이동 경로(handleGoToFrame)로 수렴한다. */}
      <nav
        style={{ gridColumn: '1 / span 2' }}
        className="flex h-11 shrink-0 items-center border-b border-gray-200 bg-white px-3"
        data-testid="review-frame-nav-bar"
        aria-label="프레임 이동 바"
      >
        {/* ★슬라이더는 전폭이다(디자인 `.rv-slider-wrap { flex:1 }`) — 이동 컨트롤이 왼쪽에 서고
            슬라이더가 남은 가로를 전부 채운다. 짧은 슬라이더는 128 프레임대 영상에서 한 픽셀이
            여러 프레임을 덮어 스크럽 정밀도가 떨어진다. */}
        <FrameNavigator
          frameIndex={currentFrameIdx}
          frameCount={frameCount}
          onRequestGoTo={handleGoToFrame}
          showSlider
          sliderFill
        />
      </nav>

      {/* 캔버스 컬럼 — 썸네일 스트립(자기 높이) + 캔버스(남은 높이). 우측 패널과 **나란한 열**이라
          패널이 본문 상단부터 시작한다(디자인 `.rv-main`). */}
      <div
        className="flex min-h-0 min-w-0 flex-col overflow-hidden"
        data-testid="review-main-column"
      >
        {/* 프레임 썸네일 스트립 — 캔버스 바로 위. 접기/펼치기는 스트립이 자체 보유하며 기본은
            펼침이다. 접혀도 토글·카운터는 남고, 이동 경로는 상단 이동 바와 같은 handleGoToFrame
            하나로 수렴한다(표면이 갈리지 않는다). */}
        <div className="shrink-0" data-testid="review-timeline-placeholder">
          <FrameTimeline
            frames={frameList?.frames ?? []}
            currentFrameIdx={currentFrameIdx}
            onSelect={handleGoToFrame}
            inquirySrcSns={inquirySrcSns}
            savedSrcSns={savedSrcSns}
          />
        </div>

        {/* Main canvas — Konva 기반 LabelCanvas 마운트.
            배경(bg-gray-200)은 UI 크롬이 아니라 영상 프레임을 얹는 미디어 매트다. 순백이면 어두운
            CCTV 프레임과 대비가 극심해 눈부심이 생기므로 중립 회색을 유지한다(라벨링 캔버스와 동일값). */}
        <main
          className="relative flex min-h-0 flex-1 items-center justify-center overflow-hidden bg-gray-200"
          data-testid="review-canvas-readonly"
          aria-label="검수 캔버스 (읽기 전용)"
        >
          {/* '읽기 전용' 배지 — **미디어 매트 위 오버레이**라 어두운 pill 이 정당하다.
              디자인 시스템: "앱에 다크 표면은 없다. 유일한 예외는 영상 프레임을 얹는 미디어 매트와
              그 위 오버레이뿐"(2026-08-06 다크 테마 폐지 확정). 라벨링 캔버스의 상태 오버레이
              (`canvas-rotation-notice` 등)가 쓰는 bg-black/70 + text-white 관례를 그대로 따른다.
              구 앰버(bg-warning) 배색은 디자인·관례 양쪽과 어긋나 되돌린다. */}
          <div
            className="pointer-events-none absolute left-3 top-3 z-10 inline-flex items-center gap-1.5 rounded-full bg-black/70 px-2.5 py-1 text-caption font-medium text-white"
            data-testid="review-readonly-badge"
          >
            <Lock className="h-3.5 w-3.5" aria-hidden="true" />
            읽기 전용
          </div>
          {/* 프레임 목록 로딩 중에는 LabelCanvas 가 스피너를 노출한다(loading prop) — 로드 전
              "프레임이 없습니다" 오표시(로딩=빈 상태 혼동)를 제거한다. */}
          <LabelCanvas
            frame={frameList?.frames?.[currentFrameIdx] ?? null}
            loading={framesLoading}
          />
        </main>
      </div>

      {/* Aside — 우측 패널. 사양대로 객체 / 메타 / 이슈 3개 탭으로 전환한다.
          ★스크롤은 탭 목록이 아니라 각 tabpanel 이 갖는다(`aside` 는 overflow-hidden) —
            aside 전체가 스크롤되면 탭 목록이 위로 밀려 나가 다른 탭으로 갈 수단이 사라진다. */}
      <aside
        className="flex flex-col overflow-hidden border-l border-gray-200 bg-white text-gray-900"
        data-testid="review-aside"
        aria-label="검수 우측 패널"
      >
        <ReviewSidePanelTabs
          value={sideTab}
          onChange={setSideTab}
          unresolvedInquiries={unresolvedInquiries}
        />

        {sideTab === 'objects' && (
          /* '객체' 탭 — 카테고리 트리 + 선택 객체 속성 + 검수 메모(이 화면 전용). */
          <div
            className="flex-1 overflow-y-auto"
            role="tabpanel"
            id={reviewPanelId('objects')}
            aria-labelledby={reviewTabId('objects')}
            data-testid="review-panel-objects"
          >
            {/* ★구역 이름은 「카테고리」다 — 이 트리가 라벨을 <b>카테고리로 묶어</b> 보이기
                때문이고, 건수는 옆의 배지가 말한다. 구 이름 「객체 목록」은 어느 사양에도 없다. */}
            <section
              className="border-b border-gray-200 p-3"
              aria-label="카테고리"
              data-testid="review-aside-object-list"
            >
              <div className="mb-2 flex items-center gap-2">
                <h2 className="text-label font-semibold uppercase tracking-wide text-gray-500">
                  카테고리
                </h2>
                {/* 숫자만 두지 않고 <b>무엇의 건수인지</b> 함께 적는다 — 「5」만 있으면 카테고리
                    수인지 객체 수인지 알 수 없다(여기서는 현재 프레임의 객체 수다). */}
                <span
                  data-testid="review-object-count-badge"
                  className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-caption font-medium text-gray-700"
                >
                  객체 {frameList?.frames?.[currentFrameIdx]?.labels?.length ?? 0}건
                </span>
              </div>
              <ObjectListPanel labels={frameList?.frames?.[currentFrameIdx]?.labels ?? []} />
            </section>

            <section
              className="border-b border-gray-200 p-3"
              aria-label="선택 객체 속성"
              data-testid="review-aside-attributes"
            >
              <h2 className="mb-2 text-label font-semibold uppercase tracking-wide text-gray-500">
                선택 객체 속성
              </h2>
              <ObjectAttributesPanel
                labels={frameList?.frames?.[currentFrameIdx]?.labels ?? []}
              />
            </section>

            {/* @design SCREEN-019 — 이 패널에는 **반려 사유 초안만** 넘긴다. 서버 등록 이슈를
                함께 넘기면 방금 낸 반려 사유가 스레드로 되돌아와 「이슈」 탭과 이 목록에 동시에
                보인다(이중 표시). 서버 이슈의 열람·댓글·해소는 「이슈」 탭이 단독 담당한다. */}
            <ReviewMemoPanel videoId={review.videoId} />
          </div>
        )}

        {sideTab === 'meta' && (
          /* '메타' 탭 — event_annotation(영상 단위) + 시계열 메타(현재 프레임) + 영상 기술 정보.
             읽기 전용(편집·승인/반려 없음). 확정은 영상 승인 시 자동 동결에 위임. */
          <div
            className="flex-1 overflow-y-auto"
            role="tabpanel"
            id={reviewPanelId('meta')}
            aria-labelledby={reviewTabId('meta')}
            data-testid="review-panel-meta"
          >
            <ReviewMetaPanel
              rawSn={review.videoId}
              srcSn={frameList?.frames?.[currentFrameIdx]?.srcSn}
              windowState={annotationWindow.state}
              onOpenWindow={annotationWindow.openOrFocus}
              allVrfcEvntTypes={videoDetail?.allVrfcEvntTypes}
            />
          </div>
        )}

        {sideTab === 'issues' && (
          /* '이슈' 탭 — 검수자↔작업자 통합 이슈 스레드(반려 이력 + 문의). 댓글·해소.
             '객체' 탭의 검수 메모(이 화면 전용, 서버 미연동)와는 별개 기능이다. */
          <div
            className="flex-1 overflow-y-auto"
            role="tabpanel"
            id={reviewPanelId('issues')}
            aria-labelledby={reviewTabId('issues')}
            data-testid="review-panel-issues"
          >
            <section aria-label="문의 스레드" data-testid="review-issue-thread-section">
              <IssueThreadPanel rawSn={review.videoId} mode="reviewer" />
            </section>
          </div>
        )}
      </aside>

      {/* 「영상 분석 설명 · 이벤트 어노테이션」 창(읽기 전용) — 비모달이라 창이 떠 있어도 캔버스·
          프레임 이동·우측 탭을 그대로 조작한다. 근거의 프레임 번호를 누르면 뒤 화면이 그 프레임으로
          이동하고 창은 접히며, 접힘 띠의 「펼치기」로 되돌린다. */}
      {annotationWindow.mounted && (
        <AnnotationWindow
          mode="readOnly"
          rawSn={review.videoId}
          srcSn={frameList?.frames?.[currentFrameIdx]?.srcSn}
          state={annotationWindow.state}
          focusRequestedAt={annotationWindow.focusRequestedAt}
          onClose={annotationWindow.close}
          onFold={annotationWindow.fold}
          onExpand={annotationWindow.expand}
          onPickingChange={annotationWindow.setPicking}
          eventTypes={videoDetail?.allVrfcEvntTypes}
          availableFrameIds={frameSrcSns}
          onJumpToFrame={handleJumpToEvidenceFrame}
        />
      )}

      <RejectModal
        reviewId={review.id}
        open={rejectOpen}
        onClose={() => setRejectOpen(false)}
        onSuccess={() => navigate('/review')}
        composeReason={(userReason) =>
          composeRejectReason(userReason, reviewComment, pendingIssues)
        }
      />

      {/* H6 — negative sample(라벨 0건) 승인 확인. 검수자가 명시 동의한 경우에만 재요청한다. */}
      <ConfirmDialog
        open={noLabelConfirmOpen}
        title="라벨이 없는 영상입니다"
        description="이 영상에는 저장된 라벨이 없습니다. 객체가 실제로 없는 영상(정상)이면 그대로 승인할 수 있습니다. 라벨이 누락된 것이라면 승인하지 말고 반려하세요. 확인 승인 시 '라벨 없음 확인' 사실이 작업 이력에 기록됩니다."
        confirmLabel="라벨 없음 확인 후 승인"
        cancelLabel="취소"
        variant="danger"
        loading={approving}
        onConfirm={handleNoLabelApprove}
        onCancel={() => setNoLabelConfirmOpen(false)}
      />

      <ConfirmDialog
        open={approveConfirmOpen}
        title="승인 확정"
        description="이 검수를 승인 처리하시겠습니까? 작업은 완료(COMPLETED) 상태로 전이됩니다."
        confirmLabel="승인 확정"
        variant="primary"
        loading={approving}
        onConfirm={handleApproveConfirm}
        onCancel={() => setApproveConfirmOpen(false)}
      />
    </div>
  );
}
