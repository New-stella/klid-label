/**
 * 포털 업로드 영상 마킹 툴바 — 방식·간격·초기화·완료. [@design SCREEN-045]
 *
 * <h3>자동이 기본이다</h3>
 * 화면에 들어오면 자동이 골라져 있고, 간격을 그대로 두고 완료하면 고정 간격으로 프레임을 뽑던
 * 것과 같은 결과가 된다. 관제 채널 마킹이 수동을 기본으로 두는 것과 다르다 — 이 경로에서 마킹은
 * 이벤트 구간을 사람이 짚는 일이 아니라 <b>프레임을 어디서 뽑을지 정하는 일</b>이기 때문이다.
 *
 * <h3>간격의 단위는 프레임 수다</h3>
 * 수동으로 찍는 지점이 이미 프레임 단위라, 자동만 시간 단위로 두면 한 화면 안에서 단위가 갈린다.
 * 다만 같은 프레임 수라도 초당 프레임 수가 다른 영상에서는 다른 시간 간격이 되므로, 지금 영상
 * 기준 환산 시간을 <b>뽑힐 장수 안내와 한자리에</b> 묶어 보여 준다 — 둘 다 「지금 설정이 이
 * 영상에서 실제로 무엇을 뜻하는가」라는 같은 물음에 답하고 같은 초당 프레임 수에서 함께 나오는
 * 값이라, 따로 두면 사용자가 두 자리를 오가며 맞춰 봐야 한다.
 *
 * <h3>완료 버튼은 곧바로 저장하지 않는다</h3>
 * 누르면 확인 단계를 먼저 연다 — 되돌릴 수 없는 조작이기 때문이다. 수동인데 지점이 하나도 없으면
 * 확인 단계로 넘어가지 않고 안내만 띄운다(자동은 간격만 있으면 되므로 이 검사 대상이 아니다).
 * 그 안내 발화는 <b>호출부</b>가 한다 — 버튼을 죽여서 막지 않는 것은 「왜 눌리지 않는가」를 화면이
 * 말해 주게 하기 위해서다(관제 채널 마킹과 같은 관례).
 */
import { Link } from 'react-router-dom';

import { Alert } from '@/components/common/Alert';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import type { CapOutcome } from '../markingPlan';
import { PortalMarkingMode } from '../markingTypes';

/** 완료가 잠긴 사유 — 문구가 갈리므로 하나로 합치지 않는다. */
export type MarkingLockReason = 'EXTRACTING' | 'SAVED';

export interface PortalMarkingToolbarProps {
  mode: PortalMarkingMode;
  onModeChange: (mode: PortalMarkingMode) => void;
  intervalFrames: number;
  onIntervalChange: (frames: number) => void;
  /** 간격의 환산 시간(초). 초당 프레임 수를 모르면 `null` — 그때는 프레임 수만 알린다. */
  intervalSec: number | null;
  /** 지금 설정으로 뽑히는 장수. 영상 길이를 몰라 셀 수 없으면 `null`. */
  cap: CapOutcome | null;
  /** 수동으로 찍은 지점 수. */
  markCount: number;
  /** 완료가 잠긴 사유. `null` 이면 저장할 수 있다. */
  lock: MarkingLockReason | null;
  submitting: boolean;
  onClear: () => void;
  onSubmit: () => void;
}

export function PortalMarkingToolbar({
  mode,
  onModeChange,
  intervalFrames,
  onIntervalChange,
  intervalSec,
  cap,
  markCount,
  lock,
  submitting,
  onClear,
  onSubmit,
}: PortalMarkingToolbarProps) {
  const isAuto = mode === PortalMarkingMode.AUTO;
  const locked = lock !== null;

  return (
    <div className="space-y-3 rounded-lg border border-gray-200 bg-white p-3">
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-1" role="group" aria-label="마킹 방식">
          {([PortalMarkingMode.AUTO, PortalMarkingMode.MANUAL] as const).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => onModeChange(m)}
              aria-pressed={mode === m}
              disabled={locked}
              className={cn(
                'min-h-11 rounded px-3 py-1.5 text-body-md font-medium transition-colors disabled:opacity-50',
                KRDS_FOCUS,
                mode === m
                  ? 'bg-primary-600 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200',
              )}
            >
              {m === PortalMarkingMode.AUTO ? '자동' : '수동'}
            </button>
          ))}
        </div>

        {isAuto && (
          <label className="flex items-center gap-1 text-label text-gray-600">
            간격(프레임)
            <input
              type="number"
              /* 1 이상 정수. 상한은 두지 않는다 — 클라이언트 상한은 서버 설정과 어긋날 수 있는
                 두 번째 진실원이 된다. 지점 수가 추출 장수 상한을 넘으면 거부가 아니라 절단이다. */
              min={1}
              step={1}
              value={intervalFrames}
              placeholder="300"
              disabled={locked}
              onChange={(e) => onIntervalChange(parseInt(e.target.value, 10) || 1)}
              className={cn(
                'w-24 rounded border border-gray-300 px-2 py-1.5 text-body-md disabled:opacity-50',
                KRDS_FOCUS,
              )}
            />
          </label>
        )}

        {!isAuto && (
          <span className="text-body-md text-gray-500">
            Space: 지점 추가 | Del: 선택 지점 삭제 | Enter: 마킹 완료
          </span>
        )}

        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            onClick={onClear}
            disabled={locked}
            className={cn(
              'min-h-11 rounded bg-gray-100 px-3 py-1.5 text-button text-gray-700 hover:bg-gray-200 disabled:opacity-50',
              KRDS_FOCUS,
            )}
          >
            초기화
          </button>
          <button
            type="button"
            onClick={onSubmit}
            disabled={locked || submitting}
            data-testid="marking-complete-button"
            className={cn(
              'min-h-11 rounded px-4 py-1.5 text-button font-medium text-white transition-colors',
              KRDS_FOCUS,
              locked || submitting
                ? 'cursor-not-allowed bg-gray-400'
                : 'bg-primary-600 hover:bg-primary-700',
            )}
          >
            {locked ? '마킹 완료 (다시 저장할 수 없음)' : submitting ? '저장 중…' : '마킹 완료'}
          </button>
        </div>
      </div>

      {/* 환산 시간 + 뽑힐 장수 — 한 줄에 함께 둔다. 저장이 곧 추출의 시작이라 되돌릴 수 없기
          때문이고, 같은 프레임 수라도 영상마다 다른 시간 간격이 되기 때문이다. */}
      <p className="text-body-md text-gray-600" data-testid="marking-plan-summary">
        {isAuto && (
          <>
            간격 {intervalFrames} 프레임
            {intervalSec !== null && ` (약 ${intervalSec.toFixed(1)}초)`} ·{' '}
          </>
        )}
        {!isAuto && <>찍은 지점 {markCount}건 · </>}
        {cap === null ? (
          <span>영상 길이를 아직 확인하지 못해 뽑힐 프레임 수를 셀 수 없습니다.</span>
        ) : (
          <span>
            뽑힐 프레임 {cap.effectiveCount}장
            {cap.truncated && ` (요청 ${cap.requestedCount}장 중 · 상한을 넘어 잘립니다)`}
          </span>
        )}
      </p>

      {/* 완료가 잠긴 자산 — 사유와 회복 경로를 함께 알린다. 기다린다고 풀리는 것이 아니다. */}
      {lock !== null && (
        <Alert
          variant="info"
          title="다시 마킹하려면 이 자산을 지우고 다시 올려야 합니다."
          data-testid="marking-locked-notice"
        >
          {lock === 'EXTRACTING' ? (
            <>
              이미 마킹을 저장해 지금 프레임을 뽑고 있습니다. 추출이 진행 중인 동안에는 이 자산을
              지울 수 없으니, 추출이 끝난 뒤에 지우고 다시 올려 주세요.
            </>
          ) : (
            <>
              이미 마킹을 저장한 영상입니다.{' '}
              <Link to="/portal/uploads" className={cn('underline', KRDS_FOCUS)}>
                내 업로드
              </Link>
              에서 이 자산을 지우고 다시 올리면 새로 마킹할 수 있습니다.
            </>
          )}
        </Alert>
      )}
    </div>
  );
}
