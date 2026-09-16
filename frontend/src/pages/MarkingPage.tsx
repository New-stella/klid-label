import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { X } from 'lucide-react';
import { EmptyState } from '@/components/common/EmptyState';
import { DeidentReportButton } from '@/features/label/components/DeidentReportButton';
import { resolveDeidentReportUnsupportedReason } from '@/features/label/utils/deidentReportEligibility';
import { MarkingTimeline, markAriaLabel } from '@/features/marking/components/MarkingTimeline';
import { MarkingToolbar } from '@/features/marking/components/MarkingToolbar';
import { VerificationEventTypeSelect } from '@/features/marking/components/VerificationEventTypeSelect';
import { VerificationQuestionSelect } from '@/features/marking/components/VerificationQuestionSelect';
import { VideoPlayer, type VideoPlayerHandle } from '@/features/marking/components/VideoPlayer';
import { useCreateMarking } from '@/features/marking/hooks/useMarkings';
import { useStreamPlaybackRetry } from '@/features/marking/hooks/useStreamPlaybackRetry';
import { toDeployedApiUrl } from '@/lib/api/deployBasePath';
import { extractBeMessage } from '@/lib/api/extractBeMessage';
import { useMarkingStore } from '@/features/marking/store';
import type { MarkItem, MarkingMode } from '@/features/marking/types';
import { useStreamUrl } from '@/features/video/hooks/useStreamUrl';
import { useVideoDetail } from '@/features/video/hooks/useVideoDetail';
import { isMarkingBlocked } from '@/features/video/types';
import { resolveMarkingFps } from '@/features/marking/markingFps';
import { useUiStore } from '@/stores/useUiStore';

// 메타데이터 로드 전 타임라인 fallback 길이(초). 로드되면 실제값으로 대체된다.
const FALLBACK_DURATION_SEC = 60;

/**
 * 마킹 진입 차단 안내 — 두 차단 축이 <b>같은 자리·같은 형태</b>로 사유를 보여 준다.
 * [@design SCREEN-006]
 *
 * ⚠ 공유하는 것은 <b>표시</b>뿐이고 판정과 문구는 축마다 따로다(비식별 축 / 배치 단계 축).
 *   두 축을 한 판정으로 합치면 사유가 다른데 안내는 하나가 되어 서버 거부 사유와 어긋난다.
 */
function MarkingBlockedNotice({ rawSn, reason }: { rawSn: number; reason: string }) {
  return (
    <div role="alert" className="mx-auto max-w-3xl space-y-2 p-8 text-center">
      <h1 className="text-title-md font-semibold text-gray-800">
        마킹 — 영상 #{rawSn}
      </h1>
      <p className="text-body-md text-gray-500">{reason}</p>
    </div>
  );
}

export function MarkingPage() {
  const { rawSn: rawSnParam } = useParams<{ rawSn: string }>();
  const rawSn = rawSnParam ? parseInt(rawSnParam, 10) : undefined;
  const navigate = useNavigate();
  const pushToast = useUiStore((s) => s.pushToast);

  const videoRef = useRef<VideoPlayerHandle>(null);
  // 영상 메타데이터 로드 전에는 fallback(60s), 로드되면 실제 길이로 갱신.
  const [durationSec, setDurationSec] = useState(FALLBACK_DURATION_SEC);
  const {
    mode, intervalFrames, localMarks, selectedMarkIndex,
    setMode, setIntervalFrames, addMark, selectMark,
    removeMark, removeSelectedMark, clearMarks, reset,
  } = useMarkingStore();

  // AC4 백스톱 — URL 직접 진입 시 비식별 미완료면 마킹 화면 진입을 막는다.
  // (BE MarkingService 의 deIdntfYn='Y' 가드가 최종 백스톱이며, 여기선 UX 선차단.)
  const { data: videoDetail } = useVideoDetail(rawSn ?? null);

  // C-ISSUE-01 — frameIndex 는 <b>서버가 내려준 실 fps</b> 로 계산한다.
  //   FE 가 30fps 를 하드코딩하던 동안 서버의 마킹 상한(round(길이×실 fps))과 기준이 갈려,
  //   25fps 영상이면 뒤 16.7% 구간(예: 55초 → 55×30=1650 > 상한 1500)의 정상 마킹이 400 으로
  //   거부됐다. 진실원은 서버 VideoFpsResolver 하나이며 여기서는 그 값을 그대로 쓴다.
  //   서버가 값을 못 내리는 경우에만 동일 폴백값(30)을 사용한다.
  const markingFps = resolveMarkingFps(videoDetail?.fps);

  // [@design SCREEN-006] [@design API-047] [@design API-043] 검증 이벤트 유형·질문 선택 —
  //   <b>두 목록 모두 출처가 영상 단건 조회 응답</b>이다. 관리 화면 경로
  //   (/v1/manage/verification-event-types)는 검수자 전용이라 작업자에게 403 이며, 그걸 부르면 이
  //   화면이 작업자에게 통째로 깨진다. BE 가 정렬순서 오름차순으로 내려주므로 다시 정렬하지 않는다.
  //
  // [@design SCREEN-006] [@design API-043] 검증 이벤트 유형 선택 — <b>관제 값이 없을 때만</b> 노출한다.
  //   서버가 그 판정을 이미 내려 목록으로 표현한다: 관제 인입에서 유형을 받은 영상에는 <b>빈 배열</b>을
  //   내려 준다. 그래서 화면은 목록이 비었는지만 보고 판정하고 `vrfcEvntTypeCd` 의 null 여부로 다시
  //   유도하지 않는다 — 판정이 두 곳으로 갈리면 한쪽만 조용히 낡는다.
  //   ⚠ 「표시하되 잠근다」가 아니다(그 안은 미채택). 노출 자체를 가르므로 작업자 선택과 관제 값이
  //     맞붙는 상태가 구조적으로 생기지 않는다.
  const selectableVrfcEvntTypes = useMemo(
    () => videoDetail?.selectableVrfcEvntTypes ?? [],
    [videoDetail?.selectableVrfcEvntTypes],
  );
  const typeChoiceOffered = selectableVrfcEvntTypes.length > 0;
  const [selectedTypeCd, setSelectedTypeCd] = useState<string | null>(null);

  // 고른 유형이 목록에서 사라지면(관제 값이 뒤늦게 도착해 목록이 비는 경우 포함) 선택을 버린다.
  //   ★ 버리지 않으면 <b>노출되지 않는 선택값이 요청에 실린다</b> — 화면에 보이지 않는 값이 저장되고
  //     외부 위탁까지 나가는 상태라, 사용자가 되돌릴 수단이 없다.
  useEffect(() => {
    setSelectedTypeCd((prev) =>
      prev !== null && selectableVrfcEvntTypes.some((t) => t.vrfcEvntTypeCd === prev) ? prev : null,
    );
  }, [selectableVrfcEvntTypes]);

  // 질문 목록의 조달처는 <b>유형 선택을 노출했는지</b>에 따라 갈린다.
  //   ① 관제 값이 있는 영상 — 서버가 그 유형의 질문을 `vrfcEvntQuestions` 로 이미 실어 준다(종전 그대로).
  //   ② 유형 선택을 노출한 영상 — 그 영상의 `vrfcEvntQuestions` 는 <b>구조적으로 빈 배열</b>이다
  //      (서버가 관제 수신 유형으로 조회하는데 그 값이 없다). 그래서 <b>다시 조회해도</b> 고른 유형의
  //      질문은 오지 않는다. 유형별 질문을 실어 보낼 자리는 목록 항목뿐이므로 거기서 읽는다.
  //   ⚠ 관리 화면 경로(/v1/manage/…)로 조달하지 않는다 — 검수자 전용이라 작업자에게 403 이고
  //     그걸 부르면 이 화면이 통째로 깨진다.
  const vrfcEvntQuestions = useMemo(() => {
    if (!typeChoiceOffered) return videoDetail?.vrfcEvntQuestions ?? [];
    if (selectedTypeCd === null) return [];
    return (
      selectableVrfcEvntTypes.find((t) => t.vrfcEvntTypeCd === selectedTypeCd)?.questions ?? []
    );
  }, [typeChoiceOffered, videoDetail?.vrfcEvntQuestions, selectableVrfcEvntTypes, selectedTypeCd]);
  const [selectedQstnSn, setSelectedQstnSn] = useState<number | null>(null);

  // 기본 선택 = 정렬순서 첫 번째. 목록이 도착하거나 영상이 바뀌면 다시 맞춘다.
  //   ★ 이미 고른 값이 새 목록에도 있으면 그대로 둔다 — 배치 진행 중에는 영상 상세가 5초마다
  //     폴링되는데, 매번 첫 번째로 되돌리면 작업자가 고른 질문이 조용히 바뀐다.
  //   ★ 없어진 값은 첫 번째로 되돌린다(폴백 없이 두면 서버가 어차피 교정하는 값을 화면만 붙들고 있게 된다).
  useEffect(() => {
    setSelectedQstnSn((prev) =>
      prev !== null && vrfcEvntQuestions.some((q) => q.vrfcEvntQstnSn === prev)
        ? prev
        : (vrfcEvntQuestions[0]?.vrfcEvntQstnSn ?? null),
    );
  }, [vrfcEvntQuestions]);

  // 비식별 누락 신고 — 마킹 화면(영상 단위) 진입점.
  //   영상 자체가 신고 대상이 아닌 경우(파생영상 · 검수 승인 영상)는 라벨링 화면과 <b>같은 판정기</b>를
  //   쓴다 — 사유 문구를 화면마다 복제하면 한쪽만 갱신돼 어긋난다(@design DFEAT-048 · @req R2).
  //   사유를 다 적고 제출한 뒤에야 거부되는 동선을 없애기 위해, 알 수 있는 시점에 버튼을
  //   비활성화하고 사유를 툴팁으로 알린다.
  //
  // ⚠ <b>[폐기]</b> 구 동작 — 「배치 단계가 마킹 대기가 아님」 사유(`markingStageReason`)를 이 체인에
  //   덧붙여 신고 버튼을 비활성화했다. 그 축은 이제 <b>화면 진입 자체를 차단</b>하고(아래
  //   `notMarkingStage`) 차단이 먼저 return 하므로, 그 사유로 버튼이 비활성화되는 화면은
  //   <b>존재하지 않는다</b>(도달 불가). 확정 사양(SCREEN-006)도 신고 버튼·툴팁 서술에서 그 축을
  //   걷어냈다 — <b>되살리지 말 것</b>. 그 경로의 신고는 라벨링 화면이 담당한다.
  //
  // ★ 우선순위는 BE 평가 순서(DeidentReportService.doReport)와 같아야 한다 —
  //   파생영상 → (비식별 미수행) → 검수 승인. 판정·문구 자체는 공용 판정기가 계속 소유하고,
  //   여기서는 <b>어느 축을 먼저 물을지</b>만 정한다.
  const derivativeReason = resolveDeidentReportUnsupportedReason(
    videoDetail ? { derivative: videoDetail.derivative } : videoDetail,
  );
  const deidentReportUnsupportedReason =
    derivativeReason ?? resolveDeidentReportUnsupportedReason(videoDetail);

  // 배치 단계 축 — 마킹 대기(MARKING_READY)가 아니면 이 화면의 <b>진입을 차단</b>한다(아래 분기).
  // ⚠ videoDetail 이 아직 없으면(로딩 중) false 다 — 확정되지 않은 값으로 차단하면 로딩 구간의
  //   깜빡임이 정상 마킹을 막는다(비식별 축이 `videoDetail &&` 로 지키는 규약과 같다).
  const notMarkingStage = Boolean(videoDetail && videoDetail.status !== 'MARKING_READY');

  // <video> 는 Authorization 헤더를 못 붙이므로 단기 서명 URL 을 발급받아 src 로 사용한다.
  const { data: streamUrl, refetch: refetchStreamUrl } = useStreamUrl(rawSn);

  // 재생 실패 시 재발급·재시도는 <b>연속 실패 상한 안에서만</b> 한다.
  // [@design API-114] [@design SCREEN-006]
  //   구 동작은 「진행 중이면 무시」만 두고 횟수를 세지 않아, 회복되지 않는 실패에서
  //   발급→실패→발급이 끝없이 돌았다(실측: 7초에 150회 이상).
  //   상한에 이르면 멈추고 <b>토스트로</b> 알린다 — 이 화면이 이미 오류를 알리는 수단이며
  //   사양에 없는 새 표면을 만들지 않는다.
  //   ★재생이 회복되면 예산을 되돌린다(handlePlaybackRecovered) — 서명 수명이 짧아 한 영상을
  //   길게 마킹하는 동안 만료가 여러 번 일어나므로, 누적으로 세면 정상 동선이 막힌다.
  const { handleSrcError: handleStreamError, handlePlaybackRecovered } = useStreamPlaybackRetry({
    reissue: refetchStreamUrl,
    resetKey: rawSn,
    onExhausted: () =>
      pushToast({
        variant: 'error',
        message: '영상을 재생할 수 없습니다. 잠시 후 다시 시도해 주세요.',
      }),
  });

  const createMutation = useCreateMarking(rawSn, {
    onSuccess: (data) => {
      clearMarks();
      // DEV_FIX H11 — 마킹 저장(201)과 배치 시작은 별개다. 배치가 시작되지 않았는데도 "배치 처리가
      //   시작됩니다" 라고 알리면 무음 실패가 된다(실제 사례: 검수 소유 상태·비식별 미완료·이미 처리된
      //   영상). BE 가 내려주는 batchTriggered/batchSkipReason 으로 사실대로 알린다.
      if (data.batchTriggered === false) {
        pushToast({
          variant: 'error',
          message: `마킹은 저장되었으나 배치가 시작되지 않았습니다. ${
            data.batchSkipReason ?? '관리자에게 문의하세요.'
          }`,
        });
        navigate('/task');
        return;
      }
      pushToast({ variant: 'success', message: '마킹이 제출되었습니다. 배치 처리가 시작됩니다.' });
      navigate('/task');
    },
    // 이벤트 유형(evntTypeCd)이 없는 영상이면 BE 가 400(INVALID_INPUT)을 내린다.
    // onError 가 없으면 조용히 실패하므로 에러 토스트로 사용자에게 알린다.
    onError: (err) => {
      pushToast({
        variant: 'error',
        message: extractBeMessage(
          err,
          '마킹 생성에 실패했습니다. 이벤트 유형이 없는 영상일 수 있습니다.',
        ),
      });
    },
  });

  useEffect(() => {
    reset();
    // 영상이 바뀌면 길이를 fallback 으로 되돌리고 새 영상 메타데이터 로드를 기다린다.
    setDurationSec(FALLBACK_DURATION_SEC);
    return () => reset();
  }, [rawSn, reset]);

  const handleDurationChange = useCallback((sec: number) => {
    setDurationSec(sec);
  }, []);

  const handleAddMarkAtCurrentTime = useCallback(() => {
    if (!videoRef.current) return;
    const frameIndex = videoRef.current.getCurrentFrame(markingFps);
    const time = videoRef.current.getCurrentTime();
    const mm = String(Math.floor(time / 60)).padStart(2, '0');
    const ss = String(Math.floor(time % 60)).padStart(2, '0');
    addMark({ frameIndex, timestamp: `${mm}:${ss}` });
  }, [addMark, markingFps]);

  const handleSubmit = useCallback(() => {
    if (rawSn === undefined) return;
    // 중복 제출 차단(FE 방어) — 제출 in-flight 중 Enter 연타/버튼 재클릭 시 마킹 POST 가 중복
    // 발화하지 않도록 pending 을 선두에서 가드한다. (서버 idempotency 와 별개의 클라이언트 가드)
    if (createMutation.isPending) return;
    // 이벤트명은 영상의 evntTypeCd 에서 서버가 자동 소싱하므로 요청에 포함하지 않는다.
    // 고른 질문이 있을 때만 싣는다 — 고를 것이 없으면 필드를 만들지 않는다(값을 지어내지 않는다).
    //   ⚠ 서버는 어긋난 값을 400 이 아니라 <b>그 유형의 첫 번째 질문으로 교정</b>하고 교정 결과를
    //     응답으로 돌려주지 않는다. 화면은 그 교정을 전제로 하며 「교정됨」 표시를 만들지 않는다.
    // ★ 유형 선택을 노출한 영상에서는 유형이 <b>필수</b>다 — 고르지 않으면 그 영상은 질문도
    //   시계열 메타도 하나도 받지 못하는 상태가 그대로 굳는다(그것을 풀려고 만든 선택이다).
    //   ⚠ 완료 버튼을 죽여서 막지 않는다 — 이 화면의 확정 사양은 버튼을 누를 수 있게 두고 사유를
    //     토스트로 말하는 것이다(마크 0건 축과 같다). 조용한 early return 이면 스크린리더 사용자에게
    //     아무 신호도 남지 않는다.
    if (typeChoiceOffered && selectedTypeCd === null) {
      pushToast({
        variant: 'warning',
        message: '검증 이벤트 유형을 골라 주세요.',
      });
      return;
    }
    const questionPayload =
      selectedQstnSn !== null ? { vrfcEvntQstnSn: selectedQstnSn } : {};
    // 유형은 <b>선택을 노출한 영상에서만</b> 싣는다 — 관제 값이 있는 영상에는 필드 자체를 만들지
    // 않는다(서버가 관제 인입을 1순위로 쓰므로 실어 봐야 무시되고, 두 조달처가 맞붙는 것처럼 보인다).
    //   ⚠ `typeChoiceOffered` 검사는 위 정리 effect 덕분에 <b>지금은 중복</b>이다(선택이 목록에서
    //     사라지면 값이 이미 버려진다). 변이시험에서 이 조건만 지워도 죽지 않는 이유가 그것이며,
    //     남겨 두는 것은 「노출하지 않은 값은 보내지 않는다」를 <b>보내는 자리에서도</b> 말하기
    //     위해서다 — 정리 effect 가 나중에 바뀌어도 이 자리는 그대로 지킨다.
    const typePayload =
      typeChoiceOffered && selectedTypeCd !== null ? { vrfcEvntTypeCd: selectedTypeCd } : {};
    if (mode === 'AUTO') {
      if (!intervalFrames || intervalFrames < 1) return;
      createMutation.mutate({
        mode: 'AUTO',
        intervalFrames,
        ...typePayload,
        ...questionPayload,
      });
    } else {
      // ★마크 0건은 **버튼을 죽여서** 막지 않는다 (확정 사양) — 버튼은 항상 누를 수 있고,
      //   눌렀을 때 사유를 토스트로 말하며 제출만 막는다. 조용한 early return 이면 사용자는
      //   "눌렀는데 아무 일도 없다" 만 겪고, 스크린리더 사용자에게는 아무 신호도 남지 않는다.
      //   (토스트는 role="alert" + aria-live 라 보조기술에도 읽힌다.)
      if (localMarks.length === 0) {
        pushToast({
          variant: 'warning',
          message: '재생하며 마킹을 1건 이상 쌓아 주세요.',
        });
        return;
      }
      createMutation.mutate({
        mode: 'MANUAL',
        marks: localMarks,
        ...typePayload,
        ...questionPayload,
      });
    }
  }, [
    mode,
    intervalFrames,
    localMarks,
    rawSn,
    createMutation,
    pushToast,
    selectedQstnSn,
    typeChoiceOffered,
    selectedTypeCd,
  ]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement).tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;

      // ★모드 전환(1=수동 / 2=자동)은 **항상 발화**한다 — 아래 마킹 조작(Space/Del/Enter)이
      //   수동 모드 전용인 것과 다른 축이다. 구 코드는 `mode !== 'MANUAL'` 조기 리턴이 맨 앞에
      //   있어 **자동 모드에서 수동으로 돌아오는 키가 아예 없었다**(단축키 0건).
      if (e.code === 'Digit1') {
        e.preventDefault();
        setMode('MANUAL');
        return;
      }
      if (e.code === 'Digit2') {
        e.preventDefault();
        setMode('AUTO');
        return;
      }

      if (mode !== 'MANUAL') return;

      if (e.code === 'Space') {
        e.preventDefault();
        handleAddMarkAtCurrentTime();
      } else if (e.code === 'Delete' || e.code === 'Backspace') {
        e.preventDefault();
        removeSelectedMark();
      } else if (e.code === 'Enter') {
        e.preventDefault();
        handleSubmit();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [mode, setMode, handleAddMarkAtCurrentTime, removeSelectedMark, handleSubmit]);

  if (rawSn === undefined || isNaN(rawSn)) {
    return <div className="p-8 text-center text-gray-500">잘못된 영상 ID입니다.</div>;
  }

  // 비식별 미완료 영상은 마킹 진입 차단(백스톱) — 영상 상세가 로드되어 미완료가 확정될 때만.
  if (videoDetail && isMarkingBlocked(videoDetail)) {
    return <MarkingBlockedNotice rawSn={rawSn} reason="비식별 완료 후 마킹이 가능합니다." />;
  }

  // 배치 단계 축 — 마킹 대기(MARKING_READY)가 아니면 진입 차단. [@design SCREEN-006]
  //
  // ★ 평가 순서는 BE(MarkingGuards)와 같게 <b>비식별 축이 먼저</b>다 — 순서가 갈리면 두 축에 모두
  //   걸린 영상에서 화면 안내와 서버 거부 사유가 달라진다.
  // ★ 선차단하는 이유: BE 는 이 축을 제출 시점에 412 로 막으므로, 화면이 열려 있으면 작업자가
  //   마크를 다 쌓은 뒤에야 거부된다(이미 처리된 영상에서 편집기가 멀쩡히 열리던 결함).
  // ⚠ 이 화면에서는 비식별 누락 신고 버튼도 함께 사라진다 — 그 상태의 신고 버튼은 어차피 사유와
  //   함께 비활성이었고, 그 경로의 신고는 라벨링 화면이 담당한다(기능 손실이 아니다).
  if (notMarkingStage) {
    return (
      <MarkingBlockedNotice
        rawSn={rawSn}
        reason="이미 다음 단계로 넘어간 영상이라 마킹할 수 없습니다."
      />
    );
  }

  // 발급받은 주소는 <b>배포 접두를 뺀 API 기준 경로</b>다 — 발급하는 쪽은 자신이 어느 컨텍스트
  // 아래에 놓이는지 알 수 없기 때문이다. 이 화면이 아는 접두를 앞에 붙여야 요청이 우리 창구에
  // 도달한다. 붙이지 않으면 같은 오리진에 놓인 다른 시스템의 경로로 나가 영상이 오지 않는다.
  // ⚠ 루트에 서비스되는 배포에서는 두 주소가 우연히 같아 이 어긋남이 드러나지 않는다.
  // [@design API-114] [@design SCREEN-006] [@design SEQ-036]
  const videoSrc = toDeployedApiUrl(streamUrl?.url);

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-title-md font-semibold">마킹 — 영상 #{rawSn}</h1>
        {/* 마킹 중 개인정보 노출 발견 시 신고(영상 단위). 라벨링 화면과 같은 컴포넌트를 재사용한다.
            ⚠ 배치 단계 진행 표시(BatchStageIndicator)는 이 헤더에 두지 않는다 — 마킹 완료가 잔여
            배치의 트리거라 이 화면에 머무는 동안 뒷단은 시작될 수 없고, 확인할 진행이 존재하지
            않는다. 그 표시기는 영상 상세 화면이 계속 쓴다(컴포넌트는 존치). [@design SCREEN-006] */}
        <DeidentReportButton
          rawSn={rawSn}
          unsupportedReason={deidentReportUnsupportedReason}
          onSuccess={() => navigate('/task')}
        />
      </div>

      {videoSrc ? (
        <VideoPlayer
          ref={videoRef}
          src={videoSrc}
          sourceKey={rawSn}
          onSrcError={handleStreamError}
          onSrcRecovered={handlePlaybackRecovered}
          onDurationChange={handleDurationChange}
        />
      ) : (
        <div className="flex aspect-video w-full items-center justify-center rounded-lg bg-black text-body-md text-gray-400">
          영상을 불러오는 중…
        </div>
      )}

      <MarkingTimeline
        marks={localMarks}
        durationSec={durationSec}
        fps={markingFps}
        selectedIndex={selectedMarkIndex}
        onSelect={selectMark}
      />

      <MarkingToolbar
        mode={mode}
        intervalFrames={intervalFrames}
        onModeChange={(m: MarkingMode) => setMode(m)}
        onIntervalFramesChange={setIntervalFrames}
        onSubmit={handleSubmit}
        onClear={clearMarks}
        submitting={createMutation.isPending}
        markCount={localMarks.length}
      />

      {/* 마킹 칩 목록 — 0건이어도 패널을 감추지 않는다.
          구 구현은 `localMarks.length > 0` 일 때만 렌더해 패널이 통째로 사라졌고, 그러면 사용자가
          <b>"이 화면엔 그런 기능이 없다"</b>와 <b>"아직 마킹을 안 했다"</b>를 구분할 수 없었다.
          확정 사양은 0건이면 빈 상태 안내를 노출하는 것이다. */}
      <div className="rounded border border-gray-200 p-3 text-body-md">
        <h3 className="mb-2 font-medium text-gray-700">현재 마킹 ({localMarks.length}건)</h3>
        {localMarks.length === 0 ? (
          // 안내 문구는 모드별로 다르다 — 자동 모드에서는 Space 단축키가 아예 발화하지 않으므로
          // (위 keydown 핸들러의 `mode !== 'MANUAL'` 조기 리턴) 수동 모드 안내를 그대로 보여주면
          // 눌러도 아무 일이 없는 키를 알려주는 거짓 안내가 된다.
          <EmptyState
            title="추가한 마킹이 없습니다"
            message={
              mode === 'MANUAL'
                ? '영상을 재생하다 이벤트 시점에서 Space 키를 누르면 마킹이 추가됩니다.'
                : '자동 모드는 간격(프레임)만 지정하면 되며 개별 마킹을 추가하지 않습니다.'
            }
            className="py-6"
          />
        ) : (
          <div className="flex flex-wrap gap-2">
            {localMarks.map((mark: MarkItem, i: number) => {
              const selected = selectedMarkIndex === i;
              return (
                // 칩 = 선택 버튼 + 개별 삭제 버튼 2개를 나란히 둔 그룹. 칩 전체를 <button> 으로
                // 감싸면 삭제 버튼이 버튼 안의 버튼(중첩)이 되어 유효하지 않은 마크업이 된다.
                <span
                  key={mark.frameIndex}
                  className={`inline-flex items-stretch overflow-hidden rounded text-caption ${
                    selected ? 'bg-primary-600 text-white' : 'bg-gray-100 text-gray-700'
                  }`}
                >
                  <button
                    type="button"
                    onClick={() => selectMark(i)}
                    className={`px-2 py-1 transition-colors ${
                      selected ? 'hover:bg-primary-700' : 'hover:bg-gray-200'
                    }`}
                  >
                    F{mark.frameIndex} {mark.timestamp && `(${mark.timestamp})`}
                  </button>
                  {/* 개별 삭제 — 마우스만 쓰는 사용자에게도 삭제 수단을 준다(Del 단축키는 그대로 유지).
                      아이콘만 있는 버튼이라 접근성 이름을 aria-label 로 따로 주며, 어느 마킹을
                      지우는지 알 수 있도록 타임라인과 <b>같은 표기</b>(markAriaLabel)를 덧붙인다. */}
                  <button
                    type="button"
                    onClick={() => removeMark(i)}
                    aria-label={`마킹 삭제 ${markAriaLabel(mark)}`}
                    className={`px-1.5 py-1 transition-colors ${
                      selected ? 'hover:bg-primary-700' : 'hover:bg-gray-200'
                    }`}
                  >
                    <X className="h-3.5 w-3.5" aria-hidden />
                  </button>
                </span>
              );
            })}
          </div>
        )}
      </div>

      {/* [@design SCREEN-006] [@design API-043] 검증 이벤트 유형 선택 — 관제가 유형을 보내지 않은
          영상에서만 뜬다(서버가 그런 영상에만 목록을 채워 준다). 관제 값이 있으면 컴포넌트가 스스로
          아무것도 렌더하지 않는다. */}
      <VerificationEventTypeSelect
        types={selectableVrfcEvntTypes}
        value={selectedTypeCd}
        onChange={setSelectedTypeCd}
        disabled={createMutation.isPending}
      />

      {/* [@design SCREEN-006] 검증 질문 선택 — 고를 질문이 하나도 없으면 컴포넌트가 스스로
          아무것도 렌더하지 않는다(빈 드롭다운은 "고를 수 있는데 비어 있다"로 읽힌다).
          질문이 없다고 마킹이 막히지는 않는다 — 어노테이션의 질문 칸이 비는 것뿐이다.
          유형 선택을 노출한 영상에서는 유형을 고른 뒤에 이 목록이 열린다. */}
      <VerificationQuestionSelect
        questions={vrfcEvntQuestions}
        value={selectedQstnSn}
        onChange={setSelectedQstnSn}
        disabled={createMutation.isPending}
      />
    </div>
  );
}
