// 온디맨드 자동 추적 — 트랙 편집 영역의 실행·검토 패널.
//
// ★ 실행 버튼 표기는 「AI 자동 추적」이다(확정 사양 SCREEN-005). 화면에 드러나는 문구에는 내부
//   모델명을 쓰지 않으며, 선택한 객체 하나를 따라가는 「AI 추적」과 이름이 겹치지 않게 구분한다.
//   ⚠ 이 화면의 고충실 시안(SD-002)과 로컬 화면 키트는 아직 구 표기(내부 모델명이 든 이름)를
//     들고 있다 — 정본은 서버 사양이므로 그것을 따른다.
//
// ★ 시작 객체를 고르지 않는다 — 현재 프레임과 뒤따르는 프레임 구간만 있으면 실행된다.
//
// ★ 적용 방식은 두 가지이고 **기본값은 검토 후 수락**이다. 이 실행은 여러 객체를 여러 프레임에
//   걸쳐 한 번에 만들어 오검출의 영향 범위가 단일 객체 추적보다 크므로 자동 반영은 사용자가
//   옵션으로 켜는 쪽이다. 고른 방식은 **그 실행에만** 적용되고 다음 실행은 다시 기본값으로
//   시작한다 — 오검출이 많은 영상에서 이전 선택이 남아 무심코 자동 반영되는 일을 막는다.
//
// ★ 수락·제외의 단위는 **트랙**이다(검출 하나하나를 따로 고르지 않는다). 어느 방식이든 결과는
//   작업 중인 객체 목록에만 들어가고 확정은 저장으로 한다.
//
// @design SCREEN-005, API-123, UC-034

import { useCallback, useRef, useState } from 'react';

import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import { RadioGroup } from '@/components/common/RadioGroup';

import { useAutoTrack } from '../hooks/useAutoTrack';
import type { AutoTrackResponse } from '../api/autoTrack';
import type { Label } from '../types';
import {
  buildAutoTrackReview,
  isEmptyReview,
  mergeGroupsBySrcSn,
  type AutoTrackReview,
} from '../utils/autoTrackResult';

/** 결과 적용 방식. 기본값은 검토 후 수락이다. */
export type AutoTrackApplyMode = 'review' | 'auto';

/** 적용 방식 기본값 — 실행마다 이 값으로 되돌아간다(이전 선택을 기억하지 않는다). */
export const DEFAULT_APPLY_MODE: AutoTrackApplyMode = 'review';

const APPLY_MODE_OPTIONS = [
  { value: 'review', label: '검토 후 수락' },
  { value: 'auto', label: '자동 반영' },
];

/**
 * 작업본 반영 결과 — 안내 문구의 **근거**다.
 *
 * ★ 요청한 건수로 안내하면 거짓말이 된다. 상위 병합은 이미 작업본에 있는 같은 분류·같은 자리 라벨과
 *   겹치는 검출을 건너뛰므로, 전부 걸러지면 실제 반영은 0건인데 패널만 "올렸다"고 말하게 된다.
 *   그래서 반영 주체가 **실제로 올라간 건수**를 돌려주고 패널은 그 값으로만 안내한다.
 */
export interface AutoTrackApplyOutcome {
  /** 실제로 작업본(현재 프레임 병합 + 이후 프레임 보류분)에 올라간 검출 건수. */
  appliedLabels: number;
  /** 이미 있는 라벨과 겹쳐 반영하지 않은 검출 건수. */
  skippedDuplicates: number;
}

export interface AutoTrackPanelProps {
  /** 시작(현재) 프레임 PK. 미상이면 실행할 수 없다. */
  srcSn?: number;
  /** 영상의 프레임 목록 — 응답 프레임에 프레임 번호를 붙이는 사전으로만 쓴다. */
  frames: readonly { srcSn: number; frameNo: number }[];
  /** 뒤따르는 프레임 PK(정렬됨). 비어 있으면 실행할 수 없다. */
  nextSrcSns: readonly number[];
  /**
   * 수락(또는 자동 반영)된 결과를 작업본에 올린다 — 프레임(srcSn) 별 라벨.
   * 저장은 하지 않는다(확정은 라벨 저장 시점).
   *
   * **실제 반영 결과를 돌려줘야 한다** — 중복 제거로 걸러진 건수를 패널이 알 수 없어서
   * 안내가 실제와 어긋난다(반환값을 버리면 그 결함이 되돌아온다).
   */
  onApply: (labelsBySrcSn: Record<number, Label[]>) => AutoTrackApplyOutcome;
  /** 편집 차단(장시간 작업 진행 중·비식별 재처리 중 등)이면 실행 진입을 막는다. */
  disabled?: boolean;
}

export function AutoTrackPanel({
  srcSn,
  frames,
  nextSrcSns,
  onApply,
  disabled = false,
}: AutoTrackPanelProps) {
  const [mode, setMode] = useState<AutoTrackApplyMode>(DEFAULT_APPLY_MODE);
  const [review, setReview] = useState<AutoTrackReview | null>(null);
  const [accepted, setAccepted] = useState<ReadonlySet<string>>(new Set());
  // 안내는 여러 줄이 함께 뜰 수 있다(반영 요약 · 마스터 미연결 제외 · 구간 절단).
  const [notices, setNotices] = useState<readonly string[]>([]);
  // 이 실행에 적용할 방식 — 실행 시점에 붙잡는다. state 는 실행 직후 기본값으로 되돌리므로
  // 결과 도착 시점의 state 를 보면 항상 기본값이 된다.
  const runModeRef = useRef<AutoTrackApplyMode>(DEFAULT_APPLY_MODE);

  // 구간 절단(상한 초과) 안내는 결과 안내와 함께 남아 있어야 한다 — 실행 시점에 붙잡는다.
  const truncatedRef = useRef(0);
  // 진행 표시 — 서버가 시간 예산 때문에 잘라 보내면 화면이 이어 보내는데, 그동안 아무 것도 안
  // 바뀌면 «멈췄나» 로 보인다. 응답 하나마다 갱신된다.
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  // 끝내지 못한 구간 — 수락 버튼을 눌러 안내를 다시 조립할 때도 그 사실이 남아 있어야 한다.
  const unfinishedRef = useRef(0);
  // 이 실행에서 결과를 한 번이라도 받았는가 — 실패 안내 문구를 고르는 근거다(부분 실패 구분).
  const gotPartialRef = useRef(false);

  const applyLabels = useCallback(
    (built: AutoTrackReview, keys: ReadonlySet<string>): AutoTrackApplyOutcome => {
      const bySrcSn = mergeGroupsBySrcSn(built.groups, keys);
      return onApply(bySrcSn);
    },
    [onApply],
  );

  const handleResult = useCallback(
    (res: AutoTrackResponse) => {
      // 실패가 뒤따라 오더라도 «결과를 받긴 했다» 는 사실이 남아야 안내가 그것을 덮지 않는다.
      gotPartialRef.current = true;
      const frameNoBySrcSn = new Map(frames.map((f) => [f.srcSn, f.frameNo] as const));
      const built = buildAutoTrackReview(res, frameNoBySrcSn);
      const allKeys = new Set(built.groups.map((g) => g.key));

      // 서버가 요청 시간 예산 안에 끝내지 못하고 남긴 구간 — 이어 보내도 진행이 없어 멈춘 경우다.
      // 시퀀스는 [시작 프레임] + 후속이라 남은 수도 그 기준으로 센다.
      const unfinished = res.truncated && res.resume ? 1 + res.resume.nextSrcSns.length : 0;
      unfinishedRef.current = unfinished;

      // 올릴 것이 없으면 **두 방식 모두** 반영을 시도하지 않고 사유만 알린다.
      // 자동 반영에만 이 분기가 없어서 "0건을 올렸습니다" 와 "반영할 검출이 없습니다" 가 함께 떴다.
      if (isEmptyReview(built)) {
        setReview(null);
        setAccepted(new Set());
        setNotices([
          ...composeNotices(built, null, truncatedRef.current, unfinished),
          '반영할 검출이 없습니다.',
        ]);
        return;
      }

      if (runModeRef.current === 'auto') {
        // 자동 반영 — 검토 없이 전부 작업본에 올린다(확정은 저장).
        setReview(null);
        setAccepted(new Set());
        const outcome = applyLabels(built, allKeys);
        setNotices(composeNotices(built, outcome, truncatedRef.current, unfinished));
        return;
      }
      // 검토 후 수락 — 사용자가 수락한 묶음만 들어간다. 기본은 전부 선택 상태다.
      setReview(built);
      setAccepted(allKeys);
      setNotices(composeNotices(built, null, truncatedRef.current, unfinished));
    },
    [applyLabels, frames],
  );

  const { run, isRunning, truncatedCountOf } = useAutoTrack(srcSn, {
    onResult: handleResult,
    // ★ 실패 안내는 **덮어쓰지 않고 덧붙인다** — 이어 보내다 실패하면 앞 조각 결과가 먼저
    //   도착해 안내(반영 건수·검토 목록)를 세워 둔다. 덮으면 살려 둔 결과가 화면에서 사라져
    //   버리는 것과 같아진다.
    onError: () =>
      setNotices((prev) => [
        ...prev,
        gotPartialRef.current
          ? '이후 구간에서 오류가 나 중단했습니다. 남은 프레임은 다시 실행해 주세요.'
          : 'AI 자동 추적에 실패했습니다. 잠시 후 다시 시도하세요.',
      ]),
    // 이어 보내는 동안에도 갱신된다 — 멈춘 것처럼 보이지 않게.
    onProgress: (done, total) => setProgress({ done, total }),
  });

  const noNextFrames = nextSrcSns.length === 0;
  const canRun = srcSn !== undefined && !noNextFrames && !disabled && !isRunning;

  const handleRun = useCallback(() => {
    if (!canRun) return;
    // 이 실행의 방식을 붙잡고 **즉시** 기본값으로 되돌린다 — 다음 실행이 이전 선택을 물려받지 않게.
    runModeRef.current = mode;
    setMode(DEFAULT_APPLY_MODE);
    setReview(null);
    setAccepted(new Set());
    truncatedRef.current = truncatedCountOf(nextSrcSns);
    unfinishedRef.current = 0;
    gotPartialRef.current = false;
    // 시퀀스 = [시작 프레임] + 상한 안의 후속. 서버도 같은 시퀀스를 훑는다.
    setProgress({ done: 0, total: 1 + nextSrcSns.length - truncatedRef.current });
    setNotices([]);
    void run(nextSrcSns);
  }, [canRun, mode, nextSrcSns, run, truncatedCountOf]);

  const toggleAccept = useCallback((key: string, next: boolean) => {
    setAccepted((prev) => {
      const copy = new Set(prev);
      if (next) copy.add(key);
      else copy.delete(key);
      return copy;
    });
  }, []);

  const handleAccept = useCallback(() => {
    if (review === null) return;
    const outcome = applyLabels(review, accepted);
    setNotices(composeNotices(review, outcome, truncatedRef.current, unfinishedRef.current));
    setReview(null);
    setAccepted(new Set());
  }, [accepted, applyLabels, review]);

  const handleDismiss = useCallback(() => {
    setReview(null);
    setAccepted(new Set());
    setNotices(['검출 결과를 모두 제외했습니다. 작업본에 반영된 것은 없습니다.']);
  }, []);

  return (
    <div
      data-testid="auto-track-panel"
      className="shrink-0 border-t border-gray-200 p-2 text-caption text-gray-700"
    >
      <div className="mb-1 font-semibold text-gray-900">AI 자동 추적</div>
      <p className="mb-2 text-[11px] text-gray-600">
        시작 객체를 고르지 않아도 현재 프레임부터 뒤따르는 프레임까지 한 번에 찾습니다.
      </p>

      {/* 적용 방식 — 기본값은 검토 후 수락이며 실행 후 다시 기본값으로 돌아간다. */}
      <div className="mb-2">
        <RadioGroup
          name="auto-track-apply-mode"
          aria-label="트랙 결과 적용 방식"
          value={mode}
          options={APPLY_MODE_OPTIONS}
          onChange={(value) => setMode(value === 'auto' ? 'auto' : 'review')}
          disabled={disabled || isRunning}
        />
      </div>

      <Button
        variant="outline"
        size="sm"
        fullWidth
        loading={isRunning}
        disabled={!canRun}
        onClick={handleRun}
      >
        AI 자동 추적
      </Button>

      {/* 진행 표시 — 서버가 잘라 보내 이어 보내는 동안에도 올라간다(멈춘 것처럼 보이지 않게). */}
      {isRunning && progress !== null && progress.total > 0 && (
        <p
          className="mt-1 text-[11px] text-gray-600"
          data-testid="auto-track-progress"
          role="status"
          aria-live="polite"
        >
          프레임 {progress.done}/{progress.total} 처리
        </p>
      )}

      {noNextFrames && (
        <p className="mt-1 text-[11px] text-gray-600">
          뒤따르는 프레임이 없어 실행할 수 없습니다.
        </p>
      )}

      {notices.length > 0 && (
        <Alert
          variant="info"
          role="status"
          title="AI 자동 추적 결과"
          className="mt-2"
          data-testid="auto-track-notice"
        >
          {notices.map((line) => (
            <span key={line} className="block">
              {line}
            </span>
          ))}
        </Alert>
      )}

      {review !== null && (
        <div className="mt-2" data-testid="auto-track-review">
          <p className="mb-1 text-[11px] text-gray-600">
            수락할 트랙을 고르세요. 체크를 풀면 그 트랙은 반영하지 않습니다.
          </p>
          <ul className="flex flex-col gap-1">
            {review.groups.map((group) => {
              const name = groupName(group.className, group.trackId);
              return (
                <li key={group.key} className="flex items-center gap-2">
                  <Checkbox
                    aria-label={`${name} 수락`}
                    checked={accepted.has(group.key)}
                    onCheckedChange={(next) => toggleAccept(group.key, next === true)}
                  />
                  <span className="min-w-0 flex-1 truncate">{name}</span>
                  <span className="shrink-0 text-[11px] text-gray-600">
                    {group.frameCount}프레임 {group.labelCount}건
                  </span>
                </li>
              );
            })}
          </ul>
          <div className="mt-2 flex gap-2">
            <Button size="sm" onClick={handleAccept} disabled={accepted.size === 0}>
              수락한 트랙 반영
            </Button>
            <Button size="sm" variant="secondary" onClick={handleDismiss}>
              모두 제외
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

/** 묶음 표기 — 트랙이 없으면 단발임을 명시한다(없는 트랙 번호를 지어내지 않는다). */
function groupName(className: string, trackId: string | null): string {
  return trackId === null ? `${className} (트랙 미부여)` : `${className} T:${trackId}`;
}

/**
 * 안내 문구 조립.
 *
 * @param applied   실제 반영 결과. null 이면 아직 반영하지 않은 단계(검토 대기)다.
 *                  ★ **요청 건수가 아니라 반영 건수**로 적는다 — 중복 제거로 전부 걸러졌는데
 *                  "올렸습니다" 라고 말하면 사용자는 작업본에 없는 것을 있다고 믿는다.
 * @param truncated 상한 초과로 잘라 보낸 프레임 수(0 이면 절단 없음).
 */
function composeNotices(
  review: AutoTrackReview,
  applied: AutoTrackApplyOutcome | null,
  truncated: number,
  unfinished = 0,
): string[] {
  const lines: string[] = [];
  if (applied !== null) {
    lines.push(
      applied.appliedLabels > 0
        ? `검출 ${applied.appliedLabels}건을 작업본에 올렸습니다. 저장해야 확정됩니다.`
        : '작업본에 올라간 검출이 없습니다.',
    );
    // 겹쳐서 빠진 건수도 알린다 — 조용히 버리면 "왜 개수가 다르지" 로 남는다.
    if (applied.skippedDuplicates > 0) {
      lines.push(
        `검출 ${applied.skippedDuplicates}건은 이미 작업본에 있는 라벨과 겹쳐 반영하지 않았습니다.`,
      );
    }
  }
  // 마스터 미연결 제외 — 제외했다는 사실과 사유를 함께 알린다(조용히 버리지 않는다).
  if (review.unlinkedCount > 0) {
    lines.push(
      `검출 ${review.unlinkedCount}건은 라벨 마스터에 연결되지 않아 반영 대상에서 빠졌습니다. ` +
        '(대응 마스터 없음 · 검출 클래스 매핑 미지정)',
    );
  }
  if (truncated > 0) {
    lines.push(`뒤쪽 ${truncated}개 프레임은 한 번에 처리할 수 있는 구간을 넘어 제외했습니다.`);
  }
  // ★ 절단(구간 상한)과 다른 사유다 — 이쪽은 **손대지 못하고 남은 구간**이다(서버가 시간 예산
  //   안에 못 끝냈거나, 이어 보내다 실패했거나). 원인을 단정하지 않는다 — 실패 사유는 별도
  //   안내가 말하고, 여기서 «시간 안에» 라고 적으면 실패한 경우에 거짓말이 된다.
  //   조용히 끝내면 사용자는 그 프레임에 왜 검출이 없는지 알 방법이 없다.
  if (unfinished > 0) {
    lines.push(`남은 프레임 ${unfinished}개는 처리하지 못했습니다. 다시 실행해 주세요.`);
  }
  return lines;
}
