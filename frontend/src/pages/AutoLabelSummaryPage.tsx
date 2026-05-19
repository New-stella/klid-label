import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { ConfidenceDistribution } from '@/features/auto/components/ConfidenceDistribution';
import { useAutoSummary } from '@/features/auto/hooks/useAutoSummary';

/**
 * SCR-AUTO-001 오토라벨링 결과 요약.
 *
 * 처리 정보 / 신뢰도 분포 (90+/70-90/70-) / 라벨별 분포 / 낮은 신뢰도 프레임 보기.
 * 보안: videoId는 number로 검증. 이미지 URL은 BE 응답값 그대로 사용 (사용자 입력 X).
 */
export function AutoLabelSummaryPage() {
  const { videoId } = useParams<{ videoId: string }>();
  const navigate = useNavigate();
  const numericId = Number.parseInt(videoId ?? '', 10);
  const validId = Number.isFinite(numericId) && numericId > 0 ? numericId : null;
  const { data, isLoading, error } = useAutoSummary(validId ?? undefined);
  const [lowOnly, setLowOnly] = useState(false);

  const visibleLowFrames = useMemo(() => {
    const frames = data?.lowConfidenceFrames ?? [];
    return lowOnly ? frames.filter((f) => f.confidence < 0.7) : frames;
  }, [data, lowOnly]);

  // BE placeholder 응답 감지: buckets/classDistribution이 없으면 미구현 단계
  // (BE V1.7 placeholder 는 status='PENDING' + buckets/classDistribution 부재)
  const isPlaceholder = data && (!data.buckets || !data.classDistribution);

  if (validId === null) {
    return <ErrorState title="잘못된 영상 ID" message="유효한 영상 ID가 필요합니다." />;
  }

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="오토라벨링 결과 요약"
        breadcrumb={[
          { label: '영상', href: '/video/completed' },
          { label: `#${validId}`, href: `/video/${validId}` },
          { label: '오토라벨' },
        ]}
        actions={
          data && !isPlaceholder && (
            <Button
              variant="primary"
              size="md"
              onClick={() => navigate(`/auto/${validId}/meta`)}
            >
              시계열 메타 검토
            </Button>
          )
        }
      />

      {isLoading && (
        <div className="rounded border border-border bg-white p-4">
          <Skeleton height={20} className="w-1/3" />
          <Skeleton height={80} className="mt-3 w-full" />
        </div>
      )}

      {error && <ErrorState title="요약 정보를 불러올 수 없습니다" />}

      {isPlaceholder && (
        <div className="rounded border border-border bg-white p-6 text-center">
          <p className="text-section-title text-neutral">배치 처리 대기 중</p>
          <p className="mt-1 text-body text-neutral">
            YOLO/SAM2 오토라벨링 완료 후 요약 정보가 표시됩니다.
          </p>
        </div>
      )}

      {data && !isPlaceholder && (
        <>
          <div
            data-testid="auto-summary-info"
            className="grid grid-cols-2 gap-3 rounded border border-border bg-white p-4 sm:grid-cols-5"
          >
            <Field label="총 프레임">{(data.totalFrames ?? 0).toLocaleString('ko-KR')}</Field>
            <Field label="총 라벨">{(data.totalLabels ?? 0).toLocaleString('ko-KR')}</Field>
            <Field label="평균 신뢰도">
              {((data.averageConfidence ?? 0) * 100).toFixed(1)}%
            </Field>
            <Field label="VLM 동의">
              {(data.vlmVerifiedCount ?? 0).toLocaleString('ko-KR')}
            </Field>
            <Field label="VLM 거부">
              {(data.vlmRejectedCount ?? 0).toLocaleString('ko-KR')}
            </Field>
          </div>

          <ConfidenceDistribution buckets={data.buckets ?? []} />

          <section
            data-testid="class-distribution"
            aria-label="라벨별 분포"
            className="rounded border border-border bg-white p-4"
          >
            <h3 className="mb-3 text-section-title text-primary">라벨별 분포</h3>
            {(() => {
              const top10 = (data.classDistribution ?? []).slice(0, 10);
              const maxCount = Math.max(...top10.map((c) => c.count), 1);
              return (
                <ul className="flex flex-col gap-2">
                  {top10.map((c) => (
                    <li key={c.classId} className="flex items-center gap-2">
                      <span className="w-20 shrink-0 truncate text-xs text-gray-600">
                        {c.className}
                      </span>
                      <div className="flex-1 overflow-hidden rounded-full bg-gray-100 h-3">
                        <div
                          className="h-full rounded-full bg-blue-500 transition-all"
                          style={{ width: `${Math.round((c.count / maxCount) * 100)}%` }}
                        />
                      </div>
                      <span className="w-8 text-right text-xs tabular-nums text-gray-500">
                        {c.count}
                      </span>
                    </li>
                  ))}
                </ul>
              );
            })()}
          </section>

          <section
            data-testid="low-confidence-section"
            aria-label="낮은 신뢰도 프레임"
            className="rounded border border-border bg-white p-4"
          >
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-section-title text-primary">신뢰도 낮은 프레임</h3>
              <Button
                size="sm"
                variant={lowOnly ? 'primary' : 'outline'}
                onClick={() => setLowOnly((v) => !v)}
              >
                {lowOnly ? '전체 보기' : '낮은 신뢰도 프레임만 보기 (70% 미만)'}
              </Button>
            </div>
            {visibleLowFrames.length === 0 ? (
              <p className="text-sub text-neutral">표시할 프레임이 없습니다.</p>
            ) : (
              <ul
                data-testid="low-confidence-list"
                className="grid grid-cols-3 gap-2 sm:grid-cols-6"
              >
                {visibleLowFrames.map((f) => (
                  <li
                    key={f.srcSn}
                    data-testid={`low-frame-${f.srcSn}`}
                    className="flex flex-col items-center gap-1"
                  >
                    <img
                      src={f.thumbnailUrl}
                      alt={`프레임 ${f.frameNo} 신뢰도 ${(f.confidence * 100).toFixed(0)}%`}
                      loading="lazy"
                      width={120}
                      height={68}
                      className="h-auto w-full rounded border border-border object-cover"
                    />
                    <span className="text-sub text-neutral">
                      #{f.frameNo} · {(f.confidence * 100).toFixed(0)}%
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-sub text-neutral">{label}</dt>
      <dd className="text-body text-primary">{children}</dd>
    </div>
  );
}
