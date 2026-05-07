import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { StatusBadge } from '@/components/common/StatusBadge';
import { useVideoDetail } from '@/features/video/hooks/useVideoDetail';

/**
 * SCR-VIDEO-003 영상 상세 (라우트 페이지 형태).
 * 기본 정보 + 썸네일 + 프레임 미리보기 6장 + 라벨링 도구 진입 링크.
 *
 * 보안: id는 number로 검증. video.cctvName 등은 React 자동 escape.
 */
export function VideoDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const numericId = Number.parseInt(id ?? '', 10);
  const validId = Number.isFinite(numericId) && numericId > 0 ? numericId : null;
  const { data, isLoading, error } = useVideoDetail(validId);

  if (validId === null) {
    return <ErrorState title="잘못된 영상 ID" message="유효한 영상 ID가 필요합니다." />;
  }

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="영상 상세"
        breadcrumb={[
          { label: '영상', href: '/video/completed' },
          { label: `#${validId}` },
        ]}
        actions={
          data && (
            <Button
              variant="primary"
              size="md"
              onClick={() => navigate(`/label/${validId}`)}
            >
              라벨링 도구 열기
            </Button>
          )
        }
      />

      {isLoading && (
        <div className="rounded border border-border bg-white p-4">
          <Skeleton height={20} className="w-1/3" />
          <div className="mt-3 grid grid-cols-3 gap-2 sm:grid-cols-6">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} height={80} className="w-full" />
            ))}
          </div>
        </div>
      )}

      {error && <ErrorState title="영상 정보를 불러올 수 없습니다" />}

      {data && (
        <div className="rounded border border-border bg-white p-4">
          <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Field label="CCTV명">{data.cctvName}</Field>
            <Field label="VMS Clip ID">{data.vmsClipId}</Field>
            <Field label="이벤트">{data.eventName}</Field>
            <Field label="지자체">{data.localGov}</Field>
            <Field label="해상도">{data.resolution}</Field>
            <Field label="프레임 수">{data.frameCount.toLocaleString('ko-KR')}</Field>
            <Field label="용량">{data.fileSizeMb.toLocaleString('ko-KR')} MB</Field>
            <Field label="상태">
              <StatusBadge status={data.status} />
            </Field>
          </dl>

          <h2 className="mt-6 mb-2 text-section-title text-primary">프레임 미리보기</h2>
          <ul
            data-testid="frame-preview-list"
            className="grid grid-cols-3 gap-2 sm:grid-cols-6"
          >
            {data.framePreviews.slice(0, 6).map((fp) => (
              <li key={fp.frameNo} className="flex flex-col items-center gap-1">
                <img
                  src={fp.thumbnailUrl}
                  alt={`프레임 ${fp.frameNo}`}
                  loading="lazy"
                  width={120}
                  height={68}
                  className="h-auto w-full rounded border border-border object-cover"
                />
                <span className="text-sub text-neutral">#{fp.frameNo}</span>
              </li>
            ))}
          </ul>
        </div>
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
