// 일괄 재시작 결과 모달 — **부분 성공을 그대로 보여준다**. [@design API-199] [@design SCREEN-008]
//
// ★ 왜 요약 한 줄로 끝내지 않는가
//   서버는 한 건도 성공하지 못해도 200 을 주고 건별 성패·사유를 돌려준다. 화면이 "요청했습니다"
//   한 마디로 뭉개면 사용자는 무엇이 안 됐는지 영영 모르고, 목록에서 같은 영상을 반복해 고른다.
//   서버가 건별 결과를 주는 이유가 이것이므로 접수되지 못한 분은 영상별로 사유까지 보여준다.
//
// ★ 건별 결과는 "**접수**했는지"이지 파이프라인이 끝났다는 뜻이 아니다
//   서버는 실패 상태를 선점하는 것까지만 요청 안에서 처리하고 실제 실행은 비동기로 넘긴다.
//   그래서 문구를 "재시작했다/완료됐다"로 쓰지 않는다 — 그렇게 쓰면 사용자는 결과를 다 본 것으로
//   오해하고, 정작 진행 상황(영상별 처리 단계)을 확인하지 않는다.
//   ⚠ 응답 스키마는 그대로다(successCount/failureCount/results) — 바뀐 것은 그 값의 **의미**뿐이다.

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

import type { BatchBulkRetryResult } from '../types';

export interface BulkRetryResultModalProps {
  open: boolean;
  result: BatchBulkRetryResult | null;
  /** 목록 행에서 만든 rawSn → CCTV명. 없는 영상은 식별자로 대신 표기한다. */
  videoNameById: Record<number, string>;
  onClose(): void;
}

export function BulkRetryResultModal({
  open,
  result,
  videoNameById,
  onClose,
}: BulkRetryResultModalProps) {
  if (!result) return null;

  const failures = result.results.filter((r) => !r.success);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="일괄 재시작 접수 결과"
      size="md"
      footer={
        <Button variant="primary" onClick={onClose}>
          확인
        </Button>
      }
    >
      <div className="flex flex-col gap-3" data-testid="bulk-retry-result">
        <p className="text-body-md text-gray-800">
          접수 <strong className="tabular-nums">{result.successCount}</strong>건 · 접수하지 못함{' '}
          <strong className="tabular-nums">{result.failureCount}</strong>건
        </p>
        {/* 접수 = 재기동을 받아들였다는 뜻이지 처리가 끝났다는 뜻이 아니다. */}
        {result.successCount > 0 && (
          <p className="text-caption text-gray-600" data-testid="bulk-retry-accepted-note">
            접수한 영상은 순서대로 처리됩니다. 진행 상황은 각 영상 상세의 처리 단계에서 확인하세요.
          </p>
        )}

        {failures.length > 0 && (
          <div>
            <h4 className="text-title-sm font-semibold text-gray-700 mb-1">
              접수하지 못한 영상
            </h4>
            <ul className="flex flex-col gap-1.5">
              {failures.map((f) => (
                <li
                  key={f.rawSn}
                  className="rounded-md border border-gray-200 bg-gray-50 px-3 py-2"
                  data-testid={`bulk-retry-failure-${f.rawSn}`}
                >
                  <p className="text-body-md font-medium text-gray-800">
                    {videoNameById[f.rawSn] ?? `영상 ${f.rawSn}`}
                  </p>
                  {/* 서버가 만든 사용자 문구를 그대로 쓴다 — 화면이 상태코드로 재해석하지 않는다. */}
                  <p className="text-caption text-gray-600">
                    {f.reason ?? '사유가 전달되지 않았습니다.'}
                  </p>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </Modal>
  );
}
