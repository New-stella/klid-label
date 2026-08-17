import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Pagination } from '@/components/common/Pagination';
import { Skeleton } from '@/components/common/Skeleton';
import { DeidentResolveDialog } from '@/features/deident/components/DeidentResolveDialog';
import {
  useDeidentReports,
  useResolveDeidentReport,
} from '@/features/deident/hooks/useDeidentReports';
import {
  DeidentReportStage,
  DeidentReportStatus,
  type DeidentReportRow,
  type DeidentReportStatus as Status,
} from '@/features/deident/reportTypes';
import { resolveDisplayName } from '@/lib/displayName';
import { useUiStore } from '@/stores/useUiStore';

/**
 * 상태 필터 탭 — 표시 문구는 사양 SCREEN-032 문자열(미처리 / 처리완료)을 따른다.
 *
 * ★서버 코드값(OPEN / RESOLVED)을 화면 문구에 노출하지 않는다. 값은 `value` 와 조회
 * 파라미터에만 있다. 같은 화면의 상태 배지({@link StatusCell})가 이미 이 규칙을 지키고 있어,
 * 탭만 코드값을 병기하면 한 화면 안에서 같은 상태를 두 이름으로 부르게 된다.
 */
const STATUS_TABS: { value: Status; label: string }[] = [
  { value: DeidentReportStatus.OPEN, label: '미처리' },
  { value: DeidentReportStatus.RESOLVED, label: '처리완료' },
];

const PAGE_SIZE = 20;

/**
 * 신고 단계 표시 — 해소 시 무엇이 일어나는지를 REVIEWER 가 목록에서 바로 읽게 한다.
 *
 * 문구 규칙:
 * - 서버 코드값 원문(MARKING/LABELING)이나 내부 컬럼명을 화면에 노출하지 않는다.
 * - 미기록(null)은 빈칸으로 두지 않는다 — 빈칸은 "값이 없다"와 "로딩 실패"가 구분되지 않는다.
 * - '미상' 은 "단계가 없다" 가 아니라 "기록이 없다" 는 뜻이다. 이 신고는 해소해도
 *   단계별 재개(재마킹 / 프레임 재추출)가 일어나지 않으므로 그 사실을 툴팁으로 알린다.
 */
const STAGE_DISPLAY: Record<DeidentReportStage, { label: string; hint: string }> = {
  [DeidentReportStage.MARKING]: {
    label: '마킹',
    hint: '마킹 화면에서 접수된 신고입니다. 해소하면 마킹부터 다시 진행합니다.',
  },
  [DeidentReportStage.LABELING]: {
    label: '라벨링',
    hint: '라벨링 화면에서 접수된 신고입니다. 해소하면 프레임 이미지만 다시 만들고 기존 마킹·라벨은 유지합니다.',
  },
};

const STAGE_UNKNOWN = {
  label: '미상',
  hint: '신고 단계가 기록되기 전에 접수된 신고입니다. 해소해도 재마킹·프레임 재추출은 자동으로 진행되지 않습니다.',
};

function StageCell({ stage }: { stage: DeidentReportRow['stage'] }) {
  const known = stage ? STAGE_DISPLAY[stage] : undefined;
  const display = known ?? STAGE_UNKNOWN;
  return (
    <span
      title={display.hint}
      className={
        known
          ? 'inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-700'
          : 'inline-flex items-center rounded-full bg-gray-50 px-2 py-0.5 text-label font-medium text-gray-400'
      }
    >
      {display.label}
    </span>
  );
}

/**
 * 신고 상태 배지 — 사양 SCREEN-032 의 '상태' 전용 컬럼.
 *
 * 기존에는 '처리' 컬럼(해소 버튼 / 해소일 텍스트)이 상태를 **암묵적으로** 표현했다.
 * 그 컬럼은 '무엇을 할 수 있는가'(액션) 축이라 상태를 읽으려면 버튼 유무를 역추론해야 했고,
 * 목록을 상태로 훑을 수 없었다. 표시 문구는 사양 문자열(미처리/처리완료)을 따른다.
 */
function StatusCell({ status }: { status: Status }) {
  const isOpen = status === DeidentReportStatus.OPEN;
  return (
    <span
      className={
        isOpen
          ? 'inline-flex items-center rounded-full bg-warning/10 px-2 py-0.5 text-label font-medium text-warning-700'
          : 'inline-flex items-center rounded-full bg-success/10 px-2 py-0.5 text-label font-medium text-success-700'
      }
    >
      {isOpen ? '미처리' : '처리완료'}
    </span>
  );
}

/**
 * SCR-MANAGE-DEIDENT — 비식별 신고 관리 (REVIEWER 전용, `/manage/deident-reports`).
 *
 * 라벨링/마킹 중 작업자가 비식별 누락을 신고하면 영상이 잠기고 `DE_IDNTF_YN='F'` 가 된다.
 * ★라벨은 **삭제하지 않고 보존**한다(2026-07-27 사용자 확정 — 구 "신고 시 라벨 전체 삭제" 정책 폐기).
 * 대신 신고 구간 동안 라벨 **조회·저장이 412 로 차단**되고(스트리밍·프레임 이미지 등 다른 게이트 포함),
 * 해소되면 `'F'→'Y'` 복원으로 게이트가 자동 해제되어 **보존된 기존 라벨을 그대로 재사용**한다
 * (별도 복원 API 없음).
 * REVIEWER 는 본 화면에서 OPEN 신고를 확인하고, 외부 솔루션으로 수동 비식별화를 완료한 뒤
 * "해소 처리" 로 잠금을 해제한다(POST /v1/deident-reports/{rprtSn}/resolve).
 *
 * ★ 해소는 **재비식별 산출물을 고르는 절차**다({@link DeidentResolveDialog}). 외부 솔루션이 결과를
 * 원본과 다른 이름으로 만들면(예: `001.mp4` → `001-mask.mp4`) 시스템이 어느 파일이 결과인지 알 수
 * 없어, 구 동작에서는 그런 신고가 영영 해소되지 않았다. 그래서 버튼은 곧바로 해소하지 않고
 * 후보 목록을 띄우며, **기본 선택 없이** 사람이 고른 뒤에만 요청이 나간다.
 *
 * 보안:
 * - REVIEWER 역할 검증은 라우터 RoleGuard + BE @PreAuthorize 이중.
 * - 신고 사유(reason)는 사용자 입력 — React 가 자동 escape 하여 텍스트로만 렌더(XSS 방어).
 * - 산출물의 내부 저장 경로는 응답에도 화면에도 없다(파일명만).
 */
/** 표 헤더 셀 — DS-001 표 표면 관례(14px/600 토큰 + 대문자화). <th> 에 직접 건다. */
const TH_CLASS =
  'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';

export function DeidentReportListPage() {
  const pushToast = useUiStore((s) => s.pushToast);
  const [status, setStatus] = useState<Status>(DeidentReportStatus.OPEN);
  const [page, setPage] = useState(0);
  /** 해소 다이얼로그 대상 — null 이면 닫힘. */
  const [resolving, setResolving] = useState<DeidentReportRow | null>(null);

  const { data, isLoading, error } = useDeidentReports({
    status,
    page,
    size: PAGE_SIZE,
  });

  const { mutate: resolve, isPending } = useResolveDeidentReport({
    onSuccess: () => {
      setResolving(null);
      pushToast({ variant: 'success', message: '신고가 해소되었습니다.' });
    },
    onError: () => pushToast({ variant: 'error', message: '해소 처리에 실패했습니다.' }),
  });

  const rows = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = Math.max(1, data?.totalPages ?? 1);
  const currentPage = data?.number ?? page;

  const changeStatus = (next: Status) => {
    setStatus(next);
    setPage(0);
  };

  return (
    <section className="flex flex-col gap-4" data-testid="deident-report-page">
      <PageHeader
        title="비식별 신고 관리"
        description="라벨링·마킹 중 신고된 비식별 누락 건을 확인하고 외부 수동 비식별화 완료 후 해소 처리합니다."
      />

      <div className="flex gap-2" role="tablist" aria-label="신고 상태 필터">
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.value}
            type="button"
            role="tab"
            aria-selected={status === tab.value}
            data-testid={`deident-status-tab-${tab.value}`}
            onClick={() => changeStatus(tab.value)}
            className={
              status === tab.value
                ? 'rounded-md border border-primary-500 bg-primary-50 px-3 py-1.5 text-body-md font-medium text-primary-700'
                : 'rounded-md border border-gray-200 bg-white px-3 py-1.5 text-body-md text-gray-600 hover:bg-gray-50'
            }
          >
            {tab.label}
          </button>
        ))}
        <span className="ml-auto inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-600">
          {totalElements}건
        </span>
      </div>

      {isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} height={40} />
          ))}
        </div>
      )}

      {error && <ErrorState title="신고 목록을 불러올 수 없습니다" />}

      {data && rows.length === 0 && (
        <EmptyState
          message={
            status === DeidentReportStatus.OPEN
              ? '미해소 신고가 없습니다.'
              : '해소된 신고가 없습니다.'
          }
        />
      )}

      {data && rows.length > 0 && (
        <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-body-md" data-testid="deident-report-table">
            <thead>
              {/* 배경은 헤더 행에 두고, 타이포·색은 각 <th> 에 직접 건다 — <tr>/<thead> 에만 걸면
                  브라우저 UA 기본 `th { font-weight: bold }` 가 상속값을 이겨 굵기가 어긋난다
                  (jsdom 은 스타일을 계산하지 않아 이 어긋남을 못 잡는다). */}
              <tr className="border-b border-gray-200 bg-secondary-50">
                <th className={TH_CLASS}>신고 번호</th>
                <th className={TH_CLASS}>영상</th>
                <th className={TH_CLASS}>신고자</th>
                <th className={TH_CLASS}>사유</th>
                <th className={TH_CLASS}>신고일시</th>
                {/* 사양 SCREEN-032 컬럼 순서: 신고 번호 · 영상 · 신고자 · 사유 · 신고일시 · 신고 단계 · 상태 · 처리.
                    '신고 단계'는 신고 사실(누가·왜·언제)을 읽은 뒤에 오는 부가 축이라 뒤에 둔다. */}
                <th className={TH_CLASS}>신고 단계</th>
                <th className={TH_CLASS}>상태</th>
                <th className={TH_CLASS}>처리</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr
                  key={r.rprtSn}
                  data-testid={`deident-report-row-${r.rprtSn}`}
                  className="border-b border-gray-100 transition-colors hover:bg-rowHover"
                >
                  {/* ⚠ 셀에 축소 크기 토큰을 걸지 않는다 — 표 본문은 17px 이 규정값이고(DS-001),
                      작아야 하는 것(식별자·배지)은 셀 **안쪽** 요소에 둔다. */}
                  <td className="px-3 py-2 text-gray-600">
                    <span className="font-mono text-mono">#{r.rprtSn}</span>
                  </td>
                  <td className="px-3 py-2 text-gray-700">
                    <span className="font-mono text-mono">영상 #{r.rawSn}</span>
                  </td>
                  {/* 신고자 — 표시명 우선, 없으면 원값(reporterNo) 폴백. 둘 다 없으면 '-'. */}
                  <td
                    className="px-3 py-2 text-gray-700"
                    data-testid={`deident-reporter-${r.rprtSn}`}
                  >
                    {resolveDisplayName(r.reporterName, r.reporterNo) ?? '-'}
                  </td>
                  <td className="max-w-[280px] truncate px-3 py-2 text-gray-700" title={r.reason}>
                    {r.reason}
                  </td>
                  <td className="px-3 py-2 text-gray-600">
                    {new Date(r.reportDt).toLocaleString('ko-KR')}
                  </td>
                  <td className="px-3 py-2" data-testid={`deident-stage-${r.rprtSn}`}>
                    <StageCell stage={r.stage} />
                  </td>
                  <td className="px-3 py-2" data-testid={`deident-status-${r.rprtSn}`}>
                    <StatusCell status={r.status} />
                  </td>
                  <td className="px-3 py-2">
                    {r.status === DeidentReportStatus.OPEN ? (
                      <Button
                        size="sm"
                        variant="primary"
                        data-testid={`deident-resolve-${r.rprtSn}`}
                        disabled={isPending}
                        onClick={() => setResolving(r)}
                      >
                        해소 처리
                      </Button>
                    ) : (
                      <span className="text-caption text-gray-600">
                        {r.resolvedDt
                          ? `해소 ${new Date(r.resolvedDt).toLocaleDateString('ko-KR')}`
                          : '해소됨'}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {/*
            페이저는 공용 컴포넌트(UI-008)를 쓴다 — 사양의 "양끝 + 현재 앞뒤 1칸 + 말줄임"
            규칙이 그 컴포넌트 한 곳에 있고, 화면마다 다시 만들면 규칙이 갈린다.
            구 동작은 번호 없는 이전/다음뿐이라 임의 페이지로 건너뛸 수 없었다.
            총 건수 요약은 공용 페이저가 갖지 않으므로 이 화면이 계속 소유한다
            (상단 상태 필터 우측의 '{totalElements}건' 배지).
          */}
          {totalPages > 1 && (
            <div className="border-t border-gray-200 bg-gray-50 px-3 py-2">
              <Pagination page={currentPage} totalPages={totalPages} onChange={setPage} />
            </div>
          )}
        </div>
      )}

      {/* 해소 = 재비식별 산출물 선택. 기본 선택 없음이며 고른 뒤에만 요청이 나간다. */}
      <DeidentResolveDialog
        rprtSn={resolving?.rprtSn ?? null}
        rawSn={resolving?.rawSn}
        submitting={isPending}
        onClose={() => setResolving(null)}
        onConfirm={(fileName) => {
          if (resolving) resolve({ rprtSn: resolving.rprtSn, fileName });
        }}
      />
    </section>
  );
}
