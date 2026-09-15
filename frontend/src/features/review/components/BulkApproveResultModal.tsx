// 검수 목록 — 일괄 검수완료 결과 창(SCREEN-018 §일괄 검수완료 결과 창).
//
// ★**한 건도 처리되지 않았어도 오류 화면으로 바꾸지 않는다** — 요청 자체는 받아들여졌고 판정은
// 건별 결과로 한다(API-250: 부분 실패 허용, 전건 실패도 200).
// 되지 않은 건은 이름과 사유를 하나씩 보여 무엇을 더 해야 하는지 알린다.
//
// [@design SCREEN-018] [@design API-250] [@design AC-1113]

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

import { failedItems, failureReasonText } from '../reviewClaim';
import type { BatchApproveResponse } from '../types';

export interface BulkApproveResultModalProps {
  open: boolean;
  result: BatchApproveResponse | null;
  /** 영상 식별자 → 표시명. 결과 응답은 식별자만 주므로 이름은 목록에서 잇는다. */
  videoNameById: Record<number, string>;
  /** 실패한 건만 다시 고른 상태로 목록에 돌아간다. */
  onRetryFailed: (videoIds: number[]) => void;
  /** 닫으면 목록을 다시 조회한다(처리된 건의 상태·점유 표시를 새로 받는다). */
  onClose: () => void;
}

export function BulkApproveResultModal({
  open,
  result,
  videoNameById,
  onRetryFailed,
  onClose,
}: BulkApproveResultModalProps) {
  if (!result) return null;

  const failures = failedItems(result.results);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="일괄 검수완료 결과"
      size="md"
      footer={
        <div className="flex justify-end gap-2">
          {/* 실패가 없으면 이 버튼을 두지 않는다 — 다시 고를 대상이 없다. */}
          {failures.length > 0 && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => onRetryFailed(failures.map((f) => f.videoId))}
              data-testid="bulk-approve-retry-failed"
            >
              실패한 건만 다시 선택
            </Button>
          )}
          <Button variant="primary" size="sm" onClick={onClose} data-testid="bulk-approve-result-close">
            닫기
          </Button>
        </div>
      }
    >
      <div className="flex flex-col gap-3" data-testid="bulk-approve-result-body">
        <p className="text-body-md text-gray-800" data-testid="bulk-approve-result-counts">
          성공 {result.successCount.toLocaleString('ko-KR')}건 · 실패{' '}
          {result.failureCount.toLocaleString('ko-KR')}건
        </p>

        {failures.length > 0 && (
          <div className="overflow-hidden rounded-md border border-gray-200">
            <table className="w-full text-body-md">
              <caption className="sr-only">처리되지 않은 영상</caption>
              <thead>
                <tr className="border-b border-gray-200 bg-secondary-50">
                  <th
                    scope="col"
                    className="whitespace-nowrap px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600"
                  >
                    영상명
                  </th>
                  <th
                    scope="col"
                    className="whitespace-nowrap px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600"
                  >
                    사유
                  </th>
                </tr>
              </thead>
              <tbody>
                {failures.map((f) => (
                  <tr
                    key={f.videoId}
                    className="border-b border-gray-100 transition-colors last:border-b-0 hover:bg-rowHover"
                  >
                    <td className="px-3 py-2 text-gray-700">
                      {videoNameById[f.videoId] ?? `video-${String(f.videoId).padStart(4, '0')}`}
                    </td>
                    {/* ★서버가 보낸 사유 문장을 그대로 싣는다 — 사유 코드로 문장을 지어내지 않는다.
                        같은 코드(CONFLICT)에 서로 다른 사유가 여럿 들어온다(reviewClaim 주석 참조). */}
                    <td className="px-3 py-2 text-gray-700">{failureReasonText(f)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Modal>
  );
}
