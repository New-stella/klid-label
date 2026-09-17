/**
 * 포털 업로드 영상 마킹 화면. [@design SCREEN-045] [@design NAV-002]
 * [@design API-238] [@design API-239] [@design API-240] [@design API-241] [@design API-140]
 *
 * <h3>이 화면이 하는 일</h3>
 * 포털 회원이 본인이 올린 영상에서 <b>프레임을 어느 지점에서 뽑을지</b> 정한다. 마킹을 완료하면
 * 그 지점으로 추출이 시작되고, 추출이 끝나면 자산이 준비 완료가 되어 라벨링으로 넘어간다.
 * 마킹을 거치지 않은 자산은 프레임을 갖지 않는다.
 *
 * <h3>모양 — 부모 포털의 저작도구 화면을 그대로 입혔다 (2026-09-16)</h3>
 * 부모 포털(KLID_Portal)이 이 화면을 <b>자기 부품으로 다시 그려</b> 「저작도구 쪽에 넘기는 기준」으로
 * 삼았고(`pages/workspace/authoring/AuthoringMarkingView`), 그 짜임을 여기에 옮겼다.
 *   · 판·기둥 짜임 = `klid-marking*` · `klid-authoring-block`
 *     (styles/portal/marking-view.css · authoring-layout.css). ⚠ 화면이 그 CSS 를 스스로 import
 *     하지 않는다 — 포털 채널 스타일 로드의 단일 지점은 `styles/portalLook.ts` 이고, 화면마다
 *     import 를 흩으면 채널별로 스타일이 갈린다(회귀 가드 bootstrapSingleSource).
 *   · 부품 = 포털 킷(`components/portal/kit`) + 저작 부품(`components/portal/authoring`) + KRDS 킷
 *   · <b>안내 띠가 콘텐츠 맨 위로 올라왔다</b> — 제목 바로 아래, 재생기·설정 기둥보다 먼저 읽힌다
 *     (구 동작: 잠긴 사유가 설정 기둥 안에 있어 오른쪽 기둥을 다 읽어야 나왔다)
 *   · 지점 목록이 <b>제 카드</b>로 떨어져 나왔다(구 동작: 설정과 한 판)
 * ⚠ 관제 공통 부품(`components/common/*`)을 쓰지 않는다 — 관제 화면 여럿이 함께 쓰므로 포털 모양을
 *   넣으면 관제 화면이 같이 바뀐다(사용자 확정 구속: <b>관제향 화면·컴포넌트 불변</b>).
 * ⚠ <b>데이터 흐름·창구·판정은 하나도 바꾸지 않았다.</b> 바뀐 것은 무엇으로 그리느냐뿐이다.
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
import { Button } from 'krds-react';
import { ChevronLeft, Scissors } from 'lucide-react';

import { MarkPointList, VideoStage } from '@/components/portal/authoring';
import { Alert, EmptyState, ResultCount, StepHeading } from '@/components/portal/kit';
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

/** 돌아갈 자리 — 이 화면으로 들어오는 유일한 문이다. */
const UPLOADS_PATH = '/portal/uploads';

/**
 * 차단 안내 — 세 축이 같은 자리·같은 형태로 사유를 보여 준다(판정과 문구는 축마다 따로다).
 *
 * ★<b>막다른 길로 두지 않는다.</b> 이 자리에 닿은 사람은 마킹을 하러 왔다가 못 하게 된 것이라,
 *   사유만 알리고 세워 두면 스스로 돌아갈 길을 찾아야 한다. 목록으로 가는 문을 함께 둔다.
 * ★ 그 문은 <b>링크다</b> — 킷 버튼은 다형 부품이라 라우터 링크를 끼우면 생김새와 링크의 성질을
 *   둘 다 갖는다. `role` 을 되돌리는 것은 킷 기본값이 `button` 이라서다(보조기술이 「링크」로 읽어야
 *   가운데 클릭·새 탭·주소 복사가 있는 자리임이 전달된다).
 */
function MarkingBlockedNotice({ reason, testId }: { reason: string; testId: string }) {
  return (
    <section className="klid-authoring-block" aria-label="업로드 영상 마킹">
      <EmptyState
        icon={Scissors}
        title="이 영상은 지금 마킹할 수 없습니다"
        desc={
          <span role="alert" data-testid={testId}>
            {reason}
          </span>
        }
        action={
          <Button as={Link} to={UPLOADS_PATH} role="link" size="medium" variant="secondary">
            내 업로드로 돌아가기
          </Button>
        }
      />
    </section>
  );
}

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

  /*
   * 지점 목록 부품은 지점을 <b>프레임 번호</b>로 주고받는다(자리 번호가 아니다). 그 환산을 여기서
   * 한 번만 해 두고 아래 세 자리(고르기 · 지우기 · 이름표)가 같은 표를 본다 — 자리마다 따로
   * 훑으면 정렬이 바뀔 때 한 자리만 어긋난다.
   */
  const markByFrame = useMemo(() => {
    const map = new Map<number, { index: number; mark: MarkItem }>();
    displayMarks.forEach((mark, index) => map.set(mark.frameIndex, { index, mark }));
    return map;
  }, [displayMarks]);
  const markFrames = useMemo(() => displayMarks.map((m) => m.frameIndex), [displayMarks]);
  const selectedFrame =
    selectedIndex !== null ? (displayMarks[selectedIndex]?.frameIndex ?? null) : null;
  const pointLabel = useCallback(
    (frame: number) => {
      const ts = markByFrame.get(frame)?.mark.timestamp;
      return ts ? `F${frame} (${ts})` : `F${frame}`;
    },
    [markByFrame],
  );

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
  const canRemove = !locked && !isAuto;

  return (
    // [@design SCREEN-045]
    // ★<b>한 화면에 담는 것을 기준으로 짠다.</b> 넓은 폭에서는 두 기둥으로 나누어 왼쪽에 재생 무대,
    //   오른쪽에 설정·완료·지점 목록을 두고, 좁은 폭에서는 같은 차례로 한 기둥에 쌓인다(격자가
    //   그 전환을 갖는다 — marking-view.css).
    //   ⚠ 세로로만 쌓으면 무대(영상+눈금+조작)만으로 화면이 차서 <b>완료 명령이 화면 밖으로
    //     밀려난다</b> — 되돌릴 수 없는 조작을 눈으로 확인하며 누를 수 없게 된다.
    //   ★<b>영상 높이 상한(구 `max-h-[65vh]`)은 두지 않아도 그 걱정이 성립하지 않는다 — 실측했다.</b>
    //     격자가 두 기둥을 <b>위로 맞춰</b> 세우므로(`align-items: start`) 왼쪽 영상이 아무리 커져도
    //     오른쪽 기둥은 제자리에 남는다. 1440·1920·2560 폭에서 「마킹 완료」 버튼 아랫변이
    //     <b>전부 457px</b> 로 같았다(폭과 무관). 그래서 시안대로 16:9 에 맡긴다.
    //     ⚠ 되돌리기 전에 이 실측부터 다시 할 것 — 상한을 다시 씌우면 넓은 화면에서 영상만 작아진다.
    <section className="klid-authoring-block" aria-labelledby="portal-marking-head">
      {/* 머리 — 이름 옆에 무엇에 대한 마킹인지(파일명), 오른쪽 끝에 돌아가는 길.
          돌아가는 길은 글자 걸음이다 — 이 면의 걸음(마킹 완료)과 무게를 다투지 않는다.
          ★제목 줄 오른쪽에 얹는다: 위에 따로 한 줄을 두면 그 줄만큼 영상이 작아진다.
          이 화면에서 세로 한 줄은 영상 높이와 맞바꾸는 자원이다.
          ⚠ 사용자 파일명은 텍스트 노드로만 렌더한다(자동 escape). */}
      <StepHeading
        size="md"
        id="portal-marking-head"
        title="업로드 영상 마킹"
        note={detail?.orgnlFileNm ?? undefined}
        desc="프레임을 어느 지점에서 뽑을지 정합니다. 완료하면 그 지점으로 추출이 시작됩니다."
        aside={
          <Button as={Link} to={UPLOADS_PATH} role="link" size="small" variant="text">
            <ChevronLeft aria-hidden />내 업로드
          </Button>
        }
      />

      {/* 잠긴 자산 — 사유와 회복 경로를 함께 알린다. 기다린다고 풀리는 것이 아니다.
          ★안내 띠는 콘텐츠 맨 위다 — 재생기·설정 기둥보다 먼저 읽힌다.
          코발트(primary)인 것은 지나가는 규칙이 아니라 <b>지금 이 면의 사정</b>이라서다. */}
      {lock !== null && (
        <div data-testid="marking-locked-notice">
          <Alert
            tone="primary"
            live="none"
            title="다시 마킹하려면 이 자산을 지우고 다시 올려야 합니다."
          >
            {lock === 'EXTRACTING' ? (
              '이미 마킹을 저장해 지금 프레임을 뽑고 있습니다. 추출이 진행 중인 동안에는 이 자산을 지울 수 없으니, 추출이 끝난 뒤에 지우고 다시 올려 주세요.'
            ) : (
              <>
                이미 마킹을 저장한 영상입니다.{' '}
                {/* 킷 링크의 생김새만 빌린다 — 실체는 라우터 링크라야 주소가 살아 있고
                    보조기술이 「링크」로 읽는다(킷 버튼은 `role` 이 button 으로 고정된다). */}
                <Link to={UPLOADS_PATH} className="krds-btn link small">
                  <span className="underline">내 업로드</span>
                </Link>
                에서 이 자산을 지우고 다시 올리면 새로 마킹할 수 있습니다.
              </>
            )}
          </Alert>
        </div>
      )}

      {/* 저장 직후 절단 사실 — 토스트는 사라지므로 이 자리에 남긴다. 상한을 미리 알 수 없어
          예고하지 못한 경우에도 사실이 유실되지 않게 하는 자리다. */}
      {saveResult?.truncated && (
        <div data-testid="marking-truncated-result">
          <Alert tone="danger" title="고른 지점이 추출 장수 상한을 넘어 일부만 쓰였습니다.">
            요청한 {saveResult.requestedMarkCount}건 가운데 {saveResult.markCount}건으로 프레임을
            뽑습니다.
          </Alert>
        </div>
      )}

      <div className="klid-marking">
        {/* ── 재생기 ── */}
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
          /* 영상이 아직 없거나 끝내 오지 않은 두 상태 — 같은 자리에 같은 판으로 선다.
             눈금도 재생 조작도 세우지 않는다(누를 대상이 없다). 안내는 판 스스로
             `role="status"` 로 알린다. */
          <section className="klid-section-card klid-marking-player" aria-label="영상 재생">
            <VideoStage
              status={streamFailed ? 'unavailable' : 'loading'}
              message={streamFailed ? '영상을 재생할 수 없습니다.' : '영상을 불러오는 중…'}
            />
          </section>
        )}

        {/* ── 설정 기둥 ── */}
        <div className="klid-marking-side">
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

          {/* 현재 마킹 목록 — 0건이어도 자리를 감추지 않는다. 감추면 「그런 기능이 없다」와
              「아직 찍지 않았다」를 구분할 수 없다. */}
          <section className="klid-section-card klid-marking-card" aria-labelledby="marking-list-title">
            <StepHeading
              size="sm"
              id="marking-list-title"
              title={locked ? '저장된 마킹' : '현재 마킹'}
              desc={
                autoPreview.renderSampled && !locked && isAuto
                  ? '지점이 많아 목록에는 일부만 그립니다. 실제로 뽑히는 장수는 위 안내를 보세요.'
                  : undefined
              }
              aside={<ResultCount total={displayMarks.length} />}
            />
            {/* 알약을 누르면 그 지점으로 옮긴다. 개별 삭제는 수동 방식에서만 — 자동 지점은 간격이
                정하고, 저장된 마킹은 확인용이라 손댈 수 없다. */}
            <MarkPointList
              label={locked ? '저장된 마킹 지점' : '현재 마킹 지점'}
              points={markFrames}
              pointLabel={pointLabel}
              selected={selectedFrame}
              onSelect={(frame) => {
                const hit = markByFrame.get(frame);
                if (hit) selectMark(hit.index);
              }}
              onRemove={
                canRemove
                  ? (frame) => {
                      const hit = markByFrame.get(frame);
                      if (hit) removeMarkAt(hit.index);
                    }
                  : undefined
              }
              removeLabel={(frame) => {
                const mark = markByFrame.get(frame)?.mark;
                return `마킹 삭제 ${mark ? markAriaLabel(mark) : `F${frame}`}`;
              }}
              empty={
                <EmptyState
                  size="sm"
                  icon={Scissors}
                  title="아직 지점이 없습니다"
                  desc={
                    isAuto
                      ? '자동 방식은 간격(프레임)만 정하면 되며 지점을 따로 찍지 않습니다.'
                      : '영상을 재생하다 원하는 순간에 Space 를 눌러 지점을 찍어 주세요.'
                  }
                />
              }
            />
          </section>
        </div>
      </div>

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
    </section>
  );
}
