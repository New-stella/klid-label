/**
 * 포털 업로드 마킹 무대 — 영상 · 마킹 눈금 · 재생 조작을 **한 표면**에 담는다. [@design SCREEN-045]
 * [@design DS-002]
 *
 * <h3>겉모습을 부모 포털 부품으로 갈아입혔다 (2026-09-16)</h3>
 * 부모 포털이 이 화면을 자기 부품으로 다시 그려 「저작도구 쪽에 넘기는 기준」으로 삼았고
 * (`pages/workspace/authoring/AuthoringMarkingView`), 그 짜임을 그대로 옮겼다.
 *   · 판 = `klid-section-card klid-marking-player` (styles/portal/marking-view.css — ⚠ 화면·부품이
 *     그 CSS 를 스스로 import 하지 않는다. 포털 채널 스타일의 단일 지점은 `styles/portalLook.ts` 다)
 *   · 영상 자리 = `VideoStage` · 눈금 = `MarkTimeline` · 재생 줄 = `PlaybackBar` · 배속 = `Dropdown`
 * ⚠ **재생 로직·창구·신호는 그대로다.** 바뀐 것은 무엇으로 그리느냐뿐이며, 이 부품이 내는
 *   신호(`onSrcError`·`onSrcRecovered`·`onDurationChange`)와 imperative 창구 둘은 손대지 않았다.
 *
 * <h3>왜 관제 채널 재생기를 쓰지 않나</h3>
 * `features/marking/components/VideoPlayer` 는 <b>관제 마킹 화면과 함께 쓰는 부품</b>이다
 * (`pages/MarkingPage.tsx`). 거기에 포털 모양을 넣으면 관제 화면이 같이 바뀐다 — 사용자 확정
 * 구속(<b>관제향 화면·컴포넌트 불변</b>)에 걸린다. 그래서 포털은 자기 무대를 갖는다.
 *
 * ⚠ 그 대가로 재생 로직이 두 벌이 된다. 그래서 <b>이 무대가 밖으로 내는 창구를 최소로 좁혔다</b> —
 *   포털 마킹 화면이 실제로 쓰는 것은 「지금 위치를 묻는다 · 그 자리로 옮긴다」 둘뿐이라,
 *   관제 재생기의 나머지 창구(프레임 번호 환산 · 길이 조회)를 따라 만들지 않는다.
 *   ★프레임 번호 환산은 <b>여기서 하지 않는다</b> — 그 공식은 `markingFps` 한 곳이 소유하고
 *   호출부가 시각을 받아 그 모듈에 묻는다(공식을 두 벌로 만들지 않는다).
 *
 * <h3>세 조각을 왜 한 표면에 두나</h3>
 * 셋 다 <b>같은 시간축</b>을 말한다 — 재생 위치 · 마킹 지점 · 탐색 막대. 한 판에 세로로 쌓으면
 * 세 축이 같은 가로 좌표를 공유해 그 연결이 화면에 드러난다. 눈금에는 재생 머리가 함께 있어
 * «여기서 찍으면 저 자리에 선다» 가 한눈에 읽힌다(`MarkTimeline` 의 `position`).
 *
 * <h3>지점은 «번호» 로 오간다 — 자리 번호가 아니다</h3>
 * 부모 포털 부품(`MarkTimeline`·`MarkPointList`)은 지점을 <b>프레임 번호</b>로 주고받는데 이
 * 화면의 상태는 <b>목록에서의 자리(index)</b>다. 그 환산을 이 부품이 맡아 호출부의 계약
 * (`selectedIndex` · `onSelectMark(index)`)을 그대로 지킨다 — 화면 쪽 상태를 건드리면 단축키
 * 삭제(`removeMarkAt`)까지 함께 흔들린다.
 */
import { forwardRef, useCallback, useImperativeHandle, useMemo, useRef, useState } from 'react';

import { MarkTimeline, PlaybackBar, VideoStage } from '@/components/portal/authoring';
import { Dropdown, type DropdownOption } from '@/components/portal/kit';
import { markAriaLabel } from '@/features/marking/components/MarkingTimeline';

import type { MarkItem } from '../markingTypes';


/** 이 무대가 밖으로 내는 창구 — 호출부가 실제로 쓰는 둘뿐이다(위 ⚠ 참조). */
export interface PortalMarkingStageHandle {
  /** 지금 재생 위치(초). 영상이 아직 없으면 0. */
  getCurrentTime: () => number;
  /** 그 시각으로 옮긴다. */
  seekTo: (timeSec: number) => void;
}

export interface PortalMarkingStageProps {
  src: string;
  /** 눈금에 그릴 마킹 지점. */
  marks: MarkItem[];
  /** 눈금 좌표의 분모가 되는 영상 길이(초). 0 이면 눈금을 그리지 않는다. */
  durationSec: number;
  /**
   * 영상 실 초당 프레임 수. 마킹 지점의 좌표는 `프레임 번호 / 총 프레임` 이므로 그 번호를 만들 때
   * 쓴 값과 <b>같아야 한다</b> — 다르면 막대가 실제와 어긋난 자리에 선다.
   */
  fps: number;
  selectedIndex: number | null;
  onSelectMark: (index: number) => void;
  /** 재생 주소가 만료돼 로드가 실패했을 때 — 호출부가 주소를 다시 받아 `src` 를 갈아 준다. */
  onSrcError?: () => void;
  /**
   * 이 소스를 재생할 수 있게 됐다 = **재생이 회복됐다.**
   *
   * 화면은 이 신호로 재발급 재시도 예산을 되돌린다. 없으면 예산이 누적으로만 줄어
   * 서명이 여러 번 만료되는 정상 동선에서 **회복 경로가 영구히 닫힌다**
   * (마킹은 오래 머무는 화면이라 만료가 여러 번 일어난다).
   */
  onSrcRecovered?: () => void;
  /** 메타데이터에서 읽은 실제 길이(초). 원장 길이가 없을 때의 눈금 분모로 쓰인다. */
  onDurationChange?: (sec: number) => void;
}

/** 배속 — 관제 마킹과 같은 여섯 단계다(사용자가 채널을 오가며 다른 선택지를 만나지 않게). */
const SPEED_OPTIONS: DropdownOption[] = ['0.25', '0.5', '1', '1.5', '2', '4'].map((v) => ({
  value: v,
  label: `${v}x`,
}));

/** `mm:ss`. 길이를 모르는 동안(NaN·Infinity)에도 자리를 지킨다. */
function formatClock(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return '00:00';
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

export const PortalMarkingStage = forwardRef<PortalMarkingStageHandle, PortalMarkingStageProps>(
  function PortalMarkingStage(
    {
      src, marks, durationSec, fps, selectedIndex, onSelectMark,
      onSrcError, onSrcRecovered, onDurationChange,
    },
    ref,
  ) {
    const videoRef = useRef<HTMLVideoElement>(null);
    const [playing, setPlaying] = useState(false);
    const [currentTime, setCurrentTime] = useState(0);
    const [duration, setDuration] = useState(0);
    const [rate, setRate] = useState('1');
    const [buffering, setBuffering] = useState(false);

    useImperativeHandle(ref, () => ({
      getCurrentTime: () => videoRef.current?.currentTime ?? 0,
      seekTo: (timeSec: number) => {
        if (videoRef.current) videoRef.current.currentTime = timeSec;
      },
    }));

    const togglePlay = useCallback(() => {
      const v = videoRef.current;
      if (!v) return;
      if (v.paused) void v.play();
      else v.pause();
    }, []);

    const changeRate = useCallback((next: string) => {
      const n = Number(next);
      if (!Number.isFinite(n) || n <= 0) return;
      if (videoRef.current) videoRef.current.playbackRate = n;
      setRate(next);
    }, []);

    const handleLoadedMetadata = useCallback(() => {
      const d = videoRef.current?.duration ?? 0;
      setDuration(d);
      // 유효한 길이일 때만 알린다 — 일부 스트림은 길이가 확정되지 않아 NaN·Infinity 로 온다.
      if (Number.isFinite(d) && d > 0) onDurationChange?.(d);
    }, [onDurationChange]);

    const seekTo = useCallback((t: number) => {
      if (videoRef.current) videoRef.current.currentTime = t;
      setCurrentTime(t);
    }, []);

    // 탐색 막대의 최댓값 — 원장 길이가 없으면 재생 요소가 읽은 길이를 쓴다.
    const seekMax = duration > 0 ? duration : durationSec;
    const totalFrames = durationSec * fps;
    const showRuler = totalFrames > 0;

    /* 프레임 번호 ↔ 자리 번호 환산(위 <h3>). 같은 번호가 두 번 담기지 않도록 호출부가 이미
       걸러 두므로 번호 하나가 자리 하나를 가리킨다. */
    const markFrames = useMemo(() => marks.map((m) => m.frameIndex), [marks]);
    const selectedFrame =
      selectedIndex !== null ? (marks[selectedIndex]?.frameIndex ?? null) : null;
    const pickFrame = useCallback(
      (frame: number) => {
        const i = marks.findIndex((m) => m.frameIndex === frame);
        if (i >= 0) onSelectMark(i);
      },
      [marks, onSelectMark],
    );
    /* 눈금 막대의 접근성 이름 — 관제 마킹과 같은 규칙(`F{프레임}·mm:ss`)을 그대로 쓴다. */
    const frameLabel = useCallback(
      (frame: number) => {
        const mark = marks.find((m) => m.frameIndex === frame);
        return mark ? markAriaLabel(mark) : `F${frame}`;
      },
      [marks],
    );

    return (
      <section className="klid-section-card klid-marking-player" aria-label="영상 재생">
        <VideoStage
          status={buffering ? 'buffering' : 'ready'}
          message="불러오는 중"
          media={
            // 자막 트랙 미제공 — 이용자가 올린 영상의 음성을 저작도구가 옮겨 적지 않는다.
            // eslint-disable-next-line jsx-a11y/media-has-caption
            <video
              ref={videoRef}
              src={src}
              /* ⚠ 판 CSS 는 `object-fit: cover` 다(대표 이미지 기준). 마킹은 **보이는 장면을
                 근거로 지점을 찍는 일**이라 잘라 내면 안 보이는 구간이 생기므로 이 화면에서만
                 «담기» 로 되돌린다. 킷 파일은 고치지 않는다(복사본 규약). */
              style={{ objectFit: 'contain' }}
              onTimeUpdate={() => setCurrentTime(videoRef.current?.currentTime ?? 0)}
              onLoadedMetadata={handleLoadedMetadata}
              onPlay={() => setPlaying(true)}
              onPause={() => setPlaying(false)}
              onError={() => onSrcError?.()}
              onWaiting={() => setBuffering(true)}
              onSeeking={() => setBuffering(true)}
              onCanPlay={() => {
                setBuffering(false);
                // 재생 가능해진 것이 곧 회복 신호다 — `onPlaying` 이 아니라 여기다.
                // 이용자가 재생을 누르지 않아도 소스가 되살아난 것은 사실이기 때문이다.
                onSrcRecovered?.();
              }}
              onPlaying={() => setBuffering(false)}
              onSeeked={() => setBuffering(false)}
            />
          }
        />

        <div className="klid-marking-controls">
          {/* 마킹 눈금 — 영상 바로 아래, 같은 가로 좌표축 위에 둔다. 길이를 모르면 그리지 않는다
              (0 에 붙은 막대는 «맨 앞» 이라는 거짓을 말한다). */}
          {showRuler && (
            <MarkTimeline
              marks={markFrames}
              total={totalFrames}
              markLabel={frameLabel}
              onSeek={pickFrame}
              position={currentTime * fps}
              selected={selectedFrame}
            />
          )}

          <div className="klid-marking-playback">
            <PlaybackBar
              playing={playing}
              onToggle={togglePlay}
              position={currentTime}
              max={seekMax || 1}
              step={0.01}
              onSeek={seekTo}
              seekValueText={formatClock(currentTime)}
              lead={`${formatClock(currentTime)} / ${formatClock(seekMax)}`}
            />
            {/* 배속은 재생 줄 뒤가 아니라 **한 줄 아래 끝**에 둔다 — 줄 끝에 꽂으면 좁은 폭에서
                위치 막대가 눌린다(부모 포털 실측). 읽는 순서(오른쪽 끝)는 그대로다. */}
            <div className="klid-marking-speed">
              <Dropdown
                size="small"
                aria-label="재생 속도"
                options={SPEED_OPTIONS}
                value={rate}
                onChange={changeRate}
              />
            </div>
          </div>
        </div>
      </section>
    );
  },
);
