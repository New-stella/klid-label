// [@design SHELL-001] [@design AC-1105] [@design ADR-012]
/**
 * 관제 채널 세션 만료 연장 팝업 — **관제지원 웹의 세션 팝업과 같은 모양**(사용자 확정).
 *
 * 모양·문구·버튼 구성은 `SHELL-001` 이 소유한다. 이 컴포넌트는 그리기만 하고 흐름(갱신·로그아웃·
 * 재표시)은 `ControlSessionMonitor` 가 소유한다.
 *
 * ★ 닫기 버튼·X 가 없고 ESC·배경 클릭으로 닫히지 않는다 — 버튼은 「로그아웃」·「로그인 연장」 둘뿐이다.
 * ★ 치수·색은 관제 모달 실측값이다. 이 저장소의 디자인 토큰 밖 값(모서리 12px · 테두리 1.25px ·
 *   전용 음영 · 배경막 75%)이 섞인 것은 **외부 시스템과 같은 모양을 재현한다는 확정** 때문이며,
 *   색은 전부 기존 토큰에 대응한다(gray-300 #B1B8BE · gray-900 #1E2124 · gray-100 #E6E8EA ·
 *   gray-600 #58616A · primary-500 #256EF4 · info-600 #096AB3). 다른 화면이 이 값을 따라 쓰지 말 것.
 * ⚠ 남은 시간은 매초 바뀐다 — 보조기술이 매초 읽지 않도록 **라이브 영역에 두지 않는다**. 대화상자의
 *   접근 이름은 고정 문구다.
 * ★ **열릴 때 초점은 「로그인 연장」에 둔다**(사용자 확정 · `SHELL-001`). 이 팝업은 사용자가 부르지
 *   않았는데 뜨므로, 초점이 앞선 「로그아웃」에 있으면 입력 중이던 사람의 Space·Enter 한 번이 곧바로
 *   로그아웃이 된다. **버튼 배치·순서·모양은 관제 팝업 그대로** 두고 초점만 옮긴 것이다.
 */
import { useRef } from 'react';

import { Modal } from '@/components/common/Modal';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export const SESSION_EXPIRY_DIALOG_LABEL = '자동 로그아웃 안내';
export const UNSAVED_WORK_WARNING = '저장하지 않은 작업이 있습니다.';

/** 「N분 N초」 — 남은 시간을 초 단위로 내림한다. */
export function formatRemaining(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(total / 60)}분 ${total % 60}초`;
}

interface SessionExpiryDialogProps {
  open: boolean;
  remainingMs: number;
  sessionTimeMinutes: number;
  hasUnsavedWork: boolean;
  extendError: string | null;
  busy: boolean;
  onExtend: () => void;
  onLogout: () => void;
}

const BUTTON_BASE =
  'inline-flex h-[38px] items-center justify-center rounded-md border-[1.25px] px-4 text-[17px] disabled:opacity-60';

function noop(): void {
  /* 닫기 수단이 없다 — 버튼 둘로만 닫힌다 */
}

export function SessionExpiryDialog({
  open,
  remainingMs,
  sessionTimeMinutes,
  hasUnsavedWork,
  extendError,
  busy,
  onExtend,
  onLogout,
}: SessionExpiryDialogProps) {
  const extendRef = useRef<HTMLButtonElement>(null);
  return (
    <Modal
      open={open}
      initialFocusRef={extendRef}
      onClose={noop}
      closeOnBackdrop={false}
      closeOnEsc={false}
      showCloseButton={false}
      ariaLabel={SESSION_EXPIRY_DIALOG_LABEL}
      className="max-w-[448px] rounded-[12px] border-[1.25px] border-gray-300 bg-white p-0 shadow-[0_0_2px_rgba(0,0,0,0.08),0_16px_24px_rgba(0,0,0,0.12)]"
      backdropClassName="bg-black/75"
    >
      <div className="px-10 pt-14">
        <h2 className="text-[24px] font-bold leading-[1.4] text-gray-900">
          <span className="text-info-600" data-testid="session-expiry-remaining">
            {formatRemaining(remainingMs)}
          </span>{' '}
          후 자동 로그아웃 됩니다.
        </h2>
      </div>
      <div className="pb-2 pl-10 pr-8 pt-4 text-[17px] font-normal leading-[1.6] text-gray-900">
        <p>
          로그인 후 {sessionTimeMinutes}분이 경과하면 자동으로 로그아웃됩니다.
          <br />
          로그인 시간을 연장하시겠습니까?
        </p>
        {hasUnsavedWork && (
          <p className="mt-2 font-bold" data-testid="session-expiry-unsaved">
            {UNSAVED_WORK_WARNING}
          </p>
        )}
        {extendError && (
          <p className="mt-2 text-danger-600" role="alert">
            {extendError}
          </p>
        )}
      </div>
      <div className="flex justify-end gap-2 px-10 pb-10 pt-4">
        <button
          type="button"
          onClick={onLogout}
          disabled={busy}
          className={cn(BUTTON_BASE, 'border-gray-600 bg-gray-100 text-gray-900', KRDS_FOCUS)}
        >
          로그아웃
        </button>
        <button
          ref={extendRef}
          type="button"
          onClick={onExtend}
          disabled={busy}
          aria-busy={busy || undefined}
          className={cn(BUTTON_BASE, 'border-primary-500 bg-primary-500 text-white', KRDS_FOCUS)}
        >
          로그인 연장
        </button>
      </div>
    </Modal>
  );
}
