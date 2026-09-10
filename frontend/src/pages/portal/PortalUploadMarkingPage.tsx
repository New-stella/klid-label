/**
 * 포털 업로드 영상 마킹 화면. [@design SCREEN-045] [@design NAV-002]
 * [@design API-238] [@design API-239] [@design API-240] [@design API-241] [@design API-140]
 *
 * <h3>이 화면이 하는 일</h3>
 * 포털 회원이 본인이 올린 영상에서 <b>프레임을 어느 지점에서 뽑을지</b> 정한다. 마킹을 완료하면
 * 그 지점으로 추출이 시작되고, 추출이 끝나면 자산이 준비 완료가 되어 라벨링으로 넘어간다.
 * 마킹을 거치지 않은 자산은 프레임을 갖지 않는다.
 *
 * <h3>관제 채널 마킹에서 가져오지 않은 것</h3>
 * 가려진 사본 재생 · 마스킹 누락 신고 · 검증 질문 선택 · 외부 위탁 기동 · 작업 배정·검수 연계.
 * 이 경로의 자산에는 비식별 단계가 없어 <b>사용자가 올린 원본</b>을 그대로 재생하고, 관제 인입
 * 이벤트 유형이 없어 고를 질문도 없으며, 외부 시계열 위탁도 검수도 없다.
 *
 * <h3>진입 차단은 세 축이고 하나로 합치지 않는다</h3>
 * 소유 · 업로드 완료 · 자산 종류. 차단이라는 결과가 같아도 사유와 문구가 다르다.
 * ⚠ 자산 상세를 <b>아직 받는 중이면 어느 축도 차단하지 않는다</b> — 확정되지 않은 값으로 차단하면
 *   불러오는 동안의 깜빡임이 정상 마킹을 막는다.
 * ⚠ 이미 마킹을 저장한 자산은 이 축이 다루지 않는다 — 저장된 지점을 확인할 수 있어야 하므로
 *   편집기를 그대로 그리되 <b>저장만</b> 막는다.
 *
 * <h3>완료는 되돌릴 수 없다</h3>
 * 저장이 곧 추출의 시작이고 재마킹은 제공하지 않는다. 그래서 확인 단계를 반드시 거치며, 저장
 * 요청이 나가는 자리는 <b>그 확인 창의 확인 버튼 하나뿐</b>이다(회귀 가드가 이 지점을 고정한다).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ChevronLeft, Scissors, X } from 'lucide-react';

import { PortalAlert } from '@/components/portal/ui/PortalAlert';
import { PortalEmptyState } from '@/components/portal/ui/PortalEmptyState';
import { PortalSectionHead } from '@/components/portal/ui/PortalSectionHead';
import { PORTAL_SURFACE, portalButton } from '@/components/portal/ui/portalControl';
import { markAriaLabel } from '@/features/marking/components/MarkingTimeline';
import { markingFrameIndex, resolveMarkingFps } from '@/features/marking/markingFps';
import { useStreamPlaybackRetry } from '@/features/marking/hooks/useStreamPlaybackRetry';
import { MarkingCompleteConfirmDialog } from '@/features/portal/uploads/components/MarkingCompleteConfirmDialog';
import {
  PortalMarkingStage,
  type PortalMarkingStageHandle,
} from '@/features/portal/uploads/components/PortalMarkingStage';
import {
  PortalMarkingToolbar,
  type MarkingLockReason,
} from '@/features/portal/uploads/components/PortalMarkingToolbar';
import {
  useSaveUploadMarking,
  useUploadMarkings,
  useUploadStreamUrl,
} from '@/features/portal/uploads/hooks/useUploadMarking';
import { useUploadDetail } from '@/features/portal/uploads/hooks/useUploadDetail';
import {
  PORTAL_AUTO_INTERVAL_DEFAULT_FRAMES,
  buildAutoMarks,
  buildMarkingSaveRequest,
  capApplied,
  formatMarkTimestamp,
  intervalSeconds,
  totalFrameCount,
} from '@/features/portal/uploads/markingPlan';
import {
  PortalMarkingMode,
  type MarkItem,
  type PortalMarkingSaveResult,
} from '@/features/portal/uploads/markingTypes';
import { PortalUploadStatus, PortalUploadType } from '@/features/portal/uploads/types';
import { toDeployedApiUrl } from '@/lib/api/deployBasePath';
import { ApiError } from '@/lib/api/errors';
import { extractBeMessage } from '@/lib/api/extractBeMessage';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useUiStore } from '@/stores/useUiStore';

/**
 * 추출 장수 상한 — <b>화면은 모른다.</b>
 *
 * 그 값은 서버 설정이고 화면에 전달하는 경로가 아직 정해지지 않았다. 여기 숫자를 베껴 두면
 * <b>두 번째 진실원</b>이 되어, 설정이 바뀌는 순간 화면이 「N장 뽑힙니다」라고 거짓을 말한다.
 * 그래서 모른다고 두고 <b>절단을 예고하지 않는다</b>.
 *
 * ★ 그 대신 사실이 유실되지는 않는다 — 저장 응답이 잘림 여부와 잘리기 전 지점 수를 함께 돌려주고,
 *   이 화면은 그것을 저장 직후에 그대로 알린다(아래 `saveResult` 안내).
 * ⚠ 전달 경로가 생기면 <b>여기만</b> 바꾸면 된다. 확인 창과 툴바는 이 값을 받아 판정만 위임한다.
 */
const EXTRACT_FRAME_CAP: number | null = null;

/**
 * 차단 안내 — 세 축이 같은 자리·같은 형태로 사유를 보여 준다(판정과 문구는 축마다 따로다).
 *
 * ★<b>막다른 길로 두지 않는다.</b> 이 자리에 닿은 사람은 마킹을 하러 왔다가 못 하게 된 것이라,
 *   사유만 알리고 세워 두면 스스로 돌아갈 길을 찾아야 한다. 목록으로 가는 문을 함께 둔다.
 */
function MarkingBlockedNotice({ reason, testId }: { reason: string; testId: string }) {
  return (
    <div className="mx-auto flex w-full max-w-wrap flex-col gap-column">
      <PortalEmptyState
        icon={Scissors}
        title="이 영상은 지금 마킹할 수 없습니다"
        description={
          <span role="alert" data-testid={testId}>
            {reason}
          </span>
        }
        action={
          <Link to={UPLOADS_PATH} className={portalButton('secondary')}>
            내 업로드로 돌아가기
          </Link>
        }
      />
    </div>
  );
}

/** 돌아갈 자리 — 이 화면으로 들어오는 유일한 문이다. */
const UPLOADS_PATH = '/portal/uploads';

/** 오류가 「재생할 파일이 아직 없다」인가 — 업로드 완료 축의 유일한 신호다. */
function isFileNotReady(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 404 || error.errorCode === 'NOT_FOUND');
}

export function PortalUploadMarkingPage() {
  const { uldSn: uldSnParam } = useParams<{ uldSn: string }>();
  const parsed = uldSnParam ? Number(uldSnParam) : NaN;
  const uldSn = Number.isInteger(parsed) && parsed > 0 ? parsed : undefined;
  const pushToast = useUiStore((s) => s.pushToast);

  const playerRef = useRef<PortalMarkingStageHandle>(null);
  const [mode, setMode] = useState<PortalMarkingMode>(PortalMarkingMode.AUTO);
  const [intervalFrames, setIntervalFrames] = useState(PORTAL_AUTO_INTERVAL_DEFAULT_FRAMES);
  const [manualMarks, setManualMarks] = useState<MarkItem[]>([]);
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [playerDurationSec, setPlayerDurationSec] = useState(0);
  const [saveResult, setSaveResult] = useState<PortalMarkingSaveResult | null>(null);

  const detailQuery = useUploadDetail(uldSn);
  const detail = detailQuery.data;
  const isVideo = detail?.uldTypeCd === PortalUploadType.VIDEO;

  // 영상이 아닌 자산에는 서명 주소를 청하지 않는다 — 어차피 거절되고, 그 거절이 「업로드 완료」
  // 축의 신호와 섞이면 안내가 엉뚱해진다.
  const streamQuery = useUploadStreamUrl(isVideo ? uldSn : undefined);
  const markingsQuery = useUploadMarkings(uldSn);

  // 저장된 마킹 — 있으면 편집이 아니라 확인이다(지우거나 더할 수 없다).
  const savedMarking = markingsQuery.data?.markings[0] ?? null;

  /**
   * 지점 산출의 근거는 <b>원장의 길이·초당 프레임 수</b>다(재생 요소가 읽은 길이가 아니다).
   * 서버가 저장할 때 그 값으로 다시 산출하므로, 화면이 다른 값을 쓰면 예고한 장수와 실제 장수가
   * 갈린다. 재생 요소의 길이는 타임라인 눈금을 그릴 때만, 그것도 원장 값이 없을 때만 쓴다.
   */
  const fps = resolveMarkingFps(detail?.fps);
  const storedDurationSec = detail?.vdoLenSec ?? null;
  const totalFrames = totalFrameCount(storedDurationSec, fps);
  const timelineDurationSec = storedDurationSec ?? (playerDurationSec > 0 ? playerDurationSec : 0);

  const autoPreview = useMemo(
    () => buildAutoMarks(storedDurationSec, fps, intervalFrames),
    [storedDurationSec, fps, intervalFrames],
  );

  const isAuto = mode === PortalMarkingMode.AUTO;
  // 저장 가능 여부 — 마킹 대기 상태이고 저장된 마킹이 없을 때만.
  const lock: MarkingLockReason | null = (() => {
    if (!detail) return null;
    if (detail.uldSttsCd === PortalUploadStatus.UPLOADED && savedMarking === null) return null;
    return detail.uldSttsCd === PortalUploadStatus.PROCESSING ? 'EXTRACTING' : 'SAVED';
  })();
  const locked = lock !== null;

  // 목록·타임라인이 그리는 지점 — 저장된 것이 있으면 그것(확인용), 없으면 지금 설정의 미리보기.
  // ⚠ 매 렌더마다 새 배열을 만들면 이 값을 의존성으로 쓰는 콜백이 함께 흔들린다.
  const savedMarks = savedMarking?.marks;
  const displayMarks: MarkItem[] = useMemo(() => {
    if (locked) return savedMarks ?? [];
    return isAuto ? autoPreview.marks : manualMarks;
  }, [autoPreview.marks, isAuto, locked, manualMarks, savedMarks]);
  const requestedCount = isAuto ? autoPreview.count : manualMarks.length;
  // 자동인데 영상 길이를 모르면 셀 수 없다 — 지어내지 않고 「셀 수 없다」고 말한다.
  const countable = !isAuto || totalFrames > 0;
  const cap = countable ? capApplied(requestedCount, EXTRACT_FRAME_CAP) : null;

  const saveMutation = useSaveUploadMarking(uldSn, {
    onSuccess: (result) => {
      setConfirmOpen(false);
      setSaveResult(result);
      setManualMarks([]);
      setSelectedIndex(null);
      pushToast({
        variant: 'success',
        message: `마킹을 저장했습니다. 프레임 ${result.markCount}장을 뽑기 시작합니다.`,
      });
      if (result.truncated) {
        pushToast({
          variant: 'warning',
          message: `고른 지점 ${result.requestedMarkCount}건 가운데 ${result.markCount}건만 쓰였습니다(추출 장수 상한).`,
        });
      }
    },
    onError: (err) => {
      setConfirmOpen(false);
      pushToast({
        variant: 'error',
        message: extractBeMessage(err, '마킹을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
      });
    },
  });

  // 서명이 만료돼 재생이 끊기면 주소를 다시 받아 그 자리에서 이어 재생한다.
  // 재생 실패 시 재발급·재시도는 <b>연속 실패 상한 안에서만</b> 한다.
  // [@design API-114] [@design SCREEN-045]
  //   구 동작은 「진행 중이면 무시」만 두고 횟수를 세지 않아, 회복되지 않는 실패에서
  //   발급→실패→발급이 끝없이 돌았다. 상한에 이르면 멈추고 <b>이 화면이 이미 쓰는 재생 실패
  //   안내</b>를 그대로 띄운다(새 표면을 만들지 않는다).
  //   ★재생이 회복되면 예산을 되돌린다 — 서명 수명이 짧아 마킹 도중 만료가 여러 번 일어나므로
  //   누적으로 세면 정상 동선에서 회복 경로가 영구히 닫힌다.
  const {
    handleSrcError: handleStreamError,
    handlePlaybackRecovered,
    exhausted: streamExhausted,
  } = useStreamPlaybackRetry({
    reissue: streamQuery.refetch,
    resetKey: uldSn,
  });

  const handleAddMarkAtCurrentTime = useCallback(() => {
    if (locked || !playerRef.current) return;
    const time = playerRef.current.getCurrentTime();
    let frameIndex = markingFrameIndex(time, fps);
    // 총 프레임 수를 아는 경우 마지막 프레임을 넘지 않게 한다 — 반올림으로 한 칸 넘어가면 서버가
    // 「없는 프레임」이라고 거절한다(사용자가 실제로 본 장면은 마지막 프레임이다).
    if (totalFrames > 0 && frameIndex >= totalFrames) frameIndex = totalFrames - 1;
    if (frameIndex < 0) return;
    setManualMarks((prev) => {
      if (prev.some((m) => m.frameIndex === frameIndex)) return prev;
      return [...prev, { frameIndex, timestamp: formatMarkTimestamp(time) }].sort(
        (a, b) => a.frameIndex - b.frameIndex,
      );
    });
  }, [fps, locked, totalFrames]);

  const removeMarkAt = useCallback((index: number) => {
    setManualMarks((prev) => prev.filter((_, i) => i !== index));
    setSelectedIndex(null);
  }, []);

  const selectMark = useCallback(
    (index: number) => {
      setSelectedIndex(index);
      const mark = displayMarks[index];
      if (mark && fps > 0) playerRef.current?.seekTo(mark.frameIndex / fps);
    },
    [displayMarks, fps],
  );

  /**
   * 완료 — <b>여기서 저장하지 않는다.</b> 확인 단계를 열 뿐이다.
   *
   * 수동인데 지점이 하나도 없으면 확인 단계로 넘어가지 않고 안내만 띄운다. 버튼을 죽이지 않는
   * 것은 「왜 눌리지 않는가」를 화면이 말해 주게 하기 위해서다(안내는 보조기술에도 읽힌다).
   */
  const handleRequestComplete = useCallback(() => {
    if (locked || saveMutation.isPending) return;
    if (!isAuto && manualMarks.length === 0) {
      pushToast({
        variant: 'warning',
        message: '재생하며 지점을 1건 이상 찍어 주세요.',
      });
      return;
    }
    if (isAuto && totalFrames <= 0) {
      pushToast({
        variant: 'warning',
        message:
          '영상 길이를 아직 확인하지 못해 자동 마킹을 만들 수 없습니다. 잠시 후 다시 시도해 주세요.',
      });
      return;
    }
    setConfirmOpen(true);
  }, [isAuto, locked, manualMarks.length, pushToast, saveMutation, totalFrames]);

  /** 저장이 실제로 나가는 <b>유일한</b> 자리 — 확인 창의 확인 버튼이 부른다. */
  const handleConfirmedSave = useCallback(() => {
    if (uldSn === undefined || saveMutation.isPending) return;
    saveMutation.mutate(buildMarkingSaveRequest(mode, intervalFrames, manualMarks));
  }, [intervalFrames, manualMarks, mode, saveMutation, uldSn]);

  // 단축키 — 수동 방식에서만. 확인 창이 열려 있으면 아무 키도 받지 않는다(창 안의 조작과 겹친다).
  useEffect(() => {
    if (locked) return undefined;
    const handler = (e: KeyboardEvent) => {
      if (confirmOpen) return;
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
      if (mode !== PortalMarkingMode.MANUAL) return;
      if (e.code === 'Space') {
        e.preventDefault();
        handleAddMarkAtCurrentTime();
      } else if (e.code === 'Delete' || e.code === 'Backspace') {
        e.preventDefault();
        if (selectedIndex !== null) removeMarkAt(selectedIndex);
      } else if (e.code === 'Enter') {
        e.preventDefault();
        handleRequestComplete();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [
    confirmOpen,
    handleAddMarkAtCurrentTime,
    handleRequestComplete,
    locked,
    mode,
    removeMarkAt,
    selectedIndex,
  ]);

  // 방식을 바꾸면 선택을 푼다 — 목록이 통째로 바뀌므로 옛 번호가 엉뚱한 지점을 가리킨다.
  useEffect(() => {
    setSelectedIndex(null);
  }, [mode]);

  if (uldSn === undefined) {
    return (
      <MarkingBlockedNotice reason="잘못된 자산 주소입니다." testId="marking-blocked-invalid" />
    );
  }

  // ── 진입 차단 세 축 — 순서대로 확인하고 하나라도 걸리면 편집기를 그리지 않는다 ──────────
  // ⚠ 상세를 아직 받는 중이면 어느 축도 차단하지 않는다.
  if (detailQuery.isError) {
    return (
      <MarkingBlockedNotice
        reason="자산을 불러올 수 없습니다."
        testId="marking-blocked-forbidden"
      />
    );
  }
  if (isFileNotReady(streamQuery.error)) {
    return (
      <MarkingBlockedNotice
        reason="아직 다 올라오지 않은 영상입니다. 업로드가 끝나면 마킹할 수 있습니다."
        testId="marking-blocked-not-uploaded"
      />
    );
  }
  if (detail && !isVideo) {
    return (
      <MarkingBlockedNotice
        reason="영상 자산만 이벤트 구간을 마킹할 수 있습니다."
        testId="marking-blocked-not-video"
      />
    );
  }

  // 발급받은 주소는 <b>배포 접두를 뺀 API 기준 경로</b>다 — 발급하는 쪽은 자신이 어느 컨텍스트
  // 아래에 놓이는지 알 수 없기 때문이다. 이 화면이 아는 접두를 앞에 붙여야 요청이 우리 창구에
  // 도달하며, 붙이지 않으면 같은 오리진의 다른 시스템 경로로 나가 영상이 오지 않는다.
  // ⚠ 루트에 서비스되는 배포에서는 두 주소가 우연히 같아 이 어긋남이 드러나지 않는다.
  // [@design API-114] [@design API-239]
  const videoSrc = streamExhausted ? '' : toDeployedApiUrl(streamQuery.data?.url);
  const streamFailed =
    streamExhausted || (streamQuery.isError && !isFileNotReady(streamQuery.error));

  return (
    // [@design SCREEN-045]
    // ★<b>한 화면에 담는 것을 기준으로 짠다.</b> 넓은 폭에서는 두 열로 나누어 왼쪽에 재생 무대,
    //   오른쪽에 설정·완료·지점 목록을 두고, 좁은 폭에서는 같은 차례로 한 열에 쌓는다.
    //   ⚠ 세로로만 쌓으면 무대(영상+눈금+조작)만으로 화면이 차서 <b>완료 명령이 화면 밖으로
    //     밀려난다</b> — 되돌릴 수 없는 조작을 눈으로 확인하며 누를 수 없게 된다.
    // ★<b>셸의 페이지 아래 여백을 되돌려 받는다</b> — 이 화면은 한 화면에 담는 몰입 편집 화면이라
    //   페이지 끝의 세로 리듬이 쓰이지 않는다. 그 자리를 그대로 두면 <b>영상이 그만큼 작아진다.</b>
    //   ⚠ 셸을 고치지 않는 이유: 그 여백은 포털의 다른 화면들이 쓰는 값이고, 그쪽은 목록이라
    //     끝에 숨 쉴 자리가 필요하다. 예외가 필요한 것은 <b>이 화면 하나</b>다.
    <div className="mx-auto -mb-page-section flex w-full max-w-wrap flex-col gap-in-component">
      <section aria-labelledby="portal-marking-head" className="flex flex-col gap-in-component">
        <PortalSectionHead
          id="portal-marking-head"
          title="업로드 영상 마킹"
          /* 이 화면이 무엇을 하는 자리인지 한 줄 — 「마킹」이라는 낱말만으로는 프레임 추출
             지점을 정하는 일이라는 것이 드러나지 않는다. */
          lead="프레임을 어느 지점에서 뽑을지 정합니다. 완료하면 그 지점으로 추출이 시작됩니다."
          /* 어느 자산을 마킹하는지 — 사용자 파일명은 텍스트 노드로만 렌더한다(자동 escape). */
          count={detail?.orgnlFileNm ?? undefined}
          /* 돌아갈 길 — 이 화면은 목록의 한 행에서 들어오는 자리라, 나가는 문이 없으면 브라우저
             뒤로가기 말고는 방법이 없다. ★제목 줄 오른쪽에 얹는다: 위에 따로 한 줄을 두면 그
             줄만큼 영상이 작아진다. 이 화면에서 세로 한 줄은 영상 높이와 맞바꾸는 자원이다. */
          action={
            <Link
              to={UPLOADS_PATH}
              className={cn(
                'inline-flex items-center gap-tight rounded-pill px-2 py-1',
                'text-body-sm text-gray-600 transition-colors hover:text-gray-900',
                KRDS_FOCUS,
              )}
            >
              <ChevronLeft className="size-4" aria-hidden />내 업로드
            </Link>
          }
        />

        {/* 두 열 — 넓은 폭에서만 갈라진다. `items-start` 라야 오른쪽 열이 왼쪽 무대 높이만큼
            늘어나지 않는다(늘어나면 목록 카드가 빈 채로 길어진다). */}
        <div className="flex flex-col gap-in-component xl:flex-row xl:items-start">
          {/* 왼쪽 — 재생 무대. `min-w-0` 가 없으면 영상이 열을 밀어 오른쪽이 눌린다. */}
          <div className="min-w-0 flex-1">
            {videoSrc ? (
              <PortalMarkingStage
                ref={playerRef}
                src={videoSrc}
                marks={displayMarks}
                durationSec={timelineDurationSec}
                fps={fps}
                selectedIndex={selectedIndex}
                onSelectMark={selectMark}
                onSrcError={handleStreamError}
                onSrcRecovered={handlePlaybackRecovered}
                onDurationChange={setPlayerDurationSec}
              />
            ) : (
              <div
                className={cn(
                  PORTAL_SURFACE,
                  'flex aspect-video w-full items-center justify-center bg-gray-900 text-body-md text-gray-300',
                )}
              >
                {streamFailed ? '영상을 재생할 수 없습니다.' : '영상을 불러오는 중…'}
              </div>
            )}
          </div>

          {/* 오른쪽 — 설정·완료·지점 목록. 폭을 고정해 무대가 남는 폭을 전부 갖게 한다. */}
          <div className="flex w-full flex-col gap-in-component xl:w-80 xl:shrink-0">
            <PortalMarkingToolbar
              mode={mode}
              onModeChange={setMode}
              intervalFrames={intervalFrames}
              onIntervalChange={setIntervalFrames}
              intervalSec={intervalSeconds(intervalFrames, detail?.fps)}
              cap={cap}
              markCount={manualMarks.length}
              lock={lock}
              submitting={saveMutation.isPending}
              onClear={() => {
                setManualMarks([]);
                setSelectedIndex(null);
              }}
              onSubmit={handleRequestComplete}
            />

            {/* 저장 직후 절단 사실 — 토스트는 사라지므로 이 자리에 남긴다. 상한을 미리 알 수 없어
            예고하지 못한 경우에도 사실이 유실되지 않게 하는 자리다. */}
            {saveResult?.truncated && (
              <PortalAlert
                tone="error"
                live
                title="고른 지점이 추출 장수 상한을 넘어 일부만 쓰였습니다."
                data-testid="marking-truncated-result"
                description={
                  <>
                    요청한 {saveResult.requestedMarkCount}건 가운데 {saveResult.markCount}건으로
                    프레임을 뽑습니다.
                  </>
                }
              />
            )}

            {/* 현재 마킹 목록 — 0건이어도 자리를 감추지 않는다. 감추면 「그런 기능이 없다」와
            「아직 찍지 않았다」를 구분할 수 없다. */}
            <div className={cn(PORTAL_SURFACE, 'flex flex-col gap-in-component p-in-component')}>
              <div className="flex flex-wrap items-baseline justify-between gap-inline">
                <h3 className="text-title-sm text-gray-900">
                  {locked ? '저장된 마킹' : '현재 마킹'}
                </h3>
                <span className="text-caption tabular-nums text-gray-500">
                  {displayMarks.length}건
                </span>
              </div>

              {autoPreview.renderSampled && !locked && isAuto && (
                <p className="text-body-sm text-gray-600">
                  지점이 많아 목록에는 일부만 그립니다. 실제로 뽑히는 장수는 위 안내를 보세요.
                </p>
              )}

              {displayMarks.length === 0 ? (
                <PortalEmptyState
                  icon={Scissors}
                  title="아직 지점이 없습니다"
                  description={
                    isAuto
                      ? '자동 방식은 간격(프레임)만 정하면 되며 지점을 따로 찍지 않습니다.'
                      : '영상을 재생하다 원하는 순간에 Space 를 눌러 지점을 찍어 주세요.'
                  }
                />
              ) : (
                /* 지점은 알약으로 흩는다 — 수가 많고 길이가 짧아 줄바꿈이 자연스럽고, 하나씩 따로
               읽힌다(표로 세우면 한 건에 한 줄을 써 화면을 다 먹는다). */
                /* ★목록만 자기 자리 안에서 스크롤한다 — 지점이 늘어도 아래의 완료 명령이 화면
               밖으로 밀려나지 않게. 높이를 넉넉히 두어 대부분의 경우 스크롤이 생기지 않는다. */
                <ul className="flex max-h-64 flex-wrap gap-inline overflow-y-auto">
                  {displayMarks.map((mark, i) => {
                    const selected = selectedIndex === i;
                    return (
                      <li
                        key={mark.frameIndex}
                        className={cn(
                          'inline-flex items-stretch overflow-hidden rounded-pill border transition-colors',
                          selected
                            ? 'border-primary-500 bg-primary-50'
                            : 'border-gray-200 bg-gray-50',
                        )}
                      >
                        <button
                          type="button"
                          onClick={() => selectMark(i)}
                          aria-pressed={selected}
                          className={cn(
                            'px-in-component py-1.5 text-caption tabular-nums transition-colors',
                            KRDS_FOCUS,
                            selected ? 'text-primary-700' : 'text-gray-700 hover:text-gray-900',
                          )}
                        >
                          F{mark.frameIndex} {mark.timestamp && `(${mark.timestamp})`}
                        </button>
                        {/* 개별 삭제는 수동 방식에서만 — 자동 지점은 간격이 정하고, 저장된 마킹은
                        확인용이라 손댈 수 없다. */}
                        {!locked && !isAuto && (
                          <button
                            type="button"
                            onClick={() => removeMarkAt(i)}
                            aria-label={`마킹 삭제 ${markAriaLabel(mark)}`}
                            /* ⚠ 500 단으로 내리지 말 것 — 고른 지점의 알약은 바탕이 옅은 주색
                               면이라 500 단은 4.01:1 로 본문 대비에 미달한다(가드가 잡는다). */
                            className={cn(
                              'flex items-center px-2 text-gray-600 transition-colors',
                              'hover:bg-danger-50 hover:text-danger-600',
                              KRDS_FOCUS,
                            )}
                          >
                            <X className="size-3.5" aria-hidden />
                          </button>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </div>
        </div>
      </section>

      <MarkingCompleteConfirmDialog
        open={confirmOpen}
        mode={mode}
        intervalFrames={intervalFrames}
        intervalSec={intervalSeconds(intervalFrames, detail?.fps)}
        cap={cap ?? capApplied(requestedCount, EXTRACT_FRAME_CAP)}
        saving={saveMutation.isPending}
        onConfirm={handleConfirmedSave}
        onCancel={() => setConfirmOpen(false)}
      />
    </div>
  );
}
