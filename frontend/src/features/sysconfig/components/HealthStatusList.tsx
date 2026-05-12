import { Eye, Wifi, WifiOff, Clock } from 'lucide-react';

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

function isUp(status: HealthStatus) {
  return status === 'UP';
}

function statusLabel(status: HealthStatus) {
  if (status === 'UP') return '정상';
  if (status === 'DOWN') return '연결 끊김';
  if (status === 'OUT_OF_SERVICE') return '서비스 중단';
  return '알 수 없음';
}

function statusBadgeClass(status: HealthStatus) {
  if (status === 'UP') return 'bg-green-100 text-green-700';
  if (status === 'OUT_OF_SERVICE') return 'bg-yellow-100 text-yellow-700';
  return 'bg-red-100 text-red-700';
}

function rowBgClass(status: HealthStatus) {
  if (status === 'UP') return 'border-green-200 bg-green-50';
  if (status === 'OUT_OF_SERVICE') return 'border-yellow-200 bg-yellow-50';
  return 'border-red-200 bg-red-50';
}

/**
 * UI/UX §4-16 ② 헬스 상태 (read-only, 5초 폴링).
 */
export function HealthStatusList() {
  const { data, isLoading, error } = useHealth();

  return (
    <Card
      title="외부 연동 상태"
      actions={
        <span className="flex items-center gap-1.5 text-xs font-medium text-blue-600 bg-blue-50 border border-blue-100 rounded-full px-2.5 py-1">
          <Eye size={12} />
          실시간 모니터링
        </span>
      }
    >
      {isLoading ? (
        <div className="flex justify-center py-4">
          <Spinner label="헬스 체크" />
        </div>
      ) : error || !data ? (
        <p className="text-body text-danger">헬스 상태를 불러올 수 없습니다.</p>
      ) : (
        <>
          <div className="flex flex-col gap-3">
            {data.components &&
              Object.entries(data.components).map(([key, comp]) => {
                const up = isUp(comp.status);
                const latencyMs = (comp.details as Record<string, unknown> | undefined)?.latencyMs;
                return (
                  <div
                    key={key}
                    className={[
                      'flex items-center justify-between p-3 rounded-lg border',
                      rowBgClass(comp.status),
                    ].join(' ')}
                  >
                    <div className="flex items-center gap-3">
                      {up ? (
                        <Wifi size={16} className="text-green-600 shrink-0" />
                      ) : (
                        <WifiOff size={16} className="text-red-500 shrink-0" />
                      )}
                      <div>
                        <p className="text-sm font-medium text-gray-800">
                          {componentLabels[key] ?? key}
                        </p>
                        {typeof (comp.details as Record<string, unknown> | undefined)?.url === 'string' && (
                          <p className="text-xs text-gray-400 truncate max-w-[200px]">
                            {(comp.details as Record<string, string>).url}
                          </p>
                        )}
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      {typeof latencyMs === 'number' && (
                        <span className="flex items-center gap-1 text-xs text-gray-500">
                          <Clock size={11} />
                          {latencyMs}ms
                        </span>
                      )}
                      <span
                        className={[
                          'text-xs font-semibold px-2 py-0.5 rounded-full',
                          statusBadgeClass(comp.status),
                        ].join(' ')}
                      >
                        {statusLabel(comp.status)}
                      </span>
                    </div>
                  </div>
                );
              })}

            {/* 전체 상태 요약 (컴포넌트 없을 때도 표시) */}
            {(!data.components || Object.keys(data.components).length === 0) && (
              <div className={['flex items-center justify-between p-3 rounded-lg border', rowBgClass(data.status)].join(' ')}>
                <div className="flex items-center gap-3">
                  {isUp(data.status) ? (
                    <Wifi size={16} className="text-green-600 shrink-0" />
                  ) : (
                    <WifiOff size={16} className="text-red-500 shrink-0" />
                  )}
                  <p className="text-sm font-medium text-gray-800">전체 상태</p>
                </div>
                <span className={['text-xs font-semibold px-2 py-0.5 rounded-full', statusBadgeClass(data.status)].join(' ')}>
                  {statusLabel(data.status)}
                </span>
              </div>
            )}
          </div>

          <p className="mt-4 text-xs text-gray-400 flex items-start gap-1.5">
            <Eye size={12} className="mt-0.5 shrink-0" />
            이 항목은 actuator/health에서 실시간 조회되며 편집할 수 없습니다.
          </p>
        </>
      )}
    </Card>
  );
}
