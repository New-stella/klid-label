import { useState } from 'react';
import { BarChart2 } from 'lucide-react';

import { ErrorState } from '@/components/common/ErrorState';
import { KpiCard } from '@/components/common/KpiCard';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { Skeleton } from '@/components/common/Skeleton';
import { DailyCompletionChart } from '@/features/stat/components/DailyCompletionChart';
import { useWorkerStat } from '@/features/stat/hooks/useWorkerStat';
import { useUsers } from '@/features/user/hooks/useUsers';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * SCR-STAT-001 작업자 통계 (mock WorkerStats 정합).
 *
 * - REVIEWER: 작업자 선택 드롭다운으로 임의 작업자 통계 조회
 * - WORKER: 본인 통계 고정 (claims.sub)
 * - KPI 4 (완료/진행중/반려/총 라벨 수) + Secondary 2 (오토라벨 비율/반려율)
 * - 일별 작업량 (최근 30일) + 월별 통계 (최근 12개월)
 */
export function WorkerStatPage() {
  const claims = useAuthStore((s) => s.claims);
  const isReviewer = claims?.role === Role.REVIEWER;
  const myId = claims?.sub;

  // REVIEWER만 작업자 목록 로드 (selector) — /users 는 REVIEWER 전용 API 라 enabled 로 호출 자체를 막는다.
  const { data: workersPage } = useUsers(
    { role: Role.WORKER, size: 100 },
    { enabled: isReviewer },
  );
  const workers = isReviewer ? workersPage?.content ?? [] : [];

  const [selectedWorkerId, setSelectedWorkerId] = useState<string | null>(null);

  /**
   * 조회 대상 작업자.
   *
   * ★REVIEWER 는 **자동 폴백을 두지 않는다**(사양 SCREEN-020 — 구 '첫 번째 작업자 자동 선택'
   * 정책은 폐기). 구 동작은 아무도 고르지 않았는데 `workers[0]` 의 통계를 띄워, 화면의 숫자가
   * 누구 것인지 사용자가 선택한 적 없는 상태로 사실처럼 읽혔다. 선택 전에는 아래 미선택 안내가
   * KPI·차트·표 전체를 대신한다. WORKER 는 본인(claims.sub) 고정이라 이 상태에 도달하지 않는다.
   */
  const targetWorkerId: string | number | undefined = isReviewer
    ? selectedWorkerId ?? undefined
    : myId;

  const needsWorkerSelection = isReviewer && !selectedWorkerId;

  const { data, isLoading, error } = useWorkerStat(targetWorkerId);

  // 제목·부제는 역할별로 분기한다(사양 SCREEN-020).
  const pageTitle = isReviewer ? '작업자 통계' : '나의 통계';
  const pageSubtitle = isReviewer
    ? '작업자별 통계를 확인합니다.'
    : '나의 작업 통계를 확인합니다.';

  return (
    <section className="flex flex-col gap-6" data-testid="worker-stat-page">
      {/* Header */}
      <div className="flex items-center justify-between">
        {/* 제목 옆 장식 아이콘은 두지 않는다 — 제목 텍스트를 되풀이할 뿐이다.
            아래 미선택 안내의 큰 아이콘은 공용 EmptyState 와 같은 삽화 역할이라 유지한다. */}
        <div>
          <h1 className="text-title-lg font-bold text-gray-900">{pageTitle}</h1>
          <p className="mt-0.5 text-caption text-gray-600">{pageSubtitle}</p>
          {data?.workerName && (
            <p className="mt-0.5 text-caption text-gray-400">{data.workerName}</p>
          )}
        </div>

        {isReviewer && workers.length > 0 && (
          // 초기값 없음 — placeholder 로 "고르지 않았다"는 상태를 그대로 보여준다.
          <Select value={selectedWorkerId ?? ''} onValueChange={setSelectedWorkerId}>
            <SelectTrigger aria-label="작업자 선택">
              <SelectValue placeholder="작업자 선택" />
            </SelectTrigger>
            <SelectContent>
              {workers.map((w) => (
                <SelectItem key={w.id} value={String(w.id)}>
                  {w.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </div>

      {/* 미선택 상태에서는 에러 배너를 띄우지 않는다 — 그 구간의 조회 결과는 화면에 쓰이지 않으므로
          실패해도 사용자가 할 일은 "작업자를 고르는 것" 하나뿐이다(안내와 에러가 겹치면 혼선). */}
      {error && !needsWorkerSelection && (
        <ErrorState title="통계 정보를 불러올 수 없습니다" />
      )}

      {needsWorkerSelection ? (
        /* 미선택 안내 — KPI/보조지표/일별 차트/월별 표 섹션 전체를 대체한다(사양 SCREEN-020). */
        <div
          data-testid="worker-stat-empty"
          className="rounded-lg border border-gray-200 bg-white p-12 text-center"
        >
          <BarChart2 size={40} className="mx-auto mb-3 text-gray-300" aria-hidden />
          <p className="text-body-md text-gray-500">작업자를 선택하세요</p>
          <p className="mt-1 text-caption text-gray-400">
            상단에서 작업자를 선택하면 해당 작업자의 통계가 표시됩니다.
          </p>
        </div>
      ) : (
        <>

      {/* KPI 4개 */}
      <div
        data-testid="worker-kpi-grid"
        className="grid grid-cols-2 gap-4 md:grid-cols-4"
      >
        {isLoading ? (
          Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} height={84} className="w-full" />
          ))
        ) : (
          <>
            <KpiCard
              label="완료 작업"
              value={data?.completed ?? 0}
            />
            <KpiCard
              label="작업중"
              value={data?.inProgress ?? 0}
            />
            <KpiCard
              label="반려"
              value={data?.rejected ?? 0}
            />
            <KpiCard
              label="총 라벨 수"
              value={data?.labelCount ?? 0}
            />
          </>
        )}
      </div>

      {/* Secondary KPIs (오토라벨 비율 / 반려율) */}
      {data && (() => {
        const autoLabelPct = Number.isFinite(data.autoLabelRate) ? data.autoLabelRate * 100 : null;
        const rejectPct = Number.isFinite(data.rejectRate) ? data.rejectRate * 100 : null;
        return (
          <div className="grid grid-cols-2 gap-4">
            <div className="rounded-lg border border-gray-200 bg-white px-5 py-4">
              <p className="mb-1 text-caption text-gray-500">오토라벨 비율</p>
              <p className="text-title-lg font-bold tabular-nums text-gray-900">
                {autoLabelPct === null ? '—' : `${autoLabelPct.toFixed(1)}%`}
              </p>
            </div>
            <div className="rounded-lg border border-gray-200 bg-white px-5 py-4">
              <p className="mb-1 text-caption text-gray-500">반려율</p>
              <p
                className={[
                  'text-title-lg font-bold tabular-nums',
                  rejectPct !== null && rejectPct > 10 ? 'text-danger' : 'text-gray-900',
                ].join(' ')}
              >
                {rejectPct === null ? '—' : `${rejectPct.toFixed(1)}%`}
              </p>
            </div>
          </div>
        );
      })()}

      {/* 일별 작업량 차트 */}
      <section className="rounded-lg border border-gray-200 bg-white p-5">
        <h2 className="mb-4 text-title-sm font-semibold text-gray-700">
          일별 작업량 (최근 30일)
        </h2>
        <DailyCompletionChart data={data?.dailyCompletion ?? []} />
      </section>

      {/* 월별 통계 표 */}
      <section className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <div className="border-b border-gray-100 px-5 py-4">
          <h2 className="text-title-sm font-semibold text-gray-700">
            월별 통계 (최근 12개월)
          </h2>
        </div>
        <table className="min-w-full text-body" data-testid="worker-monthly-table">
          <thead>
            <tr className="border-b border-border text-sub text-neutral">
              <th className="px-3 py-2 text-left">월</th>
              <th className="px-3 py-2 text-right">완료</th>
              <th className="px-3 py-2 text-right">반려</th>
              <th className="px-3 py-2 text-right">라벨 수</th>
            </tr>
          </thead>
          <tbody>
            {(data?.monthly ?? []).map((m) => (
              <tr key={m.month} className="border-b border-border">
                <td className="px-3 py-2">{m.month}</td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {m.completed.toLocaleString('ko-KR')}
                </td>
                <td className="px-3 py-2 text-right tabular-nums text-danger">
                  {m.rejected.toLocaleString('ko-KR')}
                </td>
                <td className="px-3 py-2 text-right tabular-nums">
                  {m.labelCount.toLocaleString('ko-KR')}
                </td>
              </tr>
            ))}
            {(!data?.monthly || data.monthly.length === 0) && (
              <tr>
                <td colSpan={4} className="px-3 py-4 text-center text-sub text-neutral">
                  월별 데이터가 없습니다
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>
        </>
      )}
    </section>
  );
}
