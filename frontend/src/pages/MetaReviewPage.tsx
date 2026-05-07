import { useEffect, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { z } from 'zod';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { useUiStore } from '@/stores/useUiStore';
import { EnvMetaForm } from '@/features/auto/components/EnvMetaForm';
import { EventMetaForm } from '@/features/auto/components/EventMetaForm';
import { StateChangeTimeline } from '@/features/auto/components/StateChangeTimeline';
import { VlmVerificationCard } from '@/features/auto/components/VlmVerificationCard';
import { useMeta } from '@/features/auto/hooks/useMeta';
import { useUpdateMeta } from '@/features/auto/hooks/useUpdateMeta';
import type { EnvMeta, EventMeta } from '@/features/auto/types';

/**
 * 사용자 입력 검증 (zod) — PUT /frames/{srcSn}/meta 호출 전.
 * 보안: 외부 메타 폼 입력값 길이/enum 검증.
 */
const envSchema = z.object({
  weather: z.enum(['CLEAR', 'RAIN', 'SNOW', 'CLOUDY', 'FOG']).nullable().optional(),
  timeOfDay: z.enum(['DAY', 'NIGHT', 'DAWN', 'DUSK']).nullable().optional(),
  illumination: z.enum(['LOW', 'MID', 'HIGH']).nullable().optional(),
});
const eventSchema = z.object({
  eventTypeCd: z.string().max(32).nullable().optional(),
  intensity: z.enum(['LOW', 'MID', 'HIGH']).nullable().optional(),
  description: z.string().max(500).nullable().optional(),
});

/**
 * SCR-AUTO-002 시계열 메타 검토·수정.
 *
 * V1.7: 좌(프레임+오버레이+타임라인+상태변화) + 우(VLM 검증 + 외부 메타 폼)
 * 두 영역 명시적 분리:
 * - VLM 객체 검증 결과 (저작도구 영역)
 * - 외부 자동 생성 환경/이벤트 메타 (외부 시스템 책임 — 검토·수정만)
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

  const [envMeta, setEnvMeta] = useState<EnvMeta>({});
  const [eventMeta, setEventMeta] = useState<EventMeta>({});

  useEffect(() => {
    if (data) {
      setEnvMeta(data.envMeta);
      setEventMeta(data.eventMeta);
    }
  }, [data]);

  const dirty = useMemo(() => {
    if (!data) return false;
    return (
      JSON.stringify(envMeta) !== JSON.stringify(data.envMeta) ||
      JSON.stringify(eventMeta) !== JSON.stringify(data.eventMeta)
    );
  }, [data, envMeta, eventMeta]);

  const handleSave = () => {
    const env = envSchema.safeParse(envMeta);
    const evt = eventSchema.safeParse(eventMeta);
    if (!env.success || !evt.success) {
      pushToast({ variant: 'error', message: '입력값을 확인해주세요.' });
      return;
    }
    updateMutation.mutate({ envMeta: env.data, eventMeta: evt.data });
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

      {data && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {/* 좌: 프레임 + 오버레이 + 상태 변화 (외부 자동 감지) */}
          <div
            data-testid="meta-left-panel"
            aria-label="프레임 미리보기와 상태 변화"
            className="flex flex-col gap-4"
          >
            <section
              aria-label="프레임 미리보기"
              className="rounded border border-border bg-white p-4"
            >
              <h3 className="mb-3 text-section-title text-primary">프레임</h3>
              <img
                src={data.imageUrl}
                alt={`프레임 ${data.frameNo}`}
                width={data.imageWidth}
                height={data.imageHeight}
                loading="lazy"
                data-testid="meta-frame-image"
                className="h-auto w-full rounded border border-border object-contain"
              />
              <p className="mt-2 text-sub text-neutral">
                #{data.frameNo} (srcSn: {data.srcSn})
              </p>
            </section>
            <StateChangeTimeline changes={data.stateChanges} />
          </div>

          {/* 우: VLM 객체 검증 (저작도구) + 외부 메타 폼 (외부 시스템) — 두 영역 명시 분리 */}
          <div
            data-testid="meta-right-panel"
            aria-label="VLM 검증과 외부 메타 폼"
            className="flex flex-col gap-4"
          >
            <VlmVerificationCard verifications={data.vlmVerifications} />
            <EnvMetaForm
              value={envMeta}
              onChange={setEnvMeta}
              disabled={updateMutation.isPending}
            />
            <EventMetaForm
              value={eventMeta}
              onChange={setEventMeta}
              disabled={updateMutation.isPending}
            />
          </div>
        </div>
      )}
    </section>
  );
}
