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
 * <h3>포털 창 한 벌을 쓴다 (2026-09-16)</h3>
 * 포털에 창은 `Dialog` 하나이고 화면은 거기에 글만 꽂는다 — 문구가 다르다고 창을 새로 만들지
 * 않는다는 것이 그 부품의 규약이다. 그래서 관제 공용 모달에서 이 창으로 갈아탔다.
 * ⚠ 그 결과 <b>머리 줄에 닫기(X)가 선다</b>. 구 동작(닫기 없음)은 폐기다 — 포털 창은 X 를 나가는
 *   길로 삼고 아랫동에는 고르는 걸음만 둔다.
 * ★ <b>기본 초점이 취소라는 규약은 그대로다</b> — 킷 창은 열릴 때 문서 순서상 첫 초점 대상을
 *   잡는데, X 는 내용보다 <b>뒤</b>에 그려지므로(krds-react 1.1.1 실측) 아랫동의 취소가 먼저다.
 *   그래서 본문에 초점 잡히는 요소를 두지 않는 규약도 그대로 지킨다(회귀 가드가 고정한다).
 * ★ 초점은 창이 열린 뒤 <b>한 박자 늦게</b> 잡힌다(킷이 타이머로 옮긴다) — 시험은 그 시점을 기다린다.
 *
 * <h3>닫혀 있으면 아무것도 그리지 않는다</h3>
 * ⚠ 킷 창은 `open` 이 거짓이어도 DOM 에 남고 CSS 로만 감춘다(krds-react 1.1.1 실측). 그대로 두면
 *   <b>화면에 없는 창의 버튼이 문서에 실재</b>해 보조기술과 시험 양쪽에서 읽힌다. 그래서 여기서
 *   걷어낸다 — 킷 파일은 고치지 않는다(복사본 규약).
 */
import { Alert, Dialog, KeyValueList } from '@/components/portal/kit';

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
  if (!open) return null;

  const isAuto = mode === PortalMarkingMode.AUTO;

  return (
    <Dialog
      open
      onOpenChange={(next) => {
        // 저장이 나가는 동안에는 X·ESC·배경 누르기로도 물러나지 않는다 — 두 버튼을 잠근 것과
        // 같은 이유다(응답을 기다리는 사이에 창이 사라지면 결과를 받을 자리가 없어진다).
        if (next || saving) return;
        onCancel();
      }}
      title="마킹을 완료하고 프레임을 추출할까요?"
      /* 되돌릴 수 없다는 사실과 회복 경로, 그 회복에 따르는 기다림을 한자리에서 알린다.
         절단은 그보다 앞선다 — 「무엇이 잘리는가」를 읽고 나서야 「되돌릴 수 없다」가 무게를 갖는다. */
      alert={
        <>
          {/* 절단 안내 — 상한에 걸릴 때만 나타난다. 상한을 모르면 아무 주장도 하지 않는다.
              ⚠ 시험 후크는 싸개에 단다(킷 안내 띠는 그 속성을 받지 않는다 — 복사본이라 고치지 않는다).
                 조작 요소가 아니라 읽는 자리라 싸개로 족하다. */}
          {cap.truncated && (
            <div data-testid="marking-confirm-truncated">
              <Alert tone="danger" title="고른 지점이 추출 장수 상한을 넘어 일부만 뽑힙니다.">
                요청한 {cap.requestedCount}장 가운데 {cap.effectiveCount}장만 뽑힙니다.{' '}
                {isAuto
                  ? '자동은 전 구간을 고르게 다시 뽑아 덮는 범위가 유지됩니다.'
                  : '수동은 앞에서부터 상한까지 남고 뒤가 빠집니다.'}{' '}
                지금 취소하고 {isAuto ? '간격을 넓히면' : '지점을 줄이면'} 고른 지점을 모두 살릴 수
                있습니다.
              </Alert>
            </div>
          )}
          <div data-testid="marking-confirm-irreversible">
            <Alert tone="danger" live="none" title="완료하면 되돌릴 수 없습니다.">
              다시 마킹하려면 이 자산을 지우고 다시 올려야 하며, 추출이 진행 중인 동안에는 지울 수
              없습니다.
            </Alert>
          </div>
        </>
      }
      sub={{ label: '취소', close: true, disabled: saving }}
      main={{
        label: '완료하고 추출 시작',
        tone: 'danger',
        busy: saving,
        onClick: onConfirm,
      }}
    >
      {/* 확정되는 내용 — 값은 창을 열 때의 설정에서 그때그때 계산해 넘어온다. */}
      <KeyValueList
        surface={false}
        divided={false}
        ariaLabel="마킹 완료 내용"
        items={[
          { label: '방식', value: <span data-testid="marking-confirm-mode">{isAuto ? '자동' : '수동'}</span> },
          isAuto
            ? {
                label: '간격',
                value: (
                  <span data-testid="marking-confirm-interval">
                    {intervalFrames} 프레임
                    {intervalSec !== null && ` (약 ${formatSeconds(intervalSec)})`}
                  </span>
                ),
              }
            : {
                label: '찍은 지점',
                value: <span data-testid="marking-confirm-picked">{cap.requestedCount}건</span>,
              },
          {
            label: '뽑힐 프레임',
            value: (
              <span data-testid="marking-confirm-frame-count">
                {cap.effectiveCount}장
                {/* 잘렸으면 요청한 수를 나란히 적는다 — 실제 장수만 보여 주면 고른 것이 다 들어간 줄 안다. */}
                {cap.truncated && ` (요청 ${cap.requestedCount}장 중)`}
              </span>
            ),
          },
        ]}
      />
    </Dialog>
  );
}
