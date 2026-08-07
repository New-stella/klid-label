import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { BatchStageIndicator } from '@/components/common/BatchStageIndicator';
import { DeidentReportButton } from '@/features/label/components/DeidentReportButton';
import { MarkingTimeline } from '@/features/marking/components/MarkingTimeline';
import { MarkingToolbar } from '@/features/marking/components/MarkingToolbar';
import { VideoPlayer, type VideoPlayerHandle } from '@/features/marking/components/VideoPlayer';
import { useCreateMarking } from '@/features/marking/hooks/useMarkings';
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
    removeSelectedMark, clearMarks, reset,
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

  // 비식별 누락 신고 — 마킹 화면(영상 단위) 진입점.
  //  ① 파생영상은 서버가 접수하지 않는다(원본의 비식별 결과를 복사한 사본이라 재처리 수단이 없다).
  //  ② 마킹 단계가 아닌 영상(이미 다음 단계로 넘어감)도 서버가 접수하지 않는다.
  // 두 경우 모두 사유를 다 적고 제출한 뒤에야 거부되는 동선을 없애기 위해, 알 수 있는 시점에
  // 버튼을 비활성화하고 사유를 툴팁으로 알린다(라벨링 화면과 동일 관례).
  const deidentReportUnsupportedReason = videoDetail?.derivative
    ? '증강·해상도 변환으로 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다.'
    : videoDetail && videoDetail.status !== 'MARKING_READY'
      ? '이미 다음 단계로 넘어간 영상이라 이 화면에서는 신고할 수 없습니다. 라벨링 화면에서 신고해 주세요.'
      : undefined;

  // <video> 는 Authorization 헤더를 못 붙이므로 단기 서명 URL 을 발급받아 src 로 사용한다.
  const { data: streamUrl, refetch: refetchStreamUrl } = useStreamUrl(rawSn);
  // 만료(401)로 인한 재발급 무한루프 방지 — 에러당 1회만 재발급.
  const streamRetriedRef = useRef(false);

  const handleStreamError = useCallback(() => {
    if (streamRetriedRef.current) return;
    streamRetriedRef.current = true;
    void refetchStreamUrl().finally(() => {
      // 다음 만료 시 다시 1회 재시도 허용
      streamRetriedRef.current = false;
    });
  }, [refetchStreamUrl]);

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
    if (mode === 'AUTO') {
      if (!intervalFrames || intervalFrames < 1) return;
      createMutation.mutate({
        mode: 'AUTO',
        intervalFrames,
      });
    } else {
      if (localMarks.length === 0) return;
      createMutation.mutate({
        mode: 'MANUAL',
        marks: localMarks,
      });
    }
  }, [mode, intervalFrames, localMarks, rawSn, createMutation]);

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
    return (
      <div
        role="alert"
        className="mx-auto max-w-3xl space-y-2 p-8 text-center"
      >
        <h1 className="text-title-md font-semibold text-gray-800">
          마킹 — 영상 #{rawSn}
        </h1>
        <p className="text-body-md text-gray-500">
          비식별 완료 후 마킹이 가능합니다.
        </p>
      </div>
    );
  }

  const videoSrc = streamUrl?.url ?? '';

  return (
    <div className="mx-auto max-w-5xl space-y-4 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-title-md font-semibold">마킹 — 영상 #{rawSn}</h1>
        <div className="flex flex-wrap items-center gap-3">
          {/* 배치 단계 진행 표시 — BE stages 있으면 노출, 없으면 미표시(하위호환). */}
          {videoDetail?.stages && videoDetail.stages.length > 0 && (
            <div className="overflow-x-auto">
              <BatchStageIndicator stages={videoDetail.stages} />
            </div>
          )}
          {/* 마킹 중 개인정보 노출 발견 시 신고(영상 단위). 라벨링 화면과 같은 컴포넌트를 재사용한다. */}
          <DeidentReportButton
            rawSn={rawSn}
            unsupportedReason={deidentReportUnsupportedReason}
            onSuccess={() => navigate('/task')}
          />
        </div>
      </div>

      {videoSrc ? (
        <VideoPlayer
          ref={videoRef}
          src={videoSrc}
          onSrcError={handleStreamError}
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

      {localMarks.length > 0 && (
        <div className="rounded border p-3 text-body-md">
          <h3 className="mb-2 font-medium text-gray-700">현재 마킹 ({localMarks.length}건)</h3>
          <div className="flex flex-wrap gap-2">
            {localMarks.map((mark: MarkItem, i: number) => (
              <button
                key={`${mark.frameIndex}`}
                type="button"
                onClick={() => selectMark(i)}
                className={`rounded px-2 py-1 text-caption transition-colors ${
                  selectedMarkIndex === i
                    ? 'bg-primary-600 text-white'
                    : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
                }`}
              >
                F{mark.frameIndex} {mark.timestamp && `(${mark.timestamp})`}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
