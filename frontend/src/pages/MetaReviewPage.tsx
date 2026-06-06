import { useEffect, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { z } from 'zod';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { useUiStore } from '@/stores/useUiStore';
import { StateChangeTimeline } from '@/features/auto/components/StateChangeTimeline';
import { TimeseriesTextPanel } from '@/features/auto/components/TimeseriesTextPanel';
import { useMeta } from '@/features/auto/hooks/useMeta';
import { useUpdateMeta } from '@/features/auto/hooks/useUpdateMeta';

/**
 * 사용자 입력 검증 (zod) -- PUT /frames/{srcSn}/meta 호출 전.
 * 보안: vlmText 길이 제한.
 */
const vlmTextSchema = z.string().max(5000);

/**
 * SCR-AUTO-002 시계열 메타 검토/수정.
 *
 * V2.0: 좌(프레임+오버레이+타임라인+상태변화) + 우(VLM 시계열 자연어 패널)
 * - 외부 VLM 이 자동 생성한 시계열 텍스트를 검토/수정
 *
 * srcSn은 ?srcSn=N 쿼리로 전달 (videoId만으로는 어떤 프레임인지 모호하므로).
 */
export function MetaReviewPage() {
  const { videoId } = useParams<{ videoId: string }>();
  const [searchParams] = useSearchParams();
  const pushToast = useUiStore((s) => s.pushToast);

  const numericVideoId = Number.parseInt(videoId ?? '', 10);
  const validVideoId =
    Number.isFinite(numericVideoId) && numericVideoId > 0 ? numericVideoId : null;

  const srcSnRaw = searchParams.get('srcSn');
  const numericSrcSn = Number.parseInt(srcSnRaw ?? '', 10);
  const validSrcSn =
    Number.isFinite(numericSrcSn) && numericSrcSn > 0 ? numericSrcSn : null;

  const { data, isLoading, error } = useMeta(validSrcSn ?? undefined);
  const updateMutation = useUpdateMeta(validSrcSn ?? undefined, {
    onSuccess: () =>
      pushToast({ variant: 'success', message: '메타 정보가 저장되었습니다.' }),
    onError: () =>
      pushToast({ variant: 'error', message: '메타 저장에 실패했습니다.' }),
  });

  const [vlmText, setVlmText] = useState('');

  useEffect(() => {
    if (data) {
      setVlmText(data.vlmText);
    }
  }, [data]);

  const dirty = useMemo(() => {
    if (!data) return false;
    return vlmText !== data.vlmText;
  }, [data, vlmText]);

  const handleSave = () => {
    const parsed = vlmTextSchema.safeParse(vlmText);
    if (!parsed.success) {
      pushToast({ variant: 'error', message: '입력값을 확인해주세요.' });
      return;
    }
    // BE 는 기존 metaKey 의 값만 수정 가능 — 원본 items 의 키를 보존해 round-trip.
    // 단일 항목이면 편집 텍스트를 그 값으로 반영. (items 0건이면 저장 버튼이 비활성)
    const sourceItems = data?.items ?? [];
    if (sourceItems.length === 0) {
      return;
    }
    const items =
      sourceItems.length === 1
        ? [{ metaKey: sourceItems[0].metaKey, metaVal: parsed.data }]
        : sourceItems.map((it) => ({ metaKey: it.metaKey, metaVal: it.metaVal }));
    updateMutation.mutate({ items });
  };

  if (validVideoId === null) {
    return <ErrorState title="잘못된 영상 ID" message="유효한 영상 ID가 필요합니다." />;
  }
  if (validSrcSn === null) {
    return (
      <ErrorState
        title="프레임이 선택되지 않았습니다"
        message="프레임을 선택한 후 다시 시도해주세요."
      />
    );
  }

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="시계열 메타 검토·수정"
        breadcrumb={[
          { label: '영상', href: '/video/completed' },
          { label: `#${validVideoId}`, href: `/video/${validVideoId}` },
          { label: '오토라벨', href: `/auto/${validVideoId}` },
          { label: '메타 검토' },
        ]}
        actions={
          <Button
            variant="primary"
            size="md"
            onClick={handleSave}
            disabled={!dirty || updateMutation.isPending}
          >
            {updateMutation.isPending ? '저장 중...' : '저장'}
          </Button>
        }
      />

      {isLoading && (
        <div className="rounded border border-border bg-white p-4">
          <Skeleton height={20} className="w-1/3" />
          <Skeleton height={120} className="mt-3 w-full" />
        </div>
      )}

      {error && <ErrorState title="메타 정보를 불러올 수 없습니다" />}

      {/* R7-2: VLM 메타 0건 빈 상태 — 크래시(undefined.length) 대신 안내 화면. */}
      {!isLoading && !error && data && data.items.length === 0 && (
        <div
          data-testid="meta-empty-state"
          className="rounded border border-border bg-white p-8 text-center"
        >
          <h3 className="text-section-title text-primary">VLM 메타 없음</h3>
          <p className="mt-2 text-sub text-neutral">
            이 영상에는 검토할 VLM 시계열 메타가 아직 없습니다.
          </p>
        </div>
      )}

      {!isLoading && !error && data && data.items.length > 0 && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {/* 좌: 프레임 미리보기(있을 때만) + 상태 변화 */}
          <div
            data-testid="meta-left-panel"
            aria-label="프레임 미리보기와 상태 변화"
            className="flex flex-col gap-4"
          >
            {data.imageUrl && (
              <section
                aria-label="프레임 미리보기"
                className="rounded border border-border bg-white p-4"
              >
                <h3 className="mb-3 text-section-title text-primary">프레임</h3>
                <img
                  src={data.imageUrl}
                  alt={`프레임 ${data.frameNo ?? ''}`}
                  width={data.imageWidth}
                  height={data.imageHeight}
                  loading="lazy"
                  data-testid="meta-frame-image"
                  className="h-auto w-full rounded border border-border object-contain"
                />
                {data.frameNo != null && (
                  <p className="mt-2 text-sub text-neutral">
                    #{data.frameNo}
                    {data.srcSn != null ? ` (srcSn: ${data.srcSn})` : ''}
                  </p>
                )}
              </section>
            )}
            <StateChangeTimeline changes={data.stateChanges ?? []} />
          </div>

          {/* 우: VLM 시계열 자연어 패널 */}
          <div
            data-testid="meta-right-panel"
            aria-label="VLM 시계열 메타 패널"
            className="flex flex-col gap-4"
          >
            <TimeseriesTextPanel
              vlmText={vlmText}
              onChange={setVlmText}
              disabled={updateMutation.isPending}
            />
          </div>
        </div>
      )}
    </section>
  );
}
