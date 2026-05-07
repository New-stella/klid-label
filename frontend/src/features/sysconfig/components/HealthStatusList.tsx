import { Card } from '@/components/common/Card';
import { Spinner } from '@/components/common/Spinner';
import { useHealth } from '@/features/health/hooks/useHealth';
import type { HealthStatus } from '@/features/health/types';

const componentLabels: Record<string, string> = {
  db: 'DB',
  diskSpace: '디스크',
  controlServer: '관제서버',
  portalServer: '포털서버',
  aiServer: 'AI 서버',
  gitea: 'Gitea',
  deidentify: '비식별 서버',
};

const statusColor: Record<HealthStatus, string> = {
  UP: 'text-success',
  DOWN: 'text-danger',
  OUT_OF_SERVICE: 'text-warning',
  UNKNOWN: 'text-neutral',
};

/**
 * UI/UX §4-16 ② 헬스 상태 (read-only, 5초 폴링).
 *
 * 보안: 외부 의존성 응답을 그대로 노출하되, 사용자 입력 없음 (read-only).
 */
export function HealthStatusList() {
  const { data, isLoading, error } = useHealth();

  return (
    <Card title="헬스 상태">
      {isLoading ? (
        <div className="flex justify-center py-4">
          <Spinner label="헬스 체크" />
        </div>
      ) : error || !data ? (
        <p className="text-body text-danger">헬스 상태를 불러올 수 없습니다.</p>
      ) : (
        <div className="flex flex-col gap-2">
          <div className="flex items-center justify-between border-b border-border pb-2">
            <span className="text-body font-medium text-primary">전체 상태</span>
            <span className={statusColor[data.status]}>{data.status}</span>
          </div>
          {data.components &&
            Object.entries(data.components).map(([key, comp]) => (
              <div
                key={key}
                className="flex items-center justify-between text-body"
              >
                <span className="text-neutral">{componentLabels[key] ?? key}</span>
                <span className={statusColor[comp.status]}>{comp.status}</span>
              </div>
            ))}
        </div>
      )}
    </Card>
  );
}
