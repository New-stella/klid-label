/**
 * 포털 업로드 영상 마킹 설정 — 방식 · 간격 · 예상 결과 · 완료. [@design SCREEN-045] [@design DS-002]
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
 * <h3>예상 결과를 문장이 아니라 «면» 으로 둔다</h3>
 * 저장이 곧 추출의 시작이라 되돌릴 수 없다. 그래서 <b>완료를 누르기 직전에 반드시 읽어야 하는
 * 값</b>(뽑힐 장수)을 다른 안내와 같은 글줄에 섞지 않고 옅은 강조 면 위에 큰 숫자로 올린다.
 * 예전에는 이 값이 회색 한 줄로 다른 문구와 같은 무게였고, 그래서 읽히지 않았다.
 *
 * <h3>완료 버튼은 곧바로 저장하지 않는다</h3>
 * 누르면 확인 단계를 먼저 연다 — 되돌릴 수 없는 조작이기 때문이다. 수동인데 지점이 하나도 없으면
 * 확인 단계로 넘어가지 않고 안내만 띄운다(자동은 간격만 있으면 되므로 이 검사 대상이 아니다).
 * 그 안내 발화는 <b>호출부</b>가 한다 — 버튼을 죽여서 막지 않는 것은 「왜 눌리지 않는가」를 화면이
 * 말해 주게 하기 위해서다(관제 채널 마킹과 같은 관례).
 */
import { Link } from 'react-router-dom';
import { RotateCcw } from 'lucide-react';

import { PortalAlert } from '@/components/portal/ui/PortalAlert';
import { PORTAL_SURFACE, portalButton, portalButtonSm } from '@/components/portal/ui/portalControl';
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

/** 방식 두 가지 — 라벨과 «그게 무슨 뜻인지» 한 줄을 함께 갖는다. */
const MODES = [
  {
    value: PortalMarkingMode.AUTO,
    label: '자동',
    hint: '정한 간격마다 지점을 고릅니다.',
  },
  {
    value: PortalMarkingMode.MANUAL,
    label: '수동',
    hint: '재생하며 원하는 순간을 직접 찍습니다.',
  },
] as const;

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
  const activeHint = MODES.find((m) => m.value === mode)?.hint ?? '';

  return (
    <div className={cn(PORTAL_SURFACE, 'flex flex-col gap-block p-in-component')}>
      {/* ① 방식 — 트랙 안에서 고른 쪽만 흰 알약으로 떠오른다(무대의 배속 조작과 같은 형태라
             «하나를 고르는 자리» 라는 것이 화면 전체에서 같은 뜻으로 읽힌다). */}
      <div className="flex flex-wrap items-center gap-in-component">
        {/* 트랙이 자리를 꽉 채우고 두 쪽이 반씩 나눠 갖는다 — 좁은 열에서 알약만 작게 남고 트랙만
            길어지면 «고르는 자리»가 아니라 «빈 띠»로 보인다. */}
        <div
          role="group"
          aria-label="마킹 방식"
          className="flex w-full items-center gap-0.5 rounded-pill bg-gray-100 p-1"
        >
          {MODES.map((m) => (
            <button
              key={m.value}
              type="button"
              onClick={() => onModeChange(m.value)}
              aria-pressed={mode === m.value}
              disabled={locked}
              className={cn(
                'inline-flex min-h-10 flex-1 items-center justify-center rounded-pill px-4',
                'text-btn-label transition-colors duration-fast disabled:cursor-not-allowed',
                KRDS_FOCUS,
                mode === m.value
                  ? 'bg-white text-primary-600 disabled:text-gray-500'
                  : 'text-gray-600 hover:text-gray-900 disabled:text-gray-400 disabled:hover:text-gray-400',
              )}
            >
              {m.label}
            </button>
          ))}
        </div>
        <p className="text-body-sm text-gray-600">{activeHint}</p>
      </div>

      {/* ② 방식에 딸린 입력 — 자동은 간격, 수동은 단축키 안내. 자리를 같게 두어 방식을 바꿔도
             화면이 크게 흔들리지 않는다. */}
      {isAuto ? (
        <div className="flex flex-wrap items-center gap-in-component">
          {/* ⚠ 라벨에서 단위를 빼지 말 것 — 이 입력의 접근 이름이 「간격」뿐이면 보조기술
              사용자에게 <b>프레임 수인지 초인지</b>가 전달되지 않는다. 그 구분이 이 화면의
              핵심이라(같은 프레임 수가 영상마다 다른 시간이 된다) 이름 안에 둔다. */}
          <label htmlFor="portal-marking-interval" className="text-label text-gray-700">
            간격(프레임)
          </label>
          <div className="flex items-center gap-inline">
            <input
              id="portal-marking-interval"
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
                'h-10 w-28 rounded-input border border-gray-500 bg-white px-in-component',
                'text-body-md tabular-nums text-gray-900',
                'disabled:border-gray-300 disabled:bg-gray-50 disabled:text-gray-500',
                KRDS_FOCUS,
              )}
            />
            {intervalSec !== null && (
              <span className="text-body-sm text-gray-500">약 {intervalSec.toFixed(1)}초마다</span>
            )}
          </div>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-inline">
          <span className="text-label text-gray-700">단축키</span>
          {[
            { key: 'Space', what: '지점 추가' },
            { key: 'Del', what: '선택 지점 삭제' },
            { key: 'Enter', what: '마킹 완료' },
          ].map((s) => (
            <span
              key={s.key}
              className="inline-flex items-center gap-tight text-body-sm text-gray-600"
            >
              <kbd className="rounded-tag border border-gray-300 bg-gray-50 px-tight py-0.5 text-caption text-gray-700">
                {s.key}
              </kbd>
              {s.what}
            </span>
          ))}
        </div>
      )}

      {/* ③ 예상 결과 + 명령 — 되돌릴 수 없는 조작이라 «무엇이 일어나는가» 를 버튼 바로 옆에 둔다. */}
      <div className="flex flex-wrap items-center justify-between gap-in-component rounded-tile bg-gray-50 p-in-component">
        <p className="text-body-sm text-gray-600" data-testid="marking-plan-summary">
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
              {/* 이 숫자가 이 자리의 존재 이유다 — 다른 글자보다 크고 진하게 둔다. */}
              뽑힐 프레임{' '}
              <strong className="text-title-sm tabular-nums text-primary-600">
                {cap.effectiveCount}
              </strong>
              장{cap.truncated && ` (요청 ${cap.requestedCount}장 중 · 상한을 넘어 잘립니다)`}
            </span>
          )}
        </p>

        <div className="flex shrink-0 items-center gap-inline">
          <button
            type="button"
            onClick={onClear}
            disabled={locked}
            className={portalButtonSm('ghost')}
          >
            <RotateCcw className="size-4" aria-hidden />
            초기화
          </button>
          <button
            type="button"
            onClick={onSubmit}
            disabled={locked || submitting}
            data-testid="marking-complete-button"
            className={portalButton('primary')}
          >
            {locked ? '마킹 완료 (다시 저장할 수 없음)' : submitting ? '저장 중…' : '마킹 완료'}
          </button>
        </div>
      </div>

      {/* 완료가 잠긴 자산 — 사유와 회복 경로를 함께 알린다. 기다린다고 풀리는 것이 아니다. */}
      {lock !== null && (
        <PortalAlert
          tone="info"
          title="다시 마킹하려면 이 자산을 지우고 다시 올려야 합니다."
          data-testid="marking-locked-notice"
          description={
            lock === 'EXTRACTING' ? (
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
            )
          }
        />
      )}
    </div>
  );
}
