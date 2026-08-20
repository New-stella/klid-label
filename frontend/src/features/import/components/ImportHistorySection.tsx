import { Fragment, useState } from 'react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Pagination } from '@/components/common/Pagination';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { Skeleton } from '@/components/common/Skeleton';

import { approvalHoldText, canRecordDeidentComplete } from '../approvalHold';
import { useImportHistory, useImportHistoryDetail } from '../hooks/useImportHistory';
import { ImportStatus, type ImportHistoryItem } from '../types';

import { DeidentCompleteDialog } from './DeidentCompleteDialog';

/** 표 헤더 셀 — DS-001 표 표면 관례(14px/600 토큰 + 대문자화). th 에 직접 건다. */
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';

const PAGE_SIZE = 20;

/** 이관 상태 필터 — 값이 빈 문자열이면 전체. */
const STATUS_FILTERS = [
  { value: '', label: '전체' },
  { value: ImportStatus.PROCESSING, label: '진행중' },
  { value: ImportStatus.SUCCESS, label: '성공' },
  { value: ImportStatus.FAILED, label: '실패' },
] as const;

const STATUS_LABEL: Record<ImportStatus, string> = {
  [ImportStatus.PROCESSING]: '진행중',
  [ImportStatus.SUCCESS]: '성공',
  [ImportStatus.FAILED]: '실패',
};

/** 실패 사유 펼침 — 상세(API-208)에만 있는 값이라 펼칠 때 조회한다. */
function FailReasonRow({ trnsfSn, columnCount }: { trnsfSn: number; columnCount: number }) {
  const { data, isLoading, error } = useImportHistoryDetail(trnsfSn);
  return (
    <tr className="border-b border-gray-100 bg-gray-50">
      <td className="px-3 py-2 text-gray-700" colSpan={columnCount}>
        {isLoading && <span className="text-body-sm text-gray-600">사유를 불러오는 중…</span>}
        {error && <span className="text-body-sm text-gray-700">사유를 불러올 수 없습니다.</span>}
        {data && (
          <span className="text-body-sm text-gray-800" data-testid={`import-fail-reason-${trnsfSn}`}>
            {data.failReason ?? '기록된 사유가 없습니다.'}
          </span>
        )}
      </td>
    </tr>
  );
}

/**
 * 이관 이력 — 지금까지 가져온 내역을 최근순으로 보여준다.
 *
 * 정렬은 시간순 하나이며 상태를 우선순위로 섞지 않는다(정렬 기준은 서버가 고정한다).
 *
 * ★승인 보류가 선 영상은 이 목록에서 드러나고, 그 행에서 비식별 완료를 기록하는 자리를 연다.
 * 검수 화면이 아니라 이 자리에 두는 까닭은 이 목록이 이미 영상별 상태를 보여주고 있고 검수
 * 화면은 다른 갈래의 소관이라 경계를 넘기 때문이다.
 *
 * ★보류 여부는 이관 상태와 다른 축이며 **값이 없을 수 있다**. 없음을 「보류 아님」으로 단정하지
 * 않는다 — 판정은 {@link canRecordDeidentComplete} 한 곳이 소유한다.
 *
 * @design SCREEN-039
 * @design API-207
 * @design API-208
 * @design API-215
 */
export function ImportHistorySection() {
  const [status, setStatus] = useState<string>('');
  const [page, setPage] = useState(0);
  /** 실패 사유를 펼친 행. */
  const [expanded, setExpanded] = useState<number | null>(null);
  /** 비식별 완료 기록 대상 영상. null 이면 닫혀 있다. */
  const [recordingRawSn, setRecordingRawSn] = useState<number | null>(null);

  const params = {
    ...(status ? { status: status as ImportStatus } : {}),
    page,
    size: PAGE_SIZE,
  };
  const { data, isLoading, error } = useImportHistory(params);

  const rows: ImportHistoryItem[] = data?.content ?? [];
  const totalPages = Math.max(1, data?.totalPages ?? 1);
  const currentPage = data?.number ?? page;
  const columnCount = 8;

  return (
    <section
      aria-labelledby="import-history-heading"
      data-testid="import-history-section"
      className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
    >
      <h2 id="import-history-heading" className="text-title-sm text-gray-900">
        가져온 내역
      </h2>

      <div className="flex flex-wrap items-center gap-2">
        <label className="text-label font-medium text-gray-700" htmlFor="import-history-status">
          상태
        </label>
        <Select
          value={status}
          onValueChange={(v) => {
            setStatus(v);
            setPage(0);
          }}
        >
          <SelectTrigger id="import-history-status" size="sm">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {STATUS_FILTERS.map((o) => (
              <SelectItem key={o.value} value={o.value}>
                {o.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <span className="ml-auto inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-600">
          {data?.totalElements ?? 0}건
        </span>
      </div>

      {isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} height={40} />
          ))}
        </div>
      )}

      {error && <ErrorState title="이관 이력을 불러올 수 없습니다" />}

      {data && rows.length === 0 && <EmptyState message="가져온 내역이 없습니다." />}

      {data && rows.length > 0 && (
        <div className="overflow-hidden rounded-lg border border-gray-200">
          <table className="w-full text-body-md" data-testid="import-history-table">
            <thead>
              <tr className="border-b border-gray-200 bg-secondary-50">
                <th className={TH_CLASS}>가져온 시각</th>
                <th className={TH_CLASS}>폴더명</th>
                <th className={TH_CLASS}>영상 번호</th>
                <th className={TH_CLASS}>상태</th>
                {/* 승인 보류는 이관 상태와 다른 축이다 — 상태로 대신 읽을 수 없다. */}
                <th className={TH_CLASS}>승인 보류</th>
                <th className={TH_CLASS}>프레임 수</th>
                <th className={TH_CLASS}>라벨 수</th>
                <th className={TH_CLASS}>실행자</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <Fragment key={r.trnsfSn}>
                  <tr
                    data-testid={`import-history-row-${r.trnsfSn}`}
                    className="border-b border-gray-100 transition-colors hover:bg-rowHover"
                  >
                    <td className="px-3 py-2 text-gray-600">
                      {new Date(r.regDt).toLocaleString('ko-KR')}
                    </td>
                    <td className="max-w-[220px] truncate px-3 py-2 text-gray-700" title={r.folderName ?? ''}>
                      {r.folderName ?? '-'}
                    </td>
                    <td className="px-3 py-2 text-gray-700">
                      {r.rawSn === null ? (
                        '-'
                      ) : (
                        <span className="font-mono text-mono">{r.rawSn}</span>
                      )}
                    </td>
                    {/*
                      실패 사유 펼침은 상태 축에 딸린 것이라 상태 셀 안에 둔다 — 사양이 선언한
                      열 구성(8열)을 그대로 유지하려고 별도 조작 열을 만들지 않는다.
                    */}
                    <td className="px-3 py-2 text-gray-700">
                      {r.status === ImportStatus.FAILED ? (
                        <Button
                          size="sm"
                          variant="ghost"
                          data-testid={`import-fail-toggle-${r.trnsfSn}`}
                          aria-expanded={expanded === r.trnsfSn}
                          onClick={() =>
                            setExpanded((prev) => (prev === r.trnsfSn ? null : r.trnsfSn))
                          }
                        >
                          {expanded === r.trnsfSn ? '실패 · 접기' : '실패 · 사유 보기'}
                        </Button>
                      ) : (
                        (STATUS_LABEL[r.status] ?? r.status)
                      )}
                    </td>
                    <td className="px-3 py-2" data-testid={`import-hold-${r.trnsfSn}`}>
                      <div className="flex items-center gap-2">
                        <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-700">
                          {approvalHoldText(r.approvalHeld)}
                        </span>
                        {canRecordDeidentComplete(r) && (
                          <Button
                            size="sm"
                            variant="secondary"
                            data-testid={`import-deident-record-${r.trnsfSn}`}
                            onClick={() => setRecordingRawSn(r.rawSn)}
                          >
                            비식별 완료 기록
                          </Button>
                        )}
                      </div>
                    </td>
                    <td className="px-3 py-2 text-gray-700">{r.frameCount ?? '-'}</td>
                    <td className="px-3 py-2 text-gray-700">{r.labelCount ?? '-'}</td>
                    <td className="px-3 py-2 text-gray-700">{r.regId ?? '-'}</td>
                  </tr>
                  {expanded === r.trnsfSn && (
                    <FailReasonRow trnsfSn={r.trnsfSn} columnCount={columnCount} />
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
          {totalPages > 1 && (
            <div className="border-t border-gray-200 bg-gray-50 px-3 py-2">
              <Pagination page={currentPage} totalPages={totalPages} onChange={setPage} />
            </div>
          )}
        </div>
      )}

      <DeidentCompleteDialog rawSn={recordingRawSn} onClose={() => setRecordingRawSn(null)} />
    </section>
  );
}
