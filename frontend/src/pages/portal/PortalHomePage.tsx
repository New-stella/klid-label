// SCR-PORTAL-001 — 포털 메인 페이지 (V1.x mock 시각 정합).
// ADR-013: 포털은 데이터마트 영상 선택·간편 라벨링 전용. 오토라벨링·업로드(TUS) 미제공.
// Phase B: 데이터마트 노출(검수 완료=APPROVED) 영상 목록 + 선택 → 라벨링 진입 동선.
// - hero 섹션 (orange gradient)
// - KPI 2개 (영상 수 · 라벨링 완료 수)
// - 영상 목록 (카드/리스트, 반응형) → 카드 선택 시 /portal/label/{firstSrcSn} 이동
// - 라벨링 카드 "시작하기" → 첫 영상 진입 (영상 0건이면 aria-disabled)

import { ChevronRight, Play, Upload } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';

import { KpiCard } from '@/components/common/KpiCard';
import { KRDS_FOCUS } from '@/lib/focusRing';
import type { DatamartVideo } from '@/features/portal/api';
import { useDatamartVideos } from '@/features/portal/hooks/useDatamartVideos';

export function PortalHomePage() {
  const navigate = useNavigate();
  const { data, isLoading } = useDatamartVideos({ page: 0, size: 20 });

  const videos: DatamartVideo[] = data?.content ?? [];
  const totalVideos = data?.totalElements ?? 0;
  const hasVideos = videos.length > 0;

  // BE 가 프레임 0건 영상을 제외하므로 firstSrcSn 은 항상 존재. 첫 영상으로 진입.
  const firstEntry = videos[0]?.firstSrcSn;

  const goToLabel = (srcSn: number) => {
    navigate(`/portal/label/${srcSn}`);
  };

  const onStart = (e: React.MouseEvent) => {
    if (!hasVideos || firstEntry === undefined) {
      e.preventDefault();
      return;
    }
    goToLabel(firstEntry);
  };

  return (
    <div className="flex flex-col">
      {/* Hero 섹션 — KRDS Don't(그라데이션·brand 대면적 금지) 준수: 단색 neutral 배경 */}
      <section className="border-b border-gray-200 bg-gray-100 px-6 py-10">
        <div className="mx-auto max-w-4xl">
          <h1 className="mb-2 text-page-title text-gray-900">AI 학습데이터 작성 포털</h1>
          <p className="text-body text-gray-600">데이터마트 영상 선택, 간편 라벨링</p>
          <Link
            to="/portal/uploads"
            className={`mt-4 inline-flex items-center gap-1.5 rounded-lg border border-primary-300 bg-white px-3 py-2 text-sub font-medium text-primary-700 transition-colors hover:bg-primary-50 ${KRDS_FOCUS}`}
          >
            <Upload className="h-4 w-4" aria-hidden />
            내 업로드
          </Link>
        </div>
      </section>

      <div className="mx-auto flex w-full max-w-4xl flex-col gap-6 px-4 py-6">
        {/* KPI 2개 */}
        <section aria-label="요약" className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <KpiCard label="영상 수" value={totalVideos} unit="건" />
          <KpiCard label="라벨링 완료" value={0} unit="건" />
        </section>

        {/* 라벨링 카드 */}
        <section aria-label="이용 방법" className="flex flex-col gap-3">
          <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-500">이용 방법</h2>
          <article className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
            {/* 제목 옆 장식 아이콘은 두지 않는다 — 제목 텍스트를 되풀이할 뿐이다. */}
            <h2 className="text-section-title text-gray-800">라벨링</h2>
            <p className="text-sub text-gray-500">선택한 영상에 라벨을 추가하세요</p>
            <p className="text-body text-gray-600">
              라벨링 가능{' '}
              <span className="font-semibold text-primary-700">{totalVideos}건</span>
            </p>
            {/* WCAG 2.1.1 키보드 접근성: 영상이 없으면 진입 대상이 없어 비활성이지만, native
                `disabled` 는 Tab 순서에서 제거된다(R5 지적). `aria-disabled` 로 포커스 순서는 유지하되
                활성화만 차단한다. 영상이 있으면 첫 영상으로 진입. */}
            <button
              type="button"
              aria-disabled={!hasVideos || undefined}
              onClick={onStart}
              className={
                'mt-auto flex w-full items-center justify-center gap-1.5 rounded-lg bg-primary-600 px-3 py-2 text-sub font-medium text-white transition-colors hover:bg-primary-700 ' +
                (!hasVideos
                  ? 'cursor-not-allowed opacity-50 aria-disabled:hover:bg-primary-600'
                  : '')
              }
            >
              <Play className="h-3.5 w-3.5" aria-hidden />
              시작하기
              {/* 진행 방향 표식 — 장식이라 aria-hidden. 버튼 이름은 "시작하기" 텍스트가 정한다. */}
              <ChevronRight className="h-3.5 w-3.5" aria-hidden />
            </button>
          </article>
        </section>

        {/* 영상 목록 — 데이터마트 노출(검수 완료) 영상. 카드 선택 시 라벨링 진입. */}
        <section aria-label="데이터마트 영상" className="flex flex-col gap-3">
          <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-500">
            데이터마트 영상
          </h2>
          {isLoading ? (
            <p className="text-sub text-gray-500">영상 목록을 불러오는 중…</p>
          ) : !hasVideos ? (
            <p className="text-sub text-gray-500">선택 가능한 영상이 없습니다.</p>
          ) : (
            <ul className="grid grid-cols-1 gap-3 md:grid-cols-2">
              {videos.map((v) => (
                <li key={v.rawSn}>
                  <button
                    type="button"
                    data-testid="datamart-video-item"
                    onClick={() => goToLabel(v.firstSrcSn)}
                    className={`flex w-full flex-col gap-1 rounded-xl border border-gray-200 bg-white p-4 text-left shadow-sm transition-colors hover:border-primary-300 hover:bg-primary-50 ${KRDS_FOCUS}`}
                  >
                    <span className="text-section-title text-gray-800">{v.title}</span>
                    <span className="text-sub text-gray-600">
                      {v.eventName ?? '-'} · 프레임 {v.frameCount}건
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <p className="text-sub text-gray-500">
          ※ 선택한 영상은 본인만 조회/라벨링할 수 있으며, 결과 파일 제공은 포털 시스템에서 별도로
          안내됩니다.
        </p>
      </div>
    </div>
  );
}
