// SCR-LABEL — 라벨 변경 이력 패널 (저장 이벤트 단위 + 펼침 diff).
//
// 버전(LS_LABEL_VERSION) 이력 패널(features/version/HistoryPanel)과 별개다.
// 이 패널은 프레임(srcSn) 단위 라벨 변경 이력(LS_DATA_LBL_HSTRY)을 "저장 이벤트" 단위로
// 최신순 카드로 보여주고, 카드를 펼치면 라벨별 추가/수정/삭제 상세(diff)를 노출한다.
//
// 소비 계약(Phase 2 BE): GET /v1/frames/{srcSn}/label-history?page=&size=
//   → ApiResponse<Page<LabelHistoryResponse>> (서버 고정 정렬: 최신순 + tiebreaker)
//
// 보안:
// - 라벨명/작업자 렌더는 React 기본 escape(XSS 방지) — dangerouslySetInnerHTML 미사용.
// - 모델명/내부 경로 등 기술 정보 미노출.

import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, History, RotateCcw } from 'lucide-react';

import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/common/Skeleton';
import { cn } from '@/lib/cn';
import { useIsEditBlocked } from '@/stores/useLabelStore';

import type { LabelHistoryItem } from '../api';
import { LABEL_HISTORY_PAGE_SIZE, useLabelHistory } from '../hooks/useLabelHistory';

import { LabelChangeDetail } from './LabelChangeDetail';

interface LabelHistoryPanelProps {
  /** 프레임 PK (LS_DATA_SRC.SRC_SN). undefined 면 패널 미렌더. */
  srcSn: number | undefined;
  /** 라벨링 화면(다크 테마)용. 기본 false. */
  dark?: boolean;
  /**
   * 저장 이벤트를 현재 작업본에 되돌리기(역적용) 요청 콜백. 지정 시 각 카드에 "되돌리기" 버튼 노출.
   * 미지정(버전 브라우징 페이지 등 작업본 컨텍스트가 없는 곳)이면 버튼을 렌더하지 않는다.
   */
  onRevert?: (item: LabelHistoryItem) => void;
}

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

/** 이벤트 요약 뱃지(추가 +N / 수정 ~N / 삭제 -N). 0 인 종류는 생략. */
function SummaryBadges({ item, dark }: { item: LabelHistoryItem; dark: boolean }) {
  const badges: Array<{ key: string; text: string; light: string; darkCls: string }> = [];
  if (item.addCnt > 0) {
    badges.push({
      key: 'add',
      text: `추가 +${item.addCnt}`,
      light: 'bg-emerald-50 text-emerald-700',
      darkCls: 'bg-emerald-900/40 text-emerald-300',
    });
  }
  if (item.mdfcnCnt > 0) {
    badges.push({
      key: 'mdfcn',
      text: `수정 ~${item.mdfcnCnt}`,
      light: 'bg-blue-50 text-blue-700',
      darkCls: 'bg-blue-900/40 text-blue-300',
    });
  }
  if (item.delCnt > 0) {
    badges.push({
      key: 'del',
      text: `삭제 -${item.delCnt}`,
      light: 'bg-red-50 text-red-700',
      darkCls: 'bg-red-900/40 text-red-300',
    });
  }
  if (badges.length === 0) return null;
  return (
    <span className="flex flex-wrap items-center gap-1">
      {badges.map((b) => (
        <span
          key={b.key}
          className={cn(
            'inline-flex items-center rounded px-1.5 py-0.5 text-[11px] font-medium',
            dark ? b.darkCls : b.light,
          )}
        >
          {b.text}
        </span>
      ))}
    </span>
  );
}

export function LabelHistoryPanel({ srcSn, dark = false, onRevert }: LabelHistoryPanelProps) {
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  // 편집 차단 단일 판정원 — 되돌리기는 작업본(labels/dirty)을 바꾸는 **편집**이다. 저장 in-flight
  // 중에 실행되면 저장 성공 시 clearDirty() 가 되돌린 분의 미저장 표식까지 지워, 이탈 경고·프레임
  // 가드가 풀린 채 서버본이 화면을 덮는다(무음 소실).
  const editBlocked = useIsEditBlocked();

  // 프레임 전환 시 페이지/펼침 상태 초기화 (이전 프레임 상태 잔존 방지).
  useEffect(() => {
    setPage(0);
    setExpanded(new Set());
  }, [srcSn]);

  const { data, isLoading, isError, error, refetch, isFetching } = useLabelHistory(
    srcSn,
    page,
    LABEL_HISTORY_PAGE_SIZE,
  );

  const toggle = (key: number) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

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
          <ul role="status" aria-label="변경 이력 로딩 중" className="flex flex-col gap-2 p-1">
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
              className={cn('flex flex-col divide-y', dark ? 'divide-gray-700' : 'divide-gray-100')}
            >
              {data.content.map((item) => {
                const isOpen = expanded.has(item.lblHstrySn);
                const detailId = `label-history-detail-${item.lblHstrySn}`;
                const Chevron = isOpen ? ChevronDown : ChevronRight;
                return (
                  <li key={item.lblHstrySn} className="py-1 text-xs">
                    <div className="flex items-start gap-1">
                      <button
                        type="button"
                        aria-expanded={isOpen}
                        aria-controls={detailId}
                        onClick={() => toggle(item.lblHstrySn)}
                        className={cn(
                          'flex min-w-0 flex-1 items-start gap-1.5 rounded px-1 py-1 text-left',
                          dark ? 'hover:bg-gray-800' : 'hover:bg-gray-50',
                        )}
                      >
                        <Chevron
                          size={13}
                          aria-hidden="true"
                          className={cn('mt-0.5 shrink-0', mutedText)}
                        />
                        <span className="min-w-0 flex-1">
                          <SummaryBadges item={item} dark={dark} />
                          <span className={cn('mt-0.5 block truncate', mutedText)}>
                            {item.actor ?? '시스템'} · {formatTime(item.regDt)}
                          </span>
                        </span>
                      </button>
                      {onRevert && (
                        <button
                          type="button"
                          aria-label="이 저장으로 되돌리기"
                          title={
                            editBlocked
                              ? '다른 작업이 진행 중입니다. 완료 후 되돌릴 수 있습니다.'
                              : '이 저장으로 되돌리기'
                          }
                          disabled={editBlocked}
                          onClick={() => onRevert(item)}
                          className={cn(
                            'mt-0.5 inline-flex shrink-0 items-center gap-1 rounded px-1.5 py-1 text-[11px] font-medium',
                            'disabled:cursor-not-allowed disabled:opacity-40',
                            dark
                              ? 'text-gray-300 hover:bg-gray-700 hover:text-white'
                              : 'text-gray-600 hover:bg-gray-100 hover:text-gray-800',
                          )}
                        >
                          <RotateCcw size={12} aria-hidden="true" />
                          되돌리기
                        </button>
                      )}
                    </div>
                    {isOpen && (
                      <div id={detailId}>
                        <LabelChangeDetail changes={item.changes} dark={dark} />
                      </div>
                    )}
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
                    dark ? 'text-gray-300 hover:bg-gray-700' : 'text-gray-600 hover:bg-gray-100',
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
                    dark ? 'text-gray-300 hover:bg-gray-700' : 'text-gray-600 hover:bg-gray-100',
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
