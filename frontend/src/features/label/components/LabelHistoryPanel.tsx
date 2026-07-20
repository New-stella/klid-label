// SCR-LABEL — 라벨 변경 이력 리스트 패널 (Phase 5 / A-3).
//
// 버전(LS_LABEL_VERSION) 이력 패널(features/version/HistoryPanel)과 별개다.
// 이 패널은 프레임(srcSn) 단위 라벨 변경 이력(LS_DATA_LBL_HSTRY: 추가/수정/삭제)을 최신순으로 보여준다.
//
// 소비 계약(Phase 2 BE): GET /v1/frames/{srcSn}/label-history?page=&size=
//   → ApiResponse<Page<LabelHistoryResponse>> (서버 고정 정렬: 최신순 + tiebreaker)
//
// 보안:
// - 라벨명/작업자 렌더는 React 기본 escape(XSS 방지) — dangerouslySetInnerHTML 미사용.
// - 모델명/내부 경로 등 기술 정보 미노출.

import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2, History } from 'lucide-react';

import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/common/Skeleton';
import { cn } from '@/lib/cn';

import type { LabelChangeKind } from '../api';
import { LABEL_HISTORY_PAGE_SIZE, useLabelHistory } from '../hooks/useLabelHistory';

interface LabelHistoryPanelProps {
  /** 프레임 PK (LS_DATA_SRC.SRC_SN). undefined 면 패널 미렌더. */
  srcSn: number | undefined;
  /** 라벨링 화면(다크 테마)용. 기본 false. */
  dark?: boolean;
}

/** 변경종류 → 한글 라벨 + 아이콘 + 색상(라이트/다크). */
const KIND_META: Record<
  LabelChangeKind,
  { label: string; Icon: typeof Plus; light: string; darkCls: string }
> = {
  ADDED: {
    label: '추가',
    Icon: Plus,
    light: 'bg-emerald-50 text-emerald-700',
    darkCls: 'bg-emerald-900/40 text-emerald-300',
  },
  UPDATED: {
    label: '수정',
    Icon: Pencil,
    light: 'bg-blue-50 text-blue-700',
    darkCls: 'bg-blue-900/40 text-blue-300',
  },
  DELETED: {
    label: '삭제',
    Icon: Trash2,
    light: 'bg-red-50 text-red-700',
    darkCls: 'bg-red-900/40 text-red-300',
  },
};

function formatTime(iso: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function LabelHistoryPanel({ srcSn, dark = false }: LabelHistoryPanelProps) {
  const [page, setPage] = useState(0);

  // 프레임 전환 시 페이지 초기화 (이전 프레임 페이지 상태 잔존 방지).
  useEffect(() => {
    setPage(0);
  }, [srcSn]);

  const { data, isLoading, isError, error, refetch, isFetching } = useLabelHistory(
    srcSn,
    page,
    LABEL_HISTORY_PAGE_SIZE,
  );

  const headerText = dark ? 'text-gray-200' : 'text-gray-700';
  const mutedText = dark ? 'text-gray-400' : 'text-gray-500';

  return (
    <section
      aria-label="라벨 변경 이력"
      className={cn(
        'flex flex-col',
        dark ? 'border-t border-gray-700' : 'border-t border-gray-200',
      )}
    >
      <div
        className={cn(
          'flex items-center gap-1.5 px-3 py-2 text-xs font-semibold uppercase tracking-wide',
          dark ? 'border-b border-gray-700' : 'border-b border-gray-200',
          headerText,
        )}
      >
        <History size={14} aria-hidden="true" />
        <span>라벨 변경 이력</span>
      </div>

      <div className="p-2">
        {isLoading ? (
          <ul
            role="status"
            aria-label="변경 이력 로딩 중"
            className="flex flex-col gap-2 p-1"
          >
            {[0, 1, 2].map((i) => (
              <li key={i} className="flex items-center gap-2">
                <Skeleton width={40} height={18} />
                <Skeleton width="60%" height={14} />
              </li>
            ))}
          </ul>
        ) : isError ? (
          <ErrorState
            title="변경 이력 조회 실패"
            message={error instanceof Error ? error.message : '잠시 후 다시 시도해주세요'}
            onRetry={() => refetch()}
          />
        ) : !data || data.content.length === 0 ? (
          <EmptyState title="변경 이력이 없습니다" message="라벨을 저장하면 이력이 기록됩니다." />
        ) : (
          <>
            <ul
              aria-label="라벨 변경 이력 목록"
              className={cn(
                'flex flex-col divide-y',
                dark ? 'divide-gray-700' : 'divide-gray-100',
              )}
            >
              {data.content.map((item) => {
                const meta = KIND_META[item.changeKind];
                const KindIcon = meta.Icon;
                return (
                  <li key={item.lblHstrySn} className="flex items-start gap-2 px-1 py-2 text-xs">
                    <span
                      className={cn(
                        'inline-flex shrink-0 items-center gap-1 rounded px-1.5 py-0.5 font-medium',
                        dark ? meta.darkCls : meta.light,
                      )}
                    >
                      <KindIcon size={11} aria-hidden="true" />
                      {meta.label}
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className={cn('truncate', dark ? 'text-gray-100' : 'text-gray-800')}>
                        {item.label ?? '(삭제된 라벨)'}
                      </p>
                      <p className={cn('mt-0.5 truncate', mutedText)}>
                        {item.actor ?? '시스템'} · {formatTime(item.regDt)}
                      </p>
                    </div>
                  </li>
                );
              })}
            </ul>

            {data.totalPages > 1 && (
              <div className="mt-2 flex items-center justify-between px-1">
                <button
                  type="button"
                  aria-label="이전 페이지"
                  disabled={page <= 0 || isFetching}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  className={cn(
                    'rounded px-2 py-1 text-xs font-medium disabled:opacity-40 disabled:cursor-not-allowed',
                    dark
                      ? 'text-gray-300 hover:bg-gray-700'
                      : 'text-gray-600 hover:bg-gray-100',
                  )}
                >
                  이전
                </button>
                <span className={cn('text-xs', mutedText)}>
                  {data.number + 1} / {data.totalPages}
                </span>
                <button
                  type="button"
                  aria-label="다음 페이지"
                  disabled={page + 1 >= data.totalPages || isFetching}
                  onClick={() => setPage((p) => p + 1)}
                  className={cn(
                    'rounded px-2 py-1 text-xs font-medium disabled:opacity-40 disabled:cursor-not-allowed',
                    dark
                      ? 'text-gray-300 hover:bg-gray-700'
                      : 'text-gray-600 hover:bg-gray-100',
                  )}
                >
                  다음
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </section>
  );
}
