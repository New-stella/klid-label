/**
 * 포털 업로드 영상 마킹 설정 — 방식 · 간격 · 예상 결과 · 완료. [@design SCREEN-045]
 *
 * <h3>모양 — 포털 저작도구 화면이 정본</h3>
 * 짜임·문구는 포털 화면(KLID_Portal `AuthoringMarkingView` 의 설정 기둥 첫 카드)과 같다 — 방식 라디오 줄
 * → 간격 칸(수동이면 단축키 표) → 요약 상자 → 걸음 줄. 부품은 포털 저장소 것을 가져다 쓴다.
 * 잠김 안내 띠는 이 카드가 아니라 **화면 맨 위(제목 바로 아래)** 에 선다 — 포털 화면의 자리다.
 *
 * <h3>자동이 기본이다</h3>
 * 화면에 들어오면 자동이 골라져 있고, 간격을 그대로 두고 완료하면 고정 간격으로 프레임을 뽑던
 * 것과 같은 결과가 된다. 관제 채널 마킹이 수동을 기본으로 두는 것과 다르다 — 이 경로에서 마킹은
 * 이벤트 구간을 사람이 짚는 일이 아니라 <b>프레임을 어디서 뽑을지 정하는 일</b>이기 때문이다.
 *
 * <h3>간격의 단위는 프레임 수다</h3>
 * 수동으로 찍는 지점이 이미 프레임 단위라, 자동만 시간 단위로 두면 한 화면 안에서 단위가 갈린다.
 * 다만 같은 프레임 수라도 초당 프레임 수가 다른 영상에서는 다른 시간 간격이 되므로, 지금 영상
 * 기준 환산 시간을 칸 아래와 요약 상자에 함께 보여 준다.
 *
 * <h3>완료 버튼은 곧바로 저장하지 않는다</h3>
 * 누르면 확인 단계를 먼저 연다 — 되돌릴 수 없는 조작이기 때문이다. 수동인데 지점이 하나도 없으면
 * 확인 단계로 넘어가지 않고 안내만 띄운다(자동은 간격만 있으면 되므로 이 검사 대상이 아니다).
 * 그 안내 발화는 <b>호출부</b>가 한다 — 버튼을 죽여서 막지 않는 것은 「왜 눌리지 않는가」를 화면이
 * 말해 주게 하기 위해서다(관제 채널 마킹과 같은 관례).
 */
import { useEffect, useState } from 'react';
import { Button, TextInput } from 'krds-react';
import { RotateCcw } from 'lucide-react';

import { KeyValueList, RadioRow } from '@portal/components/custom';
import { ShortcutTable } from '@portal/pages/workspace/authoring/ShortcutTable';
import '@portal/pages/workspace/authoring/AuthoringMarkingView.css';

import { PORTAL_AUTO_INTERVAL_DEFAULT_FRAMES, type CapOutcome } from '../markingPlan';
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

/** 방식마다 «그게 무슨 뜻인지» 한 줄. */
const MODE_HINT: Record<PortalMarkingMode, string> = {
  [PortalMarkingMode.AUTO]: '정한 간격마다 지점을 고릅니다.',
  [PortalMarkingMode.MANUAL]: '재생하며 원하는 순간을 직접 찍습니다.',
};

const MANUAL_SHORTCUTS = [
  { keys: ['Space'], label: '지점 추가' },
  { keys: ['Del'], label: '선택 지점 삭제' },
  { keys: ['Enter'], label: '마킹 완료' },
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

  /*
   * 간격 칸의 글 — 칸은 비워 둘 수 있어야 한다(지우고 새로 적는 중). 값은 언제나 「정수로 읽고, 못 읽으면
   * 1」 규칙으로 올린다(종전 규칙 그대로). 칸 밖에서 값이 바뀌면(초기화 등) 글도 따라간다.
   */
  const [intervalText, setIntervalText] = useState(String(intervalFrames));
  useEffect(() => {
    setIntervalText((prev) => ((parseInt(prev, 10) || 1) === intervalFrames ? prev : String(intervalFrames)));
  }, [intervalFrames]);

  const intervalValue = `${intervalFrames} 프레임${intervalSec !== null ? ` (약 ${intervalSec.toFixed(1)}초)` : ''}`;
  const frameValue =
    cap === null
      ? '-'
      : `${cap.effectiveCount}장${cap.truncated ? ` (요청 ${cap.requestedCount}장 중 · 상한을 넘어 잘립니다)` : ''}`;
  const summary = isAuto
    ? [
        { label: '간격', value: intervalValue },
        { label: '뽑힐 프레임', value: frameValue },
      ]
    : [
        { label: '찍은 지점', value: `${markCount}건` },
        { label: '뽑힐 프레임', value: frameValue },
      ];

  return (
    <section className="klid-section-card klid-marking-card" aria-label="마킹 설정">
      {/* 방식과 그 방식이 하는 일을 한 묶음으로 — 킷 폼 그룹의 안내 줄. 잠기면 방식을 바꿀 수 없다 */}
      <div className="form-group">
        <RadioRow
          label="마킹 방식"
          name="portal-marking-mode"
          options={[
            { value: PortalMarkingMode.AUTO, label: '자동', disabled: locked },
            { value: PortalMarkingMode.MANUAL, label: '수동', disabled: locked },
          ]}
          value={mode}
          onChange={onModeChange}
        />
        <p className="form-hint">{MODE_HINT[mode]}</p>
      </div>

      {/* 방식에 딸린 줄 — 자동은 간격 칸, 수동은 단축키 표 */}
      {isAuto ? (
        /* ⚠ 이름에서 단위를 빼지 말 것 — 프레임 수인지 초인지가 이 화면의 핵심이다.
           상한은 두지 않는다 — 클라이언트 상한은 서버 설정과 어긋날 수 있는 두 번째 진실원이 된다 */
        <TextInput
          id="portal-marking-interval"
          label="간격(프레임)"
          size="small"
          inputMode="numeric"
          placeholder={String(PORTAL_AUTO_INTERVAL_DEFAULT_FRAMES)}
          value={intervalText}
          onChange={(text) => {
            setIntervalText(text);
            onIntervalChange(parseInt(text, 10) || 1);
          }}
          disabled={locked}
          hint={intervalSec !== null ? `약 ${intervalSec.toFixed(1)}초마다` : undefined}
        />
      ) : (
        <ShortcutTable title="단축키" items={MANUAL_SHORTCUTS} keysAt="end" />
      )}

      {/* 지금 설정이면 몇 장이 뽑히는지 — 적는 칸 바로 아래에서 되보여 준다 */}
      <KeyValueList surface="muted" ariaLabel="마킹 요약" items={summary} />
      {/* 길이를 모르면 왜 셀 수 없는지를 요약 바로 아래 한 줄로 — 지어내지 않는다 */}
      {cap === null && (
        <p className="form-hint">영상 길이를 아직 확인하지 못해 뽑힐 프레임 수를 셀 수 없습니다.</p>
      )}

      {/* 서브가 먼저 · 메인이 나중 */}
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
