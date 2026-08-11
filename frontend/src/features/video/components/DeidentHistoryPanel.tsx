import type { DeidentHistoryItem } from '@/features/video/types';

/**
 * 비식별 이력 패널 — 영상 상세 「기본 정보」 탭. [req: R14]
 *
 * 한 항목 = 비식별 위탁 1회차(BE `LS_DEIDENT_PROC_LOG` 1행). 최초 배치 비식별과 검수완료 후
 * 재비식별이 각각 한 행을 남기므로, 이 목록이 "이 영상을 언제 몇 번 비식별했고 무엇을 얼마나
 * 가렸는가" 를 그대로 보여준다.
 *
 * 표(`<table>`)를 쓰지 않는 이유: 이 화면의 다른 메타 항목과 같은 카드 나열이 자연스럽고,
 * 열 정렬·헤더가 필요할 만큼 열이 많지 않다.
 *
 * 표시 정책
 *  - 검출 집계가 없는 회차(구 데이터/리포트 조회 실패)는 집계 줄을 아예 감춘다 —
 *    0 으로 채워 보여주면 "0건 검출" 과 구분되지 않는다.
 *  - 파일 경로는 BE 가 내려주지 않으며 화면도 요구하지 않는다(개인정보 위치 정보).
 */

/** 처리 상태 코드 → 사용자 문구. 미지의 코드는 코드 그대로 노출하지 않고 '진행 중' 으로 둔다. */
const STATUS_LABEL: Record<string, string> = {
  SUCCEEDED: '완료',
  FAILED: '실패',
  REQUESTED: '진행 중',
};

/** 상태별 배지 색 — KRDS 의미상태 토큰. */
const STATUS_CLASS: Record<string, string> = {
  SUCCEEDED: 'bg-success/10 text-success',
  FAILED: 'bg-danger/10 text-danger',
  REQUESTED: 'bg-warning/10 text-warning',
};

function statusLabel(code: string): string {
  return STATUS_LABEL[code] ?? '진행 중';
}

function statusClass(code: string): string {
  return STATUS_CLASS[code] ?? STATUS_CLASS.REQUESTED;
}

/** ISO 문자열 → 'YYYY-MM-DD HH:mm'. 값이 없으면 '-'. */
function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-';
  return value.slice(0, 16).replace('T', ' ');
}

function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? '-' : value.toLocaleString('ko-KR');
}

/** 집계가 하나라도 있으면 집계 줄을 그린다(전부 없으면 감춘다). */
function hasDetectionCounts(item: DeidentHistoryItem): boolean {
  return (
    item.faceDtctCnt !== null ||
    item.noPltDtctCnt !== null ||
    item.frmeCnt !== null
  );
}

export function DeidentHistoryPanel({ history }: { history: DeidentHistoryItem[] }) {
  if (history.length === 0) {
    return (
      <div>
        <h4 className="text-title-sm font-semibold text-gray-700 mb-2">비식별 이력</h4>
        <p className="text-body-md text-gray-600 py-4 text-center bg-gray-50 rounded-lg">
          비식별 이력이 없습니다.
        </p>
      </div>
    );
  }

  return (
    <div>
      <h4 className="text-title-sm font-semibold text-gray-700 mb-2">비식별 이력</h4>
      <ul className="flex flex-col gap-2">
        {history.map((item) => (
          <li key={item.procLogSn} className="bg-gray-50 rounded-lg px-4 py-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-body-md font-medium text-gray-800">
                {item.reqKndCd === 'REDEIDENT' ? '재비식별' : '비식별'}
              </span>
              <span
                className={`text-caption px-2 py-0.5 rounded-full ${statusClass(item.procSttsCd)}`}
              >
                {statusLabel(item.procSttsCd)}
              </span>
              <span className="text-caption text-gray-600">
                요청 {formatDateTime(item.reqDt)}
              </span>
            </div>

            {hasDetectionCounts(item) && (
              <dl className="mt-2 grid grid-cols-3 gap-2">
                <div>
                  <dt className="text-caption text-gray-600">얼굴 검출</dt>
                  <dd className="text-body-md font-medium text-gray-800 tabular-nums">
                    {formatCount(item.faceDtctCnt)}
                  </dd>
                </div>
                <div>
                  <dt className="text-caption text-gray-600">번호판 검출</dt>
                  <dd className="text-body-md font-medium text-gray-800 tabular-nums">
                    {formatCount(item.noPltDtctCnt)}
                  </dd>
                </div>
                <div>
                  <dt className="text-caption text-gray-600">총 프레임</dt>
                  <dd className="text-body-md font-medium text-gray-800 tabular-nums">
                    {formatCount(item.frmeCnt)}
                  </dd>
                </div>
              </dl>
            )}

            {(item.prcsBgngDt || item.prcsEndDt) && (
              <p className="mt-2 text-caption text-gray-600">
                처리 {formatDateTime(item.prcsBgngDt)} ~ {formatDateTime(item.prcsEndDt)}
              </p>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
