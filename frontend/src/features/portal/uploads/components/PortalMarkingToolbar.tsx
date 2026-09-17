/**
 * 포털 업로드 영상 마킹 설정 — 방식 · 간격 · 예상 결과 · 완료. [@design SCREEN-045] [@design DS-002]
 *
 * <h3>겉모습을 부모 포털 부품으로 갈아입혔다 (2026-09-16)</h3>
 * 부모 포털이 넘긴 기준(`pages/workspace/authoring/AuthoringMarkingView`)의 <b>설정 기둥</b>을
 * 그대로 옮겼다 — 판은 `klid-section-card klid-marking-card`, 부품은 포털 킷이다.
 *   · 방식 고르기 = `RadioRow` — ⚠ 종전의 «알약 트랙 전환 버튼» 은 **포털이 쓰지 않는 모양**이라
 *     폐기했다(부모 포털이 그 자리를 라디오 줄로 못박았다). 고르는 행위·값은 그대로다.
 *   · 간격 칸 = 킷 `TextInput` (환산 시간은 그 칸의 힌트 줄로 내려갔다)
 *   · 단축키 안내 = `ShortcutTable`
 *   · 예상 결과 = `KeyValueList`(옅은 면) — ⚠ 종전의 한 문장 요약은 폐기다. 이름·값이 갈려
 *     「무엇이 몇인가」가 훑어읽기로 잡힌다.
 *   · 걸음 줄 = `klid-marking-actions`
 * ⚠ **잠긴 사유 안내 띠는 이 부품이 그리지 않는다** — 콘텐츠 맨 위(제목 바로 아래)로 올라가
 *   재생기·설정 기둥보다 먼저 읽힌다. 화면이 그 자리를 갖는다.
 *
 * <h3>자동이 기본이다</h3>
 * 화면에 들어오면 자동이 골라져 있고, 간격을 그대로 두고 완료하면 고정 간격으로 프레임을 뽑던
 * 것과 같은 결과가 된다. 관제 채널 마킹이 수동을 기본으로 두는 것과 다르다 — 이 경로에서 마킹은
 * 이벤트 구간을 사람이 짚는 일이 아니라 <b>프레임을 어디서 뽑을지 정하는 일</b>이기 때문이다.
 *
 * <h3>간격의 단위는 프레임 수다</h3>
 * 수동으로 찍는 지점이 이미 프레임 단위라, 자동만 시간 단위로 두면 한 화면 안에서 단위가 갈린다.
 * 다만 같은 프레임 수라도 초당 프레임 수가 다른 영상에서는 다른 시간 간격이 되므로, 지금 영상
 * 기준 환산 시간을 <b>칸 바로 아래 힌트</b>와 <b>예상 결과</b> 두 자리에서 되보여 준다.
 *
 * <h3>완료 버튼은 곧바로 저장하지 않는다</h3>
 * 누르면 확인 단계를 먼저 연다 — 되돌릴 수 없는 조작이기 때문이다. 수동인데 지점이 하나도 없으면
 * 확인 단계로 넘어가지 않고 안내만 띄운다(자동은 간격만 있으면 되므로 이 검사 대상이 아니다).
 * 그 안내 발화는 <b>호출부</b>가 한다 — 버튼을 죽여서 막지 않는 것은 「왜 눌리지 않는가」를 화면이
 * 말해 주게 하기 위해서다(관제 채널 마킹과 같은 관례).
 */
import { Button, TextInput } from 'krds-react';
import { RotateCcw } from 'lucide-react';

import { ShortcutTable } from '@/components/portal/authoring';
import { KeyValueList, RadioRow } from '@/components/portal/kit';

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

/** 수동 방식의 단축키 — 지점은 Space 로만 찍는다. */
const MANUAL_SHORTCUTS = [
  { keys: ['Space'], label: '지점 추가' },
  { keys: ['Del'], label: '선택 지점 삭제' },
  { keys: ['Enter'], label: '마킹 완료' },
] as const;

/** 뽑힐 장수 한 줄. 셀 수 없으면 값을 지어내지 않고 자리만 남긴다(사유는 바로 아래 힌트가 말한다). */
function frameCountText(cap: CapOutcome | null): string {
  if (cap === null) return '-';
  const suffix = cap.truncated ? ` (요청 ${cap.requestedCount}장 중 · 상한을 넘어 잘립니다)` : '';
  return `${cap.effectiveCount}장${suffix}`;
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
  const activeHint = MODES.find((m) => m.value === mode)?.hint ?? '';
  const intervalText =
    intervalSec !== null
      ? `${intervalFrames} 프레임 (약 ${intervalSec.toFixed(1)}초)`
      : `${intervalFrames} 프레임`;

  const summary = isAuto
    ? [
        { label: '간격', value: intervalText },
        { label: '뽑힐 프레임', value: frameCountText(cap) },
      ]
    : [
        { label: '찍은 지점', value: `${markCount}건` },
        { label: '뽑힐 프레임', value: frameCountText(cap) },
      ];

  return (
    <section className="klid-section-card klid-marking-card" aria-label="마킹 설정">
      {/* ① 방식 — 둘 중 하나 고르기는 라디오 줄이다. 방식이 하는 일을 힌트 한 줄로 묶어
             「무엇을 고르는가」와 「고르면 무슨 일이 되는가」가 한자리에서 읽힌다. */}
      <div className="form-group">
        <RadioRow
          label="마킹 방식"
          name="marking-mode"
          value={mode}
          onChange={onModeChange}
          options={MODES.map((m) => ({ value: m.value, label: m.label, disabled: locked }))}
        />
        <p className="form-hint">{activeHint}</p>
      </div>

      {/* ② 방식에 딸린 입력 — 자동은 간격, 수동은 단축키 안내. 자리를 같게 두어 방식을 바꿔도
             화면이 크게 흔들리지 않는다. */}
      {isAuto ? (
        /* ⚠ 라벨에서 단위를 빼지 말 것 — 이 입력의 접근 이름이 「간격」뿐이면 보조기술
           사용자에게 <b>프레임 수인지 초인지</b>가 전달되지 않는다. 그 구분이 이 화면의
           핵심이라(같은 프레임 수가 영상마다 다른 시간이 된다) 이름 안에 둔다. */
        <TextInput
          id="portal-marking-interval"
          label="간격(프레임)"
          size="small"
          /* 1 이상 정수. 상한은 두지 않는다 — 클라이언트 상한은 서버 설정과 어긋날 수 있는
             두 번째 진실원이 된다. 지점 수가 추출 장수 상한을 넘으면 거부가 아니라 절단이다. */
          type="number"
          min={1}
          step={1}
          placeholder="300"
          value={String(intervalFrames)}
          disabled={locked}
          onChange={(next) => onIntervalChange(parseInt(next, 10) || 1)}
          hint={intervalSec !== null ? `약 ${intervalSec.toFixed(1)}초마다` : undefined}
        />
      ) : (
        <ShortcutTable title="단축키" items={MANUAL_SHORTCUTS} keysAt="end" />
      )}

      {/* ③ 예상 결과 — 되돌릴 수 없는 조작이라 «무엇이 일어나는가» 를 걸음 바로 위에 둔다.
             ⚠ 싸개에 시험 후크를 단다 — 값 목록 부품은 그 속성을 받지 않는다(복사본이라 고치지
             않는다). 조작 요소가 아니라 읽는 자리라 싸개로 족하다. */}
      <div data-testid="marking-plan-summary">
        <KeyValueList surface="muted" ariaLabel="마킹 요약" items={summary} />
      </div>
      {/* 셀 수 없으면 왜 셀 수 없는지를 요약 바로 아래 한 줄로 — 값 칸에 문장을 넣으면 좁은
          기둥에서 이름표와 값이 함께 접힌다. */}
      {cap === null && (
        <p className="form-hint">영상 길이를 아직 확인하지 못해 뽑힐 프레임 수를 셀 수 없습니다.</p>
      )}

      {/* ④ 걸음 — 서브가 먼저 · 메인이 나중(포털 창의 걸음 자리와 같다). */}
      <div className="klid-marking-actions">
        <Button size="medium" variant="secondary" onClick={onClear} disabled={locked}>
          <RotateCcw aria-hidden />
          초기화
        </Button>
        <Button
          size="medium"
          onClick={onSubmit}
          disabled={locked || submitting}
          data-testid="marking-complete-button"
        >
          {locked ? '마킹 완료 (다시 저장할 수 없음)' : submitting ? '저장 중…' : '마킹 완료'}
        </Button>
      </div>
    </section>
  );
}
