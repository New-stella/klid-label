/**
 * 마킹 완료 확인 — 저장을 보내기 전에 한 번 확인받는 단계. [@design SCREEN-045] [@design API-240]
 *
 * <h3>모양 — 포털 저작도구 화면이 정본</h3>
 * 포털 작은 창(`Dialog`)에 제목 · 경고 상자 · 요약(`KeyValueList`) · 취소/완료 걸음을 꽂는다
 * (KLID_Portal `AuthoringMarkingView` 의 확인 창과 같은 짜임·문구). 완료 걸음은 위험 색이다.
 *
 * <h3>왜 확인 단계가 선택이 아닌가</h3>
 * 이 화면에서 유일하게 <b>되돌릴 수 없는</b> 조작이다. 저장하는 순간 그 지점으로 프레임 추출이
 * 시작되고 재마킹은 제공하지 않으므로, 잘못 저장하면 자산을 지우고 다시 올리는 것 말고는 되돌릴
 * 방법이 없다.
 *
 * <h3>세 가지를 알린다</h3>
 * ① 무엇이 확정되는가 — 방식과 간격(자동이면 프레임 수와 그 환산 시간), 그리고 뽑히는 장수.
 * ② 되돌릴 수 없다는 사실.
 * ③ 잘못했을 때의 회복 경로 — 지우고 다시 올려야 하며, 삭제는 추출이 진행 중인 동안 거절되므로
 *    추출이 끝나기를 기다려야 한다.
 *
 * 저장하는 동안에는 닫히지 않는다 — 두 걸음이 함께 잠기고, 바깥 누르기 · Esc 도 무시한다.
 */
import { Alert, Dialog, KeyValueList, type KeyValueItem } from '@portal/components/custom';

import type { CapOutcome } from '../markingPlan';
import { PortalMarkingMode } from '../markingTypes';

export interface MarkingCompleteConfirmDialogProps {
  open: boolean;
  mode: PortalMarkingMode;
  /** 자동 방식에서 쓴 간격(프레임 수). 수동이면 쓰지 않는다. */
  intervalFrames: number;
  /** 그 간격이 이 영상에서 몇 초인지. 초당 프레임 수를 모르면 `null` — 그때는 환산을 적지 않는다. */
  intervalSec: number | null;
  /** 상한 반영 결과. 상한을 모르면 `known:false` 이며 절단을 예고하지 않는다. */
  cap: CapOutcome;
  /** 저장 요청이 나가는 동안 참 — 두 버튼을 함께 잠가 두 번 눌리지 않게 한다. */
  saving: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function MarkingCompleteConfirmDialog({
  open,
  mode,
  intervalFrames,
  intervalSec,
  cap,
  saving,
  onConfirm,
  onCancel,
}: MarkingCompleteConfirmDialogProps) {
  const isAuto = mode === PortalMarkingMode.AUTO;

  const items: KeyValueItem[] = [
    { label: '방식', value: isAuto ? '자동' : '수동' },
    isAuto
      ? {
          label: '간격',
          value: `${intervalFrames} 프레임${intervalSec !== null ? ` (약 ${intervalSec.toFixed(1)}초)` : ''}`,
        }
      : { label: '찍은 지점', value: `${cap.requestedCount}건` },
    {
      label: '뽑힐 프레임',
      // 잘렸으면 요청한 수를 나란히 적는다 — 실제 장수만 보여 주면 고른 것이 다 들어간 줄 안다.
      value: `${cap.effectiveCount}장${cap.truncated ? ` (요청 ${cap.requestedCount}장 중)` : ''}`,
    },
  ];

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (next) return;
        if (saving) return;
        onCancel();
      }}
      title="마킹을 완료하고 프레임을 추출할까요?"
      alert={
        <>
          {/* 절단 안내 — 상한에 걸릴 때만 나타난다. 상한을 모르면 아무 주장도 하지 않는다. */}
          {cap.truncated && (
            <Alert tone="danger" live="none" title="고른 지점이 추출 장수 상한을 넘어 일부만 뽑힙니다.">
              요청한 {cap.requestedCount}장 가운데 {cap.effectiveCount}장만 뽑힙니다.{' '}
              {isAuto
                ? '자동은 전 구간을 고르게 다시 뽑아 덮는 범위가 유지됩니다.'
                : '수동은 앞에서부터 상한까지 남고 뒤가 빠집니다.'}{' '}
              지금 취소하고 {isAuto ? '간격을 넓히면' : '지점을 줄이면'} 고른 지점을 모두 살릴 수 있습니다.
            </Alert>
          )}
          <Alert tone="danger" live="none" title="완료하면 되돌릴 수 없습니다.">
            다시 마킹하려면 이 자산을 지우고 다시 올려야 하며, 추출이 진행 중인 동안에는 지울 수 없습니다.
          </Alert>
        </>
      }
      sub={{ label: '취소', close: true, disabled: saving }}
      main={{ label: '완료하고 추출 시작', tone: 'danger', busy: saving, onClick: onConfirm }}
    >
      <KeyValueList surface={false} divided={false} ariaLabel="마킹 완료 내용" items={items} />
    </Dialog>
  );
}
