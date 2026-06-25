import { type ReactNode } from 'react';

import { Button } from './Button';
import { Modal } from './Modal';

export interface ConfirmDialogProps {
  open: boolean;
  title: ReactNode;
  description?: ReactNode;
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
      size="sm"
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
    />
  );
}
