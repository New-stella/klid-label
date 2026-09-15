// 검수 목록 — 일괄 검수완료 확인 창(SCREEN-018 §일괄 검수완료 확인 창).
//
// ★되돌릴 수 없는 처리를 **한 번의 클릭으로 시작하지 않는다.** 실행 버튼은 곧바로 보내지 않고
// 이 창을 먼저 연다. 건수만 보이고 무엇인지 보이지 않으면 잘못 고른 것을 확인할 수 없으므로,
// 대상 영상을 이름과 영상 번호로 **모두** 보인다.
//
// [@design SCREEN-018] [@design API-250]

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

/** 확인 창이 보여줄 대상 한 건 — 목록 행에서 표시에 필요한 것만 추린 모양. */
export interface BulkApproveTarget {
  videoId: number;
  cctvName: string;
}

export interface BulkApproveConfirmModalProps {
  open: boolean;
  targets: readonly BulkApproveTarget[];
  onConfirm: () => void;
  onCancel: () => void;
  /** 전송 중 — 확인 버튼을 잠가 두 번 눌리지 않게 한다. */
  isPending?: boolean;
}

/** 영상 번호 표기 — 목록 표와 **같은 형식**이라야 같은 영상임을 알아본다. */
function videoCode(videoId: number): string {
  return `video-${String(videoId).padStart(4, '0')}`;
}

export function BulkApproveConfirmModal({
  open,
  targets,
  onConfirm,
  onCancel,
  isPending = false,
}: BulkApproveConfirmModalProps) {
  return (
    <Modal
      open={open}
      onClose={onCancel}
      title="일괄 검수완료"
      size="md"
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="secondary" size="sm" onClick={onCancel} disabled={isPending}>
            취소
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={onConfirm}
            disabled={isPending}
            loading={isPending}
            data-testid="bulk-approve-confirm"
          >
            검수완료
          </Button>
        </div>
      }
    >
      <div className="flex flex-col gap-3" data-testid="bulk-approve-confirm-body">
        <p className="text-body-md text-gray-800">
          선택한 {targets.length.toLocaleString('ko-KR')}건을 검수완료합니다.
        </p>

        {/* 목록이 길면 창 안에서 스크롤한다 — 건수만 보이면 잘못 고른 것을 확인할 수 없다. */}
        <ul
          className="max-h-60 overflow-y-auto rounded-md border border-gray-200 bg-gray-50 p-2"
          data-testid="bulk-approve-target-list"
        >
          {targets.map((t) => (
            <li
              key={t.videoId}
              className="flex items-baseline gap-2 px-2 py-1 text-body-md text-gray-700"
            >
              <span className="truncate">{t.cctvName}</span>
              {/* ★영상 번호는 장식이 아니라 **잘못 고른 것을 확인하는 식별 정보**다(SCREEN-018 이
                  이름과 번호를 모두 보이라고 한 대상). 목록 배경(`bg-gray-50`) 위에서
                  `text-gray-400` 은 2.82:1 로 크게 미달이라 `gray-600`(5.77:1)으로 잡았다 —
                  「다른 곳도 그렇게 쓴다」는 이 자리의 근거가 되지 못한다. */}
              <span className="shrink-0 text-caption text-gray-600">{videoCode(t.videoId)}</span>
            </li>
          ))}
        </ul>

        <p className="text-caption text-gray-500">
          검수완료는 작업 완료를 뜻합니다. 처리한 뒤에는 되돌릴 수 없습니다.
        </p>
      </div>
    </Modal>
  );
}
