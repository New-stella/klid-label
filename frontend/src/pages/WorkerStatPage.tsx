import { useState } from 'react';
import { BarChart2 } from 'lucide-react';

import {
  ApprovedRatioNote,
  approvedRatioWithServerRate,
  formatApprovedValue,
} from '@/components/common/ApprovedRatioNote';
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
import { cn } from '@/lib/cn';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * SCR-STAT-001 작업자 통계 (mock WorkerStats 정합).
 *
 * - REVIEWER: 작업자 선택 드롭다운으로 임의 작업자 통계 조회
 * - WORKER: 본인 통계 고정 (claims.sub)
 * - KPI 4 (완료/진행중/반려/총 라벨 수) + Secondary 2 (오토라벨 비율/반려율)
 * - 일별 작업량 (최근 30일) + 월별 통계 (최근 12개월)
 */

/**
 * 월별 통계 표의 헤더 셀 클래스 — 모든 `<th>` 가 이 한 값을 공유한다(정렬만 덧붙인다).
 *
 * ⚠ **반드시 `<th>` 에 직접 건다.** 구 구현은 이 글자 클래스를 헤더 `<tr>` 에만 걸었는데,
 * `font-weight` 는 상속되더라도 브라우저 UA 기본 `th { font-weight: bold }`(700)가 **직접
 * 적용**되어 상속값을 이긴다 — 그래서 이 표만 700 으로 굵게 렌더됐다(브라우저 실측).
 * ⚠ 굵기는 `text-table-header` step(600)이 단독으로 정한다 — 별도 굵기 클래스를 겹치지 않는다.
 *
 * 글자색은 `text-neutral`(= gray-700 별칭) 대신 gray 축으로 명시한다 — 대비 판정이
 * `text-gray-NNN` 단계를 읽는다. gray-600 은 secondary-50 배경 위에서 5.60:1 로 AA 를
 * 만족한다(gray-500 은 4.01 로 미달).
 */
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';

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

  /**
   * 완료 카드 병기 — 주 수치는 검수완료(completed), 전체·완료율은 보조 라인으로만 병기한다.
   * 검수 승인 = 작업 완료 = 학습데이터 확정 정책이라 미완료분이 주 수치가 아니다.
   *
   * ★완료율은 <b>서버가 내려준 completionRate(0.0~1.0)</b>를 그대로 채택한다 —
   * completed/assignedTotal 을 화면에서 다시 나누지 않는다(사양 SCREEN-020).
   * @design SCREEN-020, API-056
   */
  const completedRatio = approvedRatioWithServerRate(
    data?.completed,
    data?.assignedTotal,
    data?.completionRate,
  );

  /**
   * 총 라벨 수 카드 병기 — 주 수치는 검수완료 영상의 라벨(폐기 프레임 제외)이고 전체는 보조다.
   *
   * ★비율을 <b>넘기지 않는다</b>(세 번째 인자 undefined + `omitRate`) — completionRate 는 영상
   * 건수의 비율이라 라벨 분량에 쓸 수 없고 라벨 단위 비율은 서버가 내려주지 않는다. 화면이
   * approvedLabelCount/labelCount 를 나눠 만들어 붙이는 것도 금지다(사양 SCREEN-020).
   */
  const labelRatio = approvedRatioWithServerRate(
    data?.approvedLabelCount,
    data?.labelCount,
    undefined,
  );

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
              data-testid="worker-kpi-completed"
              label="완료 작업"
              value={data?.completed ?? 0}
              note={<ApprovedRatioNote ratio={completedRatio} unit="건" />}
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
              data-testid="worker-kpi-label-count"
              label="총 라벨 수"
              value={formatApprovedValue(labelRatio)}
              // 완료율을 붙이지 않는다 — 사양 SCREEN-020(라벨 단위 비율은 서버가 주지 않는다).
              note={<ApprovedRatioNote ratio={labelRatio} unit="개" omitRate />}
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
        {/* 본문 셀 크기는 `text-body-md`(17px) 로 명시한다 — 별칭 `text-body` 는 값이 같지만
            표기가 갈리면 표마다 다른 토큰을 쓰는 것처럼 읽힌다. */}
        <table className="min-w-full text-body-md" data-testid="worker-monthly-table">
          {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과 같은
              회색을 쓰면 열 구조가 먼저 읽히지 않는다.
              `<tr>` 에는 배경·테두리만 두고 **글자 축은 `<th>`(TH_CLASS)** 가 갖는다. */}
          <thead>
            <tr className="border-b border-border bg-secondary-50">
              <th className={TH_CLASS}>월</th>
              <th className={cn(TH_CLASS, 'text-right')}>완료</th>
              <th className={cn(TH_CLASS, 'text-right')}>반려</th>
              <th className={cn(TH_CLASS, 'text-right')}>라벨 수</th>
            </tr>
          </thead>
          <tbody>
            {(data?.monthly ?? []).map((m) => (
              <tr
                key={m.month}
                className="border-b border-border transition-colors hover:bg-rowHover"
              >
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
                {/* 빈 상태 안내도 표 본문이라 본문 크기(17px)를 따른다 — 다른 표들은 같은
                    자리를 `EmptyState`(text-body=17px) 에 위임하고 있어, 여기만 14px 이면
                    같은 화면 안에서 안내 문구 크기가 갈린다. */}
                <td colSpan={4} className="px-3 py-4 text-center text-body-md text-neutral">
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
