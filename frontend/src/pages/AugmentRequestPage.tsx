import { useMemo, useState } from 'react';

import { Button } from '@/components/common/Button';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Input } from '@/components/common/Input';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { AugmentTypeCheckbox } from '@/features/augment/components/AugmentTypeCheckbox';
import { JobCard } from '@/features/augment/components/JobCard';
import { useRequestAugment } from '@/features/augment/hooks/useAugmentDecision';
import { useAugmentJobs } from '@/features/augment/hooks/useAugmentJobs';
import { AugmentType, type AugmentType as AT } from '@/features/augment/types';
import { useUiStore } from '@/stores/useUiStore';

const ALL_TYPES: AT[] = [
  AugmentType.WINTER,
  AugmentType.NIGHT,
  AugmentType.RAIN,
  AugmentType.RESOLUTION,
];

/**
 * SCR-AUG-001 데이터 증강 요청 (`/augment`).
 *
 * UI/UX §4-12:
 * - 대상 영상 ID(콤마) 입력 + 증강 유형 4종 체크박스(겨울/야간/비/해상도)
 * - 최근 요청 이력 잡 카드 6건 그리드 (5초 폴링)
 *
 * 보안:
 * - REVIEWER 역할 검증 (라우터 + BE).
 * - videoIds는 number[]로 변환 후 전달 — 비숫자 입력 차단.
 * - types는 enum allowlist로 강제 (체크박스).
 */
export function AugmentRequestPage() {
  const [videoIdInput, setVideoIdInput] = useState('');
  const [selectedTypes, setSelectedTypes] = useState<Set<AT>>(new Set());
  const pushToast = useUiStore((s) => s.pushToast);

  const { data, isLoading, error } = useAugmentJobs({ page: 0, size: 6 });
  const { mutate, isPending } = useRequestAugment({
    onSuccess: () => {
      pushToast({ variant: 'success', message: '증강 요청 등록됨' });
      setVideoIdInput('');
      setSelectedTypes(new Set());
    },
    onError: () => {
      pushToast({ variant: 'error', message: '증강 요청 실패' });
    },
  });

  const parsedVideoIds = useMemo(() => {
    return videoIdInput
      .split(',')
      .map((s) => Number.parseInt(s.trim(), 10))
      .filter((n) => Number.isFinite(n) && n > 0);
  }, [videoIdInput]);

  const canSubmit =
    parsedVideoIds.length > 0 && selectedTypes.size > 0 && !isPending;

  const toggleType = (t: AT) => {
    setSelectedTypes((prev) => {
      const next = new Set(prev);
      if (next.has(t)) next.delete(t);
      else next.add(t);
      return next;
    });
  };

  const handleSubmit = () => {
    if (!canSubmit) return;
    mutate({
      videoIds: parsedVideoIds,
      types: Array.from(selectedTypes),
    });
  };

  return (
    <section className="flex flex-col gap-4" data-testid="augment-request-page">
      <PageHeader
        title="데이터 증강 요청"
        description="대상 영상에 4종(겨울/야간/비/해상도) 증강을 요청합니다."
      />

      <section
        aria-label="증강 요청 폼"
        className="flex flex-col gap-3 rounded border border-border bg-white p-4"
      >
        <Input
          label="대상 영상 ID (콤마 구분)"
          placeholder="예: 1, 2, 3"
          value={videoIdInput}
          onChange={(e) => setVideoIdInput(e.target.value)}
          hint={
            parsedVideoIds.length > 0
              ? `${parsedVideoIds.length}건 선택됨`
              : undefined
          }
        />

        <div>
          <span className="mb-1 block text-body font-medium text-primary">
            증강 유형 (복수 선택)
          </span>
          <div className="flex flex-wrap gap-4" data-testid="augment-type-list">
            {ALL_TYPES.map((t) => (
              <AugmentTypeCheckbox
                key={t}
                type={t}
                checked={selectedTypes.has(t)}
                onChange={() => toggleType(t)}
              />
            ))}
          </div>
        </div>

        <div className="flex justify-end">
          <Button
            data-testid="augment-submit"
            variant="primary"
            disabled={!canSubmit}
            loading={isPending}
            onClick={handleSubmit}
          >
            증강 요청
          </Button>
        </div>
      </section>

      <section
        aria-label="최근 요청 이력"
        className="flex flex-col gap-3 rounded border border-border bg-white p-4"
      >
        <h2 className="text-section-title text-primary">
          최근 요청 이력 (5초 자동 갱신)
        </h2>
        {error && <ErrorState title="이력을 불러올 수 없습니다" />}
        {isLoading && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} height={92} />
            ))}
          </div>
        )}
        {data &&
          (data.content.length === 0 ? (
            <EmptyState message="등록된 증강 요청이 없습니다" />
          ) : (
            <div
              data-testid="job-card-grid"
              className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3"
            >
              {data.content.slice(0, 6).map((job) => (
                <JobCard key={job.jobId} job={job} />
              ))}
            </div>
          ))}
      </section>
    </section>
  );
}
