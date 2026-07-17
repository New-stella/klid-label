// R5 — 프레임 이동 미저장 가드 모달.
//
// 현재 프레임에 미저장 변경(dirtyLabels)이 있는 상태에서 다른 프레임으로 이동하려 할 때
// 3옵션(저장 후 이동 / 저장 안 함 / 취소)을 제공한다. 닫기(X) 가드 모달과 톤·컴포넌트를
// 정합해 공통 Modal(포커스트랩·ESC·백드롭 내장)을 재사용한다.
//
// ESC/백드롭/X = 취소(현재 프레임 유지).

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

export interface FrameNavGuardModalProps {
  open: boolean;
  /** 미저장 객체 수(안내 문구용) */
  dirtyCount: number;
  /** '저장 후 이동' 진행 중(저장 요청) — 버튼 로딩/비활성 표기 */
  saving: boolean;
  onSaveAndMove: () => void;
  onDiscardAndMove: () => void;
  onCancel: () => void;
}

/**
 * 프레임 이동 시 미저장 변경 확인 다이얼로그(3분기).
 */
export function FrameNavGuardModal({
  open,
  dirtyCount,
  saving,
  onSaveAndMove,
  onDiscardAndMove,
  onCancel,
}: FrameNavGuardModalProps) {
  return (
    <Modal
      open={open}
      onClose={onCancel}
      title="저장 안 한 변경사항이 있습니다"
      description={`${dirtyCount}개 객체에 미저장 변경이 있습니다. 프레임을 이동하기 전에 어떻게 하시겠습니까?`}
      size="sm"
      footer={
        <>
          <Button
            variant="outline"
            onClick={onCancel}
            disabled={saving}
            data-testid="frame-nav-guard-cancel"
          >
            취소
          </Button>
          <Button
            variant="outline"
            onClick={onDiscardAndMove}
            disabled={saving}
            data-testid="frame-nav-guard-discard"
          >
            저장 안 함
          </Button>
          <Button
            variant="primary"
            onClick={onSaveAndMove}
            loading={saving}
            data-testid="frame-nav-guard-save"
          >
            저장 후 이동
          </Button>
        </>
      }
    />
  );
}
