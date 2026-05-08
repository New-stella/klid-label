import { Settings } from 'lucide-react';

import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Spinner } from '@/components/common/Spinner';
import { BatchConfigCard } from '@/features/sysconfig/components/BatchConfigCard';
import { DangerActions } from '@/features/sysconfig/components/DangerActions';
import { FFmpegConfigCard } from '@/features/sysconfig/components/FFmpegConfigCard';
import { HealthStatusList } from '@/features/sysconfig/components/HealthStatusList';
import { useConfigs } from '@/features/sysconfig/hooks/useConfigs';

/**
 * SCR-MANAGE-SETTINGS 시스템 설정 (V1.x mock 시각 정합).
 *
 * UI/UX §4-16 — 3섹션 명확 분리:
 *   ① 편집 가능 — DB 영속화 (FFmpeg + Batch — 독립 저장 카드)
 *   ② 실시간 모니터링 — Health (read-only, 5초 폴링)
 *   ③ 위험 액션 (placeholder)
 *
 * 보안: REVIEWER만 진입 (RoleGuard).
 */
export function SystemSettingsPage() {
  const { data: configs, isLoading, error } = useConfigs();

  return (
    <section className="flex flex-col gap-6">
      <PageHeader
        title="시스템 설정"
        description="FFmpeg/배치 파라미터 · 외부 연동 헬스 · 위험 액션"
        actions={
          <div className="flex items-center justify-center rounded-lg bg-gray-100 p-2">
            <Settings className="h-5 w-5 text-gray-600" aria-hidden />
          </div>
        }
      />

      {error && <ErrorState title="시스템 설정을 불러올 수 없습니다" />}

      {/* ① 편집 가능 — DB 영속화 */}
      <div className="flex flex-col gap-3">
        <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-500">
          편집 가능 — DB 영속화
        </h2>
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
      </div>

      {/* ② 실시간 모니터링 — read-only */}
      <div className="flex flex-col gap-3">
        <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-500">
          실시간 모니터링
        </h2>
        <HealthStatusList />
      </div>

      {/* ③ 위험 액션 — placeholder. 그룹 헤더는 DangerActions Card title('위험 액션')과
          getByText 충돌 방지를 위해 '위험 영역'으로 표기. */}
      <div className="flex flex-col gap-3">
        <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-500">
          위험 영역
        </h2>
        <DangerActions />
      </div>
    </section>
  );
}
