import { type ReactNode } from 'react';

import { Alert } from './Alert';
import { Button } from './Button';
import { MODAL_DESCRIPTION_CLASS, Modal } from './Modal';

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
  /**
   * 설명 **위**에 놓이는 본문 조각 — 시안 `.dlg-target`(대상 칩 행)처럼 «무엇에 대한
   * 확인인가»를 먼저 보여야 하는 자리다.
   *
   * ★ 왜 `Modal` 의 `children` 을 그냥 쓰지 않는가: `Modal` 본문은 `description` → `children`
   *   순서가 고정이라 그대로 넘기면 칩 행이 **설명 아래**로 내려간다(시안은 위다). 그래서 이
   *   값이 있을 때만 설명을 `Modal` 에 넘기지 않고 **여기서 순서대로** 그린다.
   * ⚠ 이 값을 넘기지 않은 호출부의 마크업은 **한 글자도 바뀌지 않는다** — 설명은 종전대로
   *   `Modal` 이 그린다(회귀 가드가 이 분기를 고정한다).
   * ⚠ 설명을 대신하지 않는다 — 이 자리는 «대상», `description` 은 «무엇을 하는가»다.
   */
  children?: ReactNode;
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
  children,
  confirmLabel = '확인',
  cancelLabel = '취소',
  variant = 'primary',
  loading = false,
  closeOnEsc,
  closeOnBackdrop,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  /** 칩 행이 실제로 있는가 — 본문 순서 분기의 **단일 판정**(아래 두 자리가 같은 값을 본다). */
  const hasLead = children !== undefined && children !== null && children !== false;

  return (
    <Modal
      open={open}
      onClose={onCancel}
      title={title}
      // 칩 행이 있으면 설명을 아래 본문에서 **직접** 그린다(설명 위에 칩 행을 두기 위해).
      // 없으면 종전대로 `Modal` 이 그린다 — 기존 호출부 마크업 무변경.
      {...(hasLead ? {} : { description })}
      // 시안 `.lightbox-box.is-sm { width: min(520px, …) }`. 토큰은 sm=384 · md=512 · lg=768 뿐이라
      // 520 에 가장 가까우면서 그 상한을 넘지 않는 `md`(512, -8px)를 쓴다.
      // ⚠ `Modal` 의 `sm` 토큰 자체를 520 으로 바꾸지 말 것 — `size="sm"` 을 쓰는 **다른 모달까지**
      //   함께 넓어진다. 폭을 정하는 것은 이 확인창이지 size 토큰이 아니다.
      size="md"
      {...(closeOnEsc !== undefined ? { closeOnEsc } : {})}
      {...(closeOnBackdrop !== undefined ? { closeOnBackdrop } : {})}
      footer={
        <>
          {/* 취소는 시안 `.btn-secondary`(중립 테두리 + 검정 글자)다. 구 `outline` 은 글자·테두리가
              모두 파랑이라 확정 버튼과 나란히 놓이면 **두 번째 주 버튼**처럼 읽혔다 — 취소는
              물러나는 조작이므로 중립 톤이어야 위계가 선다(건너뛰기 모달이 같은 이유로 이미
              `secondary` 다. 두 확인 창의 취소가 서로 다른 톤이던 것을 맞춘 것이다). */}
          <Button variant="secondary" onClick={onCancel} disabled={loading}>
            {cancelLabel}
          </Button>
          <Button variant={variant} onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {/* 시안 순서: 대상 칩 행 → 설명 → 경고 박스. 경고를 설명 **아래·조작 버튼 위**에 두는 것은
          누르기 직전에 마지막으로 읽히게 하기 위해서다.
          칩 행이 없을 때는 설명을 `Modal` 이 children 앞에 그리므로 여기 경고만 두면 같은 순서가
          나온다(기존 동작). 칩 행이 있을 때만 설명이 이 블록으로 내려와 셋이 한 자리에서 순서를
          갖는다 — 그 경우에도 타이포는 `Modal` 이 소유한 클래스를 **참조**한다(복제 금지). */}
      {/* 슬롯과 다음 요소 사이 간격은 `Modal` 이 설명에 주는 `mb-4` 와 같은 리듬으로 둔다
          (슬롯 내용이 스스로 바깥 여백을 갖지 않아도 되게 이 자리가 소유한다). */}
      {hasLead && <div className="mb-4">{children}</div>}
      {hasLead && description !== undefined && description !== null && (
        <p className={MODAL_DESCRIPTION_CLASS}>{description}</p>
      )}
      {warning !== undefined && warning !== null && (
        <Alert variant="error" title={warning} data-testid="confirm-dialog-warning" />
      )}
    </Modal>
  );
}
