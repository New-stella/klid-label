import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Spinner } from '@/components/common/Spinner';
import { BatchConfigCard } from '@/features/sysconfig/components/BatchConfigCard';
import { DangerActions } from '@/features/sysconfig/components/DangerActions';
import { FFmpegConfigCard } from '@/features/sysconfig/components/FFmpegConfigCard';
import { HealthStatusList } from '@/features/sysconfig/components/HealthStatusList';
import { useConfigs } from '@/features/sysconfig/hooks/useConfigs';

/**
 * SCR-MANAGE-SETTINGS 시스템 설정.
 *
 * UI/UX §4-16 — 3섹션 명확 분리:
 *   ① FFmpeg + Batch (독립 저장 카드)
 *   ② Health (read-only, 5초 폴링)
 *   ③ Danger Actions (placeholder)
 *
 * 보안: REVIEWER만 진입 (RoleGuard).
 */
export function SystemSettingsPage() {
  const { data: configs, isLoading, error } = useConfigs();

  return (
    <section className="flex flex-col gap-6">
      <PageHeader title="시스템 설정" description="FFmpeg/배치 파라미터 · 헬스 · 위험 액션" />

      {error && <ErrorState title="시스템 설정을 불러올 수 없습니다" />}

      {/* ① 설정 카드 — 독립 저장 */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {isLoading || !configs ? (
          <div className="col-span-full flex justify-center py-6">
            <Spinner label="설정 로딩" />
          </div>
        ) : (
          <>
            <FFmpegConfigCard configs={configs} />
            <BatchConfigCard configs={configs} />
          </>
        )}
      </div>

      {/* ② 헬스 상태 (read-only) */}
      <HealthStatusList />

      {/* ③ 위험 액션 (placeholder) */}
      <DangerActions />
    </section>
  );
}
