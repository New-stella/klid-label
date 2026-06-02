// SCR-PORTAL-001 — 포털 메인 페이지 (V1.x mock 시각 정합).
// ADR-013: 포털은 데이터마트 영상 선택·간편 라벨링 전용. 오토라벨링·업로드(TUS) 미제공.
// - hero 섹션 (orange gradient)
// - KPI 2개 (영상 수 · 라벨링 완료 수)
// - 라벨링 카드 (데이터마트 영상 선택 → 간편 라벨링)

import { Tag, Play } from 'lucide-react';

import { KpiCard } from '@/components/common/KpiCard';

export function PortalHomePage() {
  // GET /portal/uploads 엔드포인트는 BE 에서 삭제됨(ADR-013) — 호출하지 않는다.
  // 데이터마트 영상 목록 전용 엔드포인트는 아직 없으므로 홈은 최소 상태로 둔다
  // (KPI 0 · 시작하기 비활성). 목록 조회는 별도 후속에서 도입.
  const totalVideos = 0;
  const labeledCount = 0;
  const pendingCount = 0;

  return (
    <div className="flex flex-col">
      {/* Hero 섹션 — orange gradient */}
      <section className="bg-gradient-to-br from-orange-500 to-orange-600 px-6 py-10 text-white">
        <div className="mx-auto max-w-4xl">
          <h1 className="mb-2 text-page-title">AI 학습데이터 작성 포털</h1>
          <p className="text-body text-orange-50">데이터마트 영상 선택, 간편 라벨링</p>
        </div>
      </section>

      <div className="mx-auto flex w-full max-w-4xl flex-col gap-6 px-4 py-6">
        {/* KPI 2개 */}
        <section aria-label="요약" className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <KpiCard label="영상 수" value={totalVideos} unit="건" />
          <KpiCard label="라벨링 완료" value={labeledCount} unit="건" />
        </section>

        {/* 라벨링 카드 */}
        <section aria-label="이용 방법" className="flex flex-col gap-3">
          <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-500">
            이용 방법
          </h2>
          <article className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
            <div className="flex items-center gap-2">
              <Tag className="h-4 w-4 text-orange-500" aria-hidden />
              <h2 className="text-section-title text-gray-800">라벨링</h2>
            </div>
            <p className="text-sub text-gray-500">선택한 영상에 라벨을 추가하세요</p>
            <p className="text-body text-gray-600">
              라벨링 필요{' '}
              <span className="font-semibold text-orange-600">{pendingCount}건</span>
            </p>
            <button
              type="button"
              disabled
              className="mt-auto flex w-full items-center justify-center gap-1.5 rounded-lg bg-orange-500 px-3 py-2 text-sub font-medium text-white transition-colors hover:bg-orange-600 disabled:cursor-not-allowed disabled:opacity-50"
            >
              <Play className="h-3.5 w-3.5" aria-hidden />
              시작하기 ▶
            </button>
          </article>
        </section>

        <p className="text-sub text-gray-500">
          ※ 선택한 영상은 본인만 조회/라벨링할 수 있으며, 결과 파일 제공은 포털 시스템에서
          별도로 안내됩니다.
        </p>
      </div>
    </div>
  );
}
