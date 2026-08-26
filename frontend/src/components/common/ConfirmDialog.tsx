import { type ReactNode } from 'react';

import { Alert } from './Alert';
import { Button } from './Button';
import { Modal } from './Modal';

export interface ConfirmDialogProps {
  open: boolean;
  title: ReactNode;
  description?: ReactNode;
  /**
   * 되돌릴 수 없는 결과를 **평문이 아니라 경고 박스**로 알린다 — 시안 `.dlg-warn`
   * (배경 --e-0 · 테두리 --e-2 · 아이콘 --e-6). 공용 {@link Alert} 을 그대로 쓰므로 상자·아이콘의
   * 소유자가 한 곳이고 아이콘 종수도 늘지 않는다.
   *
   * ⚠ **opt-in 이다** — 이 값을 넘긴 호출부만 박스를 얻는다. `variant="danger"` 만으로 박스를
   *   그리면 이미 danger 를 쓰는 확인창 전부의 모양이 바뀐다(그건 이 변경의 의도가 아니다).
   * ⚠ `description` 을 대신하지 않는다 — 설명은 «무엇을 하는가», 이 값은 «무엇을 잃는가»다.
   *   한 문단에 뭉치면 되돌릴 수 없다는 사실이 설명 속에 묻힌다.
   */
  warning?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: 'primary' | 'danger';
  loading?: boolean;
  /**
   * ESC 키로 닫기 허용 여부 (Modal 패스스루). 미지정 시 Modal 기본값(true) 유지 —
   * 기존 호출부 동작 무변경. 처리 중 강제 닫힘을 막아야 하는 곳에서 false 전달.
   */
  closeOnEsc?: boolean;
  /** 백드롭 클릭으로 닫기 허용 여부 (Modal 패스스루). 미지정 시 Modal 기본값(true) 유지. */
  closeOnBackdrop?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({
  open,
  title,
  description,
  warning,
  confirmLabel = '확인',
  cancelLabel = '취소',
  variant = 'primary',
  loading = false,
  closeOnEsc,
  closeOnBackdrop,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <Modal
      open={open}
      onClose={onCancel}
      title={title}
      description={description}
      // 시안 `.lightbox-box.is-sm { width: min(520px, …) }`. 토큰은 sm=384 · md=512 · lg=768 뿐이라
      // 520 에 가장 가까우면서 그 상한을 넘지 않는 `md`(512, -8px)를 쓴다.
      // ⚠ `Modal` 의 `sm` 토큰 자체를 520 으로 바꾸지 말 것 — `size="sm"` 을 쓰는 **다른 모달까지**
      //   함께 넓어진다. 폭을 정하는 것은 이 확인창이지 size 토큰이 아니다.
      size="md"
      {...(closeOnEsc !== undefined ? { closeOnEsc } : {})}
      {...(closeOnBackdrop !== undefined ? { closeOnBackdrop } : {})}
      footer={
        <>
          <Button variant="outline" onClick={onCancel} disabled={loading}>
            {cancelLabel}
          </Button>
          <Button variant={variant} onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {/* 시안은 경고 박스를 설명 **아래·조작 버튼 위**에 두어 누르기 직전에 마지막으로 읽히게 한다.
          `Modal` 이 설명을 children 앞에 그리므로 여기 두면 그 순서가 그대로 나온다. */}
      {warning !== undefined && warning !== null && (
        <Alert variant="error" title={warning} data-testid="confirm-dialog-warning" />
      )}
    </Modal>
  );
}
