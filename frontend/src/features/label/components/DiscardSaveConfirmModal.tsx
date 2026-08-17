// R4·R5 — 「폐기 프레임 저장 확인」 모달.
//
// 저장에 폐기 상태 변경이 들어 있으면 몇 개 프레임이 학습데이터에서 빠지고 몇 개가 되돌아오는지
// 알리고 확인을 받는다(SCREEN-005). 폐기는 되돌릴 수 있지만 <b>산출물에서 빠지는 결정</b>이라
// 저장 전에 한 번 드러낸다 — 한번이라도 검수가 완료된 영상에서는 서버가 새 폐기·복원을 막으므로
// 되돌릴 회차가 없으면 그 프레임은 산출물에서 영구 누락된다.
//
// 프레임 이동 가드 모달과 톤·컴포넌트를 정합해 공통 Modal(포커스트랩·ESC·백드롭 내장)을 재사용한다.
// ESC/백드롭/X = 취소(저장하지 않음, 편집 상태 유지).
//
// @design SCREEN-005

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

import type { DiscardSaveSummary } from '../discardSaveSummary';

import { DiscardSaveNotice } from './DiscardSaveNotice';

export interface DiscardSaveConfirmModalProps {
  open: boolean;
  /** 이번 저장이 바꾸는 폐기 상태(방향별 프레임 수). */
  summary: DiscardSaveSummary;
  onConfirm: () => void;
  onCancel: () => void;
}

// 확인·취소 어느 쪽을 눌러도 모달은 곧바로 닫히므로(저장은 그 뒤에 진행) 진행 중 표기를 두지 않는다.
export function DiscardSaveConfirmModal({
  open,
  summary,
  onConfirm,
  onCancel,
}: DiscardSaveConfirmModalProps) {
  return (
    <Modal
      open={open}
      onClose={onCancel}
      title="폐기 프레임 저장 확인"
      size="sm"
      footer={
        <>
          <Button variant="outline" onClick={onCancel} data-testid="discard-save-confirm-cancel">
            취소
          </Button>
          <Button variant="primary" onClick={onConfirm} data-testid="discard-save-confirm-submit">
            확인하고 저장
          </Button>
        </>
      }
    >
      {/* 문구·톤은 가드 다이얼로그의 인라인 안내와 한 곳에서 나온다(DiscardSaveNotice). */}
      <DiscardSaveNotice summary={summary} testId="discard-save-confirm" />
    </Modal>
  );
}
