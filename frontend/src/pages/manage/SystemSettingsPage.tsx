import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Spinner } from '@/components/common/Spinner';
import { BatchConfigCard } from '@/features/sysconfig/components/BatchConfigCard';
import { DangerActions } from '@/features/sysconfig/components/DangerActions';
import { DeidentConfigCard } from '@/features/sysconfig/components/DeidentConfigCard';
import { HealthStatusList } from '@/features/sysconfig/components/HealthStatusList';
import { IntegrationEndpointsCard } from '@/features/sysconfig/components/IntegrationEndpointsCard';
import { PrecisionConfigCard } from '@/features/sysconfig/components/PrecisionConfigCard';
import { YoloConfigCard } from '@/features/sysconfig/components/YoloConfigCard';
import { useConfigs } from '@/features/sysconfig/hooks/useConfigs';
import { useConfigStrings } from '@/features/sysconfig/hooks/useConfigStrings';

/**
 * SCR-MANAGE-SETTINGS 시스템 설정 (V1.x mock 시각 정합).
 *
 * UI/UX §4-16 — 3섹션 명확 분리:
 *   ① 편집 가능 — DB 영속화 (Batch + YOLO + 라벨링 정밀도 + 비식별 옵션 + 연동 서버 주소 — 독립 저장 카드 5종)
 *   ② 실시간 모니터링 — Health (read-only, 5초 폴링)
 *   ③ 위험 구역 (placeholder)
 *
 * 보안: REVIEWER만 진입 (RoleGuard).
 */
export function SystemSettingsPage() {
  const { data: configs, isLoading, error } = useConfigs();
  // R11 — 연동 주소는 문자열이라 숫자 변환 맵(`useConfigs`)에서 걸러진다. 같은 queryKey 를 쓰므로
  // 요청은 한 번만 나가고 select 만 다르다.
  const { data: stringConfigs } = useConfigStrings();

  return (
    <section className="flex flex-col gap-6">
      <PageHeader
        // 제목 옆 장식 아이콘은 두지 않는다 — 제목 텍스트를 되풀이할 뿐이다.
        title="시스템 설정"
        description="배치 파라미터 · 외부 연동 헬스 · 위험 구역"
      />

      {error && <ErrorState title="시스템 설정을 불러올 수 없습니다" />}

      {/* ① 편집 가능 — DB 영속화 */}
      <div className="flex flex-col gap-3">
        <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-600">
          편집 가능 — DB 영속화
        </h2>
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {isLoading || !configs ? (
            <div className="col-span-full flex justify-center py-6">
              <Spinner label="설정 로딩" />
            </div>
          ) : (
            <>
              <BatchConfigCard configs={configs} />
              <YoloConfigCard configs={configs} />
              <PrecisionConfigCard configs={configs} />
              <DeidentConfigCard configs={configs} />
              <IntegrationEndpointsCard configs={stringConfigs ?? {}} />
            </>
          )}
        </div>
      </div>

      {/* ② 실시간 모니터링 — read-only */}
      <div className="flex flex-col gap-3">
        <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-600">
          실시간 모니터링
        </h2>
        <HealthStatusList />
      </div>

      {/* ③ 위험 액션 — placeholder */}
      <div className="flex flex-col gap-3">
        <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-600">
          위험 액션
        </h2>
        <DangerActions />
      </div>
    </section>
  );
}
