import { useParams } from 'react-router-dom';

import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { useBackgroundGenerateJob } from '@/features/generate/hooks/useGenerate';
import { type BackgroundGenType } from '@/features/generate/types';

const GEN_TYPE_LABEL: Record<BackgroundGenType, string> = {
  WILDFIRE: '🔥 산불',
  FLOOD: '🌊 침수',
};

/**
 * SCR-GEN-002 배경영상 요청 결과 (`/generate/result/:jobId`).
 *
 * UI/UX §4-13:
 * - 단일 카드(요청 메타) + "관제서버 전달 안내"
 *
 * 보안: jobId는 number 타입 검증. 표시 데이터는 BE 응답값만.
 */
export function GenerateResultPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const numericId = Number.parseInt(jobId ?? '', 10);
  const validId = Number.isFinite(numericId) && numericId > 0 ? numericId : null;

  const { data, isLoading, error } = useBackgroundGenerateJob(
    validId ?? undefined,
  );

  if (validId === null) {
    return <ErrorState title="잘못된 잡 ID" message="유효한 잡 ID가 필요합니다." />;
  }

  return (
    <section className="flex flex-col gap-4" data-testid="generate-result-page">
      <PageHeader
        title="배경영상 요청 결과"
        breadcrumb={[
          { label: '영상 목록', href: '/video/completed' },
          { label: `잡 #${validId}` },
        ]}
      />

      {error && <ErrorState title="요청 정보를 불러올 수 없습니다" />}
      {isLoading && (
        <div className="rounded border border-border bg-white p-4">
          <Skeleton height={120} />
        </div>
      )}

      {data && (
        <article
          data-testid="generate-result-card"
          className="flex flex-col gap-3 rounded border border-border bg-white p-4 shadow-sm"
        >
          <h2 className="text-section-title text-primary">요청 메타</h2>
          <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Field label="잡 ID">#{data.jobId}</Field>
            <Field label="CCTV명">{data.cctvName}</Field>
            <Field label="기준 프레임">
              #{data.frameNo ?? data.srcSn}
            </Field>
            <Field label="유형">{GEN_TYPE_LABEL[data.genType]}</Field>
            <Field label="요청 일시">
              {new Date(data.requestedAt).toLocaleString('ko-KR')}
            </Field>
          </dl>
          <p
            data-testid="generate-result-notice"
            className="rounded border border-accent bg-accent/5 p-3 text-body text-primary"
          >
            요청은 외부 생성형 AI 시스템으로 전달되었습니다. 결과 영상은
            관제서버를 통해 전달되며, 관련 알림은 별도로 안내됩니다.
          </p>
        </article>
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
