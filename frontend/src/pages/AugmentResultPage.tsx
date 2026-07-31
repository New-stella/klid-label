import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Pagination } from '@/components/common/Pagination';
import { Skeleton } from '@/components/common/Skeleton';
import { Spinner } from '@/components/common/Spinner';
import { StatusBadge } from '@/components/common/StatusBadge';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { augTypeLabel } from '@/features/augment/augTypeLabel';
import { AugmentVideoSection } from '@/features/augment/components/AugmentVideoSection';
import { FRAME_PAGE_SIZE } from '@/features/augment/components/AugmentResultPanel';
import { useAugmentResult } from '@/features/augment/hooks/useAugmentResult';
import { normalizeDecision, totalPairsOf } from '@/features/augment/resultView';
import {
  AugmentDecision,
  type AugmentResult as AugResult,
  type AugmentResultType,
} from '@/features/augment/types';

const VISIBLE_INITIAL = 2;

/** 결과 항목 페이지 크기 — BE 기본값(20)과 동일. */
const ITEM_PAGE_SIZE = 20;

/**
 * SCR-AUG-002 증강 결과 (`/augment/result/:jobId`).
 *
 * <h3>페이징 축이 둘이다</h3>
 * - 프레임 쌍 축(`page`/`size`) : 해상도 파생의 비교 이미지
 * - 결과 항목 축(`itemPage`/`itemSize`) : 증강 결과 항목
 *
 * 두 축은 **서로 독립된 컨트롤**이다. 항목 페이저를 프레임 쌍 총량으로 렌더하면 프레임 쌍이 0건인
 * 순수 외부 위탁 잡에서 항목 2페이지로 갈 수단이 사라진다(= 21번째 항목부터 도달 불가).
 *
 * <h3>진행률은 서버 실값</h3>
 * 잡 단위 가짜 진행률(0/50/100)을 그리지 않는다. 진행률은 **항목별**로 BE
 * `GET /v1/augments/{id}/progress` 가 주는 값이며 항목 패널에서 표시한다.
 *
 * 보안: jobId는 number 타입 검증. URL 이미지는 BE 응답값만 사용.
 */
export function AugmentResultPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const numericId = Number.parseInt(jobId ?? '', 10);
  const validId = Number.isFinite(numericId) && numericId > 0 ? numericId : null;

  // 프레임 쌍 페이지(0-based) — BE 가 results[].framePairs 를 이 단위로 잘라 내려준다.
  const [framePage, setFramePage] = useState(0);
  // 결과 항목 페이지(0-based) — BE 가 results 자체를 이 단위로 잘라 내려준다.
  const [itemPage, setItemPage] = useState(0);
  const { data, isLoading, isFetching, error, refetch } = useAugmentResult(
    validId ?? undefined,
    {
      page: framePage,
      size: FRAME_PAGE_SIZE,
      itemPage,
      itemSize: ITEM_PAGE_SIZE,
    },
  );
  const [showAll, setShowAll] = useState(false);

  // 영상별 그룹핑
  const groupedByVideo = useMemo(() => {
    const map = new Map<number, { cctvName: string; results: AugResult[] }>();
    if (!data) return map;
    for (const r of data.results) {
      const cur = map.get(r.videoId);
      if (cur) {
        cur.results.push(r);
      } else {
        map.set(r.videoId, { cctvName: r.cctvName, results: [r] });
      }
    }
    return map;
  }, [data]);

  /**
   * 항목 탭 번호(#n)의 근거 — **잡 전체에서 몇 번째 항목인가** (id → 순번).
   *
   * 항목 축은 페이징되므로 페이지 안의 순번을 그대로 쓰면 2페이지의 첫 탭도 `#1` 이 되어,
   * "왼쪽일수록 최근 요청" 안내와 겹치는 순간 **거짓**이 된다(항목 21건 이상인 잡에서 재현).
   * 오프셋은 추정하지 않고 **응답이 돌려준 `itemPage`/`itemSize`** 로만 계산한다. 두 값이 없는
   * 응답(항목 축 페이징을 하지 않는 구 서버 — 한 응답에 전 항목이 실린다)은 `null` 을 넘겨,
   * 화면이 번호를 "보이는 순서대로 매긴 번호" 라고 밝히게 한다(없는 근거를 지어내지 않는다).
   */
  const itemOrdinals = useMemo(() => {
    if (!data) return null;
    const { itemPage: respPage, itemSize: respSize } = data;
    if (typeof respPage !== 'number' || typeof respSize !== 'number') return null;
    if (respPage < 0 || respSize <= 0) return null;
    const base = respPage * respSize;
    const map = new Map<number, number>();
    data.results.forEach((r, i) => map.set(r.id, base + i + 1));
    return map as ReadonlyMap<number, number>;
  }, [data]);

  // 잡 레벨 요약 — API 응답에서 파생.
  const summary = useMemo(() => (data ? summarize(data) : null), [data]);

  const rateToneClass =
    summary && summary.augmentedRate >= 95
      ? 'text-success'
      : summary && summary.augmentedRate >= 85
        ? 'text-warning'
        : 'text-danger';

  /**
   * 페이지 부분합에 붙이는 범위 표기 — 값이 잡 전체가 아님을 **라벨에서** 밝힌다.
   * 한 페이지가 전체 항목을 담고 있으면(단일 페이지 잡) 부분합이 곧 전체라 표기하지 않는다.
   */
  const scopeNote = summary && !summary.coversAllItems ? ' (이 페이지)' : '';

  // 항목 축 페이저 — **프레임 쌍 총량과 무관**하게 항목 축 총량으로만 판단한다.
  const itemTotalElements = data?.totalElements ?? data?.results.length ?? 0;
  const itemTotalPages = data?.totalPages ?? (data ? 1 : 0);
  const showItemPager = itemTotalPages > 1 || itemPage > 0;

  const handleItemPageChange = (next: number) => {
    setItemPage(next);
    // 보고 있는 항목이 통째로 바뀌므로 프레임 축 페이지를 유지할 근거가 없다(빈 그리드 갇힘 방지).
    setFramePage(0);
  };

  if (validId === null) {
    return <ErrorState title="잘못된 잡 ID" message="유효한 잡 ID가 필요합니다." />;
  }

  return (
    <section className="flex flex-col gap-4" data-testid="augment-result-page">
      <PageHeader
        title="증강 결과 확인"
        breadcrumb={[
          { label: '데이터 증강', href: '/augment' },
          { label: `잡 #${validId}` },
        ]}
        actions={
          summary && (
            <span data-testid="augment-result-status-badge">
              <StatusBadge
                status={summary.displayStatus}
                label={summary.displayStatus === 'CANCELED' ? '취소됨' : undefined}
              />
            </span>
          )
        }
      />

      {error && <ErrorState title="증강 결과를 불러올 수 없습니다" />}
      {isLoading && (
        <div className="rounded border border-border bg-white p-4">
          <Skeleton height={120} />
        </div>
      )}

      {data && summary && (
        <>
          {/* 작업 요약 카드 */}
          <div
            className="rounded border border-border bg-white p-4"
            data-testid="augment-result-summary"
          >
            <h2 className="mb-3 text-section-title text-primary">작업 요약</h2>
            <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-body md:grid-cols-5">
              <div>
                <dt className="text-sub text-gray-500">작업 ID</dt>
                <dd className="font-mono text-sub text-gray-800">#{validId}</dd>
              </div>
              <div>
                <dt className="text-sub text-gray-500">증강 유형{scopeNote}</dt>
                <dd className="font-medium text-gray-800">
                  {summary.types.map((t) => augTypeLabel(t)).join(' · ') || '-'}
                </dd>
              </div>
              <div>
                <dt className="text-sub text-gray-500">대상 영상{scopeNote}</dt>
                <dd className="font-medium text-gray-800">{summary.videoCount}건</dd>
              </div>
              <div>
                <dt className="text-sub text-gray-500">결과 항목</dt>
                <dd className="font-medium text-gray-800">
                  {itemTotalElements.toLocaleString('ko-KR')}건
                </dd>
              </div>
              {/* 비교 프레임 쌍 — "총 처리 이미지" 가 아니다. 외부 위탁 항목은 프레임별 산출물 연동
                  전이라 쌍이 0건이며, 그것을 "처리 0장" 으로 말하면 사실과 다르다. */}
              <div>
                <dt className="text-sub text-gray-500">비교 프레임 쌍{scopeNote}</dt>
                <dd
                  className="font-medium text-gray-800"
                  data-testid="augment-result-page-pairs"
                >
                  {summary.pagePairs.toLocaleString('ko-KR')}쌍
                </dd>
              </div>
            </dl>
          </div>

          {/* PROCESSING 배너 — 진행률(실값)은 항목별 패널에서 표시한다.

              자동 갱신의 실체는 **항목별 진행 상태가 종결로 관측될 때의 결과 재조회**
              (`useResultRefreshOnProgressTerminal`)뿐이다. 진행 상태를 조회할 수 없는 환경
              (외부 미연동)이나 항목이 아직 없는 잡에서는 그 신호가 오지 않으므로, 구 문구처럼
              "잠시 후 자동으로 결과가 표시됩니다" 라고 단언하지 않고 수동 갱신 수단을 함께 준다
              (실패 배너에만 복구 버튼이 있던 비대칭도 함께 없앤다). */}
          {summary.status === 'PROCESSING' && (
            <div
              className="flex flex-wrap items-center justify-between gap-3 rounded border border-info/30 bg-info/10 p-4"
              role="status"
              data-testid="augment-result-processing"
            >
              <div className="flex items-center gap-3">
                <Spinner size="sm" />
                <div>
                  <p className="text-body font-semibold text-info">
                    증강 처리 중입니다...
                  </p>
                  <p className="text-sub text-info">
                    처리 상태가 확인되면 결과가 자동으로 갱신됩니다. 갱신되지 않으면
                    새로고침을 눌러 확인하세요.
                  </p>
                </div>
              </div>
              <Button
                variant="secondary"
                size="sm"
                data-testid="augment-result-refresh"
                onClick={() => {
                  void refetch();
                }}
                disabled={isFetching}
              >
                새로고침
              </Button>
            </div>
          )}

          {/* FAILED 배너 */}
          {summary.status === 'FAILED' && (
            <div
              className="flex flex-wrap items-center justify-between gap-3 rounded border border-danger/30 bg-danger/10 p-4"
              role="alert"
              data-testid="augment-result-failed"
            >
              <div>
                <p className="text-body font-semibold text-danger">증강 처리 실패</p>
                <p className="text-sub text-danger">
                  외부 증강 시스템 응답 오류 또는 리소스 부족으로 처리가 중단되었습니다.
                </p>
              </div>
              <Button variant="secondary" size="sm" onClick={() => location.reload()}>
                재시도
              </Button>
            </div>
          )}

          {/* COMPLETED — 결과 본문이 아직 없으면(외부 SFR-07 연동 전) 완료 안내만 표시 */}
          {summary.status === 'COMPLETED' && !summary.hasResults && (
            <div
              className="rounded border border-success/30 bg-success/10 p-4"
              role="status"
              data-testid="augment-result-completed-empty"
            >
              <p className="text-body font-semibold text-success">증강 처리 완료</p>
              <p className="text-sub text-success">
                파생 영상이 생성되어 작업 목록에서 라벨링·검수를 진행할 수 있습니다. 프레임별
                비교 결과는 외부 연동 이후 표시됩니다.
              </p>
            </div>
          )}

          {/* 증강 이미지 생성률 — 프레임 쌍이 있는 항목이 있을 때만 계산·표시한다.
              외부 위탁 항목은 프레임 쌍이 비어 있어, 이를 분모에 넣으면 "0% — 주의" 오경보가 뜬다.

              ⚠ 이 값은 **지금 화면에 로드된 프레임 쌍**(현재 프레임 페이지) 중 증강 이미지가 있는
              비율이다. 라벨을 검사한 값이 아니어서 구 이름("라벨 무결성")은 사실과 달랐고, 프레임
              페이지를 넘길 때마다 값이 바뀌는데 잡 전체 지표처럼 보였다. 이름과 근거를 함께 밝힌다. */}
          {summary.status === 'COMPLETED' && summary.hasFramePairs && (
            <div
              className="flex flex-col items-center gap-2 rounded border border-border bg-white p-6"
              data-testid="augment-result-integrity"
            >
              <p className="text-body font-semibold text-gray-600">증강 이미지 생성률</p>
              <span className={`text-5xl font-black tabular-nums ${rateToneClass}`}>
                {summary.augmentedRate}%
              </span>
              <p className="text-sub text-gray-400">
                현재 화면에 표시된 프레임 {summary.loadedPairs.toLocaleString('ko-KR')}쌍
                기준입니다.
              </p>
            </div>
          )}

          {Array.from(groupedByVideo.entries())
            .slice(0, showAll ? undefined : VISIBLE_INITIAL)
            .map(([videoId, group]) => (
              <AugmentVideoSection
                key={videoId}
                videoId={videoId}
                cctvName={group.cctvName}
                results={group.results}
                itemOrdinals={itemOrdinals}
                framePage={framePage}
                onFramePageChange={setFramePage}
              />
            ))}

          {groupedByVideo.size > VISIBLE_INITIAL && !showAll && (
            <button
              type="button"
              onClick={() => setShowAll(true)}
              data-testid="augment-result-show-more"
              className={`self-start text-body text-accent underline ${KRDS_FOCUS}`}
            >
              더 보기 ({groupedByVideo.size - VISIBLE_INITIAL}건)
            </button>
          )}

          {showItemPager && (
            <div data-testid="augment-item-pager">
              <Pagination
                page={itemPage}
                size={ITEM_PAGE_SIZE}
                totalElements={itemTotalElements}
                onPageChange={handleItemPageChange}
              />
            </div>
          )}
        </>
      )}
    </section>
  );
}

interface ResultSummary {
  /** ⚠ **현재 항목 페이지에 실린 항목들**의 유형 집합 (전체 잡 값이 아니다) */
  types: AugmentResultType[];
  /** ⚠ **현재 항목 페이지** 기준 영상 수 */
  videoCount: number;
  /** ⚠ **현재 항목 페이지** 항목들의 프레임 쌍 총량 — "처리한 이미지 수" 가 아니다 */
  pagePairs: number;
  /** 현재 항목 페이지가 잡 전체 항목을 담고 있는가 — 위 값들을 잡 전체로 말해도 되는 조건 */
  coversAllItems: boolean;
  /** 결과 본문 유무 — 외부 연동 대기 안내(빈 결과) 판정용 */
  hasResults: boolean;
  /** **현재 프레임 페이지**에 실제로 로드된 쌍 수 — 생성률 계산의 분모(근거 명시용) */
  loadedPairs: number;
  /** 프레임 쌍이 실린 항목이 있는가 — 생성률 계산 대상 유무 */
  hasFramePairs: boolean;
  /** ⚠ **현재 프레임 페이지에 로드된 쌍** 기준 증강 이미지 생성률(%) */
  augmentedRate: number;
  status: 'PROCESSING' | 'COMPLETED' | 'FAILED';
  /** 화면 표시용 상태 — 취소 종결을 "완료" 로 오표시하지 않도록 보정한다 */
  displayStatus: 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELED';
}

/**
 * 결과 요약 산출 — **집계 축이 무엇인지 값마다 다르다** (Critical).
 *
 * `data.results` 는 **현재 항목 페이지**의 슬라이스이고, 각 항목의 `framePairs` 는 다시 **현재 프레임
 * 페이지**의 슬라이스다. 따라서 여기서 나오는 합계는 어느 것도 "잡 전체" 가 아니다. 잡 전체인 값은
 * BE 가 총량으로 내려준 `totalElements`(결과 항목 수)뿐이다.
 *
 * 구 구현은 이 페이지 부분합을 "총 처리 이미지"·"라벨 무결성" 이라는 **잡 전체 이름**으로 렌더해,
 * 같은 잡인데 페이지를 넘길 때마다 값이 달라지고(항목 22건 잡의 1페이지 "0장"), 외부 위탁만 있는
 * 완료 잡은 실제로 처리를 마쳐도 항상 "0장" 이었다. BE 가 잡 전체 집계를 주지 않으므로 **추정하지
 * 않고**(외삽 금지) 화면이 라벨에 범위를 명시한다.
 *
 * - 상태는 BE 집계값을 그대로 쓴다(results.length 로 파생하지 않는다).
 * - 다만 **취소 종결**은 BE 잡 집계(`AugmentJobStatus` 4값)가 COMPLETED 로 내려주고 BE 는 그 enum 을
 *   확장하지 않기로 확정했으므로, 이 페이지가 실린 항목 전체를 근거로 표시 축에서 보정한다.
 *   보정은 **한 페이지가 전체 항목을 담고 있을 때만** 한다(부분 페이지로 전체를 단정하지 않는다).
 * - 생성률은 프레임 쌍을 가진 항목만으로 계산한다(외부 위탁 항목은 쌍이 비어 있다).
 */
function summarize(data: {
  results: AugResult[];
  status?: 'PROCESSING' | 'COMPLETED' | 'FAILED';
  totalElements?: number;
}): ResultSummary {
  const uniqueTypes = new Set<AugmentResultType>();
  const uniqueVideos = new Set<number>();
  let loadedPairs = 0;
  let augmentedPairs = 0;
  let totalPairs = 0;

  for (const r of data.results) {
    uniqueTypes.add(r.type);
    uniqueVideos.add(r.videoId);
    totalPairs += totalPairsOf(r);
    for (const p of r.framePairs) {
      loadedPairs += 1;
      if (p.augmentedUrl) augmentedPairs += 1;
    }
  }

  const status = data.status ?? 'PROCESSING';
  const hasResults = data.results.length > 0;
  const coversAllItems = (data.totalElements ?? data.results.length) <= data.results.length;
  const allCanceled =
    hasResults &&
    coversAllItems &&
    data.results.every((r) => normalizeDecision(r.decision) === AugmentDecision.CANCELED);

  return {
    types: Array.from(uniqueTypes),
    videoCount: uniqueVideos.size,
    pagePairs: totalPairs,
    coversAllItems,
    hasResults,
    loadedPairs,
    hasFramePairs: loadedPairs > 0,
    augmentedRate:
      loadedPairs > 0 ? Math.round((augmentedPairs / loadedPairs) * 100) : 0,
    status,
    displayStatus: allCanceled ? 'CANCELED' : status,
  };
}
