/**
 * 마킹 완료 확인 — 저장을 보내기 전에 한 번 확인받는 단계. [@design SCREEN-045] [@design API-240]
 *
 * <h3>왜 확인 단계가 선택이 아닌가</h3>
 * 이 화면에서 유일하게 <b>되돌릴 수 없는</b> 조작이다. 저장하는 순간 그 지점으로 프레임 추출이
 * 시작되고 재마킹은 제공하지 않으므로, 잘못 저장하면 자산을 지우고 다시 올리는 것 말고는 되돌릴
 * 방법이 없다. 그래서 회복 경로가 확정되는 순간 이 단계가 설계의 필수 조건이 됐다.
 *
 * <h3>세 가지를 알린다</h3>
 * ① 무엇이 확정되는가 — 방식과 간격(자동이면 프레임 수와 그 환산 시간), 그리고 뽑히는 장수.
 * ② 되돌릴 수 없다는 사실.
 * ③ 잘못했을 때의 회복 경로 — 지우고 다시 올려야 하며, 삭제는 추출이 진행 중인 동안 거절되므로
 *    추출이 끝나기를 기다려야 한다. 이 시점이 사용자가 아직 취소할 수 있는 마지막 자리다.
 *
 * <h3>기본 초점은 취소에 둔다</h3>
 * 되돌릴 수 없는 쪽이 기본 선택이면 무심코 누른 한 번이 그대로 확정된다.
 * ★ 초점은 <b>DOM 순서</b>가 정한다 — 공용 모달이 열릴 때 첫 초점 대상을 잡으므로, 닫기(X)를 두지
 *   않고 본문에 초점 잡히는 요소를 두지 않아야 취소가 첫 대상이 된다. 본문에 버튼·입력을 넣으면
 *   그 자리로 초점이 옮겨 가 이 규약이 조용히 깨진다(회귀 가드가 이 지점을 고정한다).
 *
 * <h3>브라우저 기본 확인창을 쓰지 않는다</h3>
 * 이 저장소의 관례가 화면 안 창이고, 알려야 할 것이 여러 항목이라 평문 한 덩어리로는 위계를 줄 수
 * 없으며, 기본 초점을 취소에 두는 것도 화면 안 창에서만 정할 수 있다.
 */
import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

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

/** 소수 한 자리까지 — 초당 프레임 수가 분수면 정수로 떨어지지 않는다. */
function formatSeconds(sec: number): string {
  return `${sec.toFixed(1)}초`;
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

  return (
    <Modal
      open={open}
      onClose={onCancel}
      title="마킹을 완료하고 프레임을 추출할까요?"
      size="md"
      /* 닫기(X)를 두지 않는다 — ① 초점이 취소에 먼저 가야 하고 ② 물러나는 길이 취소 하나로
         모여야 한다. ESC·배경 누르기는 그대로 살아 있어(둘 다 취소와 같게 다룬다) 키보드로
         빠져나올 수 없게 되지 않는다. */
      showCloseButton={false}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={saving}>
            취소
          </Button>
          <Button variant="danger" onClick={onConfirm} loading={saving}>
            완료하고 추출 시작
          </Button>
        </>
      }
    >
      {/* 확정되는 내용 — 값은 창을 열 때의 설정에서 그때그때 계산해 넘어온다. */}
      {/* ⚠ 새 유틸리티 클래스를 만들지 않는다 — 스타일시트는 두 채널이 한 벌을 나눠 쓰므로,
          여기서만 쓰는 임의 값(예: 열 너비를 직접 적은 격자)이 관제 채널 산출물에도 실린다.
          이미 쓰이는 클래스 조합으로 같은 모양을 만든다. */}
      <dl className="mb-4 space-y-2 text-body-md text-gray-800">
        <div className="flex gap-3">
          <dt className="w-24 shrink-0 text-gray-500">방식</dt>
          <dd data-testid="marking-confirm-mode">{isAuto ? '자동' : '수동'}</dd>
        </div>
        {isAuto && (
          <div className="flex gap-3">
            <dt className="w-24 shrink-0 text-gray-500">간격</dt>
            <dd data-testid="marking-confirm-interval">
              {intervalFrames} 프레임
              {intervalSec !== null && ` (약 ${formatSeconds(intervalSec)})`}
            </dd>
          </div>
        )}
        {!isAuto && (
          <div className="flex gap-3">
            <dt className="w-24 shrink-0 text-gray-500">찍은 지점</dt>
            <dd data-testid="marking-confirm-picked">{cap.requestedCount}건</dd>
          </div>
        )}
        <div className="flex gap-3">
          <dt className="w-24 shrink-0 text-gray-500">뽑힐 프레임</dt>
          <dd data-testid="marking-confirm-frame-count">
            {cap.effectiveCount}장
            {/* 잘렸으면 요청한 수를 나란히 적는다 — 실제 장수만 보여 주면 고른 것이 다 들어간 줄 안다. */}
            {cap.truncated && ` (요청 ${cap.requestedCount}장 중)`}
          </dd>
        </div>
      </dl>

      {/* 절단 안내 — 상한에 걸릴 때만 나타난다. 상한을 모르면 아무 주장도 하지 않는다. */}
      {cap.truncated && (
        <Alert
          variant="error"
          title="고른 지점이 추출 장수 상한을 넘어 일부만 뽑힙니다."
          data-testid="marking-confirm-truncated"
        >
          요청한 {cap.requestedCount}장 가운데 {cap.effectiveCount}장만 뽑힙니다.{' '}
          {isAuto
            ? '자동은 전 구간을 고르게 다시 뽑아 덮는 범위가 유지됩니다.'
            : '수동은 앞에서부터 상한까지 남고 뒤가 빠집니다.'}{' '}
          지금 취소하고 {isAuto ? '간격을 넓히면' : '지점을 줄이면'} 고른 지점을 모두 살릴 수 있습니다.
        </Alert>
      )}

      {/* 되돌릴 수 없다는 사실과 회복 경로, 그 회복에 따르는 기다림을 한자리에서 알린다.
          누르기 직전에 마지막으로 읽히도록 조작 버튼 바로 위에 둔다. */}
      <Alert
        variant="error"
        title="완료하면 되돌릴 수 없습니다."
        data-testid="marking-confirm-irreversible"
      >
        다시 마킹하려면 이 자산을 지우고 다시 올려야 하며, 추출이 진행 중인 동안에는 지울 수 없습니다.
      </Alert>
    </Modal>
  );
}
