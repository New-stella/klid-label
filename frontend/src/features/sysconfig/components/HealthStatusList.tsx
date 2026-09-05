import { Eye, Wifi, WifiOff, Clock } from 'lucide-react';

import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/components/common/Card';
import { Skeleton } from '@/components/common/Skeleton';
import { useHealth } from '@/features/health/hooks/useHealth';
import type { HealthStatus } from '@/features/health/types';

/**
 * 헬스 컴포넌트 키 → 화면 표시명.
 *
 * ⚠ 키는 **서버 응답 계약**이다 — BE ManageHealthController 가 components 맵에 넣는 키와
 * 정확히 일치해야 한다. 렌더가 `componentLabels[key] ?? key` 폴백이라 빠진 키는 오류 없이
 * **원문 키가 그대로 화면에 노출**된다(`database` 가 실제로 그렇게 새어 나갔다).
 * 양방향 대조는 healthComponentLabelContract.test.ts 가 고정한다.
 */
export const componentLabels: Record<string, string> = {
  deidentify: '비식별 서버',
  aiServer: 'AI 서버',
  database: '데이터베이스',
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
  // ⚠ 2026-08-08: text-{color}-700 사용 이유는 StatusBadge.tsx 상단 주석 참조(AA 대비 회복).
  if (status === 'UP') return 'bg-success/10 text-success-700';
  if (status === 'OUT_OF_SERVICE') return 'bg-warning/10 text-warning-700';
  return 'bg-danger/10 text-danger-700';
}

function rowBgClass(status: HealthStatus) {
  if (status === 'UP') return 'border-success/30 bg-success/10';
  if (status === 'OUT_OF_SERVICE') return 'border-warning/30 bg-warning/10';
  return 'border-danger/30 bg-danger/10';
}

/**
 * UI/UX §4-16 ② 헬스 상태 (read-only, 5초 폴링).
 */
export function HealthStatusList() {
  const { data, isLoading, error } = useHealth();

  return (
    <Card>
      <CardHeader>
        <CardTitle>외부 연동 상태</CardTitle>
        <CardAction>
          <span className="flex items-center gap-1.5 text-label font-medium text-info-700 bg-info/10 border border-info/20 rounded-full px-2.5 py-1">
            <Eye size={12} />
            실시간 모니터링
          </span>
        </CardAction>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          // 로딩은 스피너가 아니라 스켈레톤이다 — 목록의 행 구조를 미리 보여줘 레이아웃이
          // 흔들리지 않게 한다(5초 폴링이라 전환이 잦다).
          <div className="flex flex-col gap-3" data-testid="health-loading">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="w-full" height={58} />
            ))}
          </div>
        ) : error || !data ? (
          <p className="text-body text-danger">헬스 상태를 불러올 수 없습니다.</p>
        ) : (
          <>
            <div className="flex flex-col gap-3">
              {data.components &&
                Object.entries(data.components).map(([key, comp]) => {
                  const up = isUp(comp.status);
                  const latencyMs = (comp.details as Record<string, unknown> | undefined)
                    ?.latencyMs;
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
                          <Wifi size={16} className="text-success shrink-0" />
                        ) : (
                          <WifiOff size={16} className="text-danger shrink-0" />
                        )}
                        <div>
                          <p className="text-body-md font-medium text-gray-800">
                            {componentLabels[key] ?? key}
                          </p>
                          {typeof (comp.details as Record<string, unknown> | undefined)?.url ===
                            'string' && (
                            <p className="text-caption text-gray-600 truncate max-w-[200px]">
                              {(comp.details as Record<string, string>).url}
                            </p>
                          )}
                        </div>
                      </div>

                      <div className="flex items-center gap-3">
                        {typeof latencyMs === 'number' && (
                          <span className="flex items-center gap-1 text-caption text-gray-500">
                            <Clock size={11} />
                            {latencyMs}ms
                          </span>
                        )}
                        <span
                          className={[
                            'text-label font-semibold px-2 py-0.5 rounded-full',
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
                <div
                  className={[
                    'flex items-center justify-between p-3 rounded-lg border',
                    rowBgClass(data.status),
                  ].join(' ')}
                >
                  <div className="flex items-center gap-3">
                    {isUp(data.status) ? (
                      <Wifi size={16} className="text-success shrink-0" />
                    ) : (
                      <WifiOff size={16} className="text-danger shrink-0" />
                    )}
                    <p className="text-body-md font-medium text-gray-800">전체 상태</p>
                  </div>
                  <span
                    className={[
                      'text-label font-semibold px-2 py-0.5 rounded-full',
                      statusBadgeClass(data.status),
                    ].join(' ')}
                  >
                    {statusLabel(data.status)}
                  </span>
                </div>
              )}
            </div>

            <p className="mt-4 text-caption text-gray-600 flex items-start gap-1.5">
              <Eye size={12} className="mt-0.5 shrink-0" />이 항목은 actuator/health에서 실시간
              조회되며 편집할 수 없습니다.
            </p>
          </>
        )}
      </CardContent>
    </Card>
  );
}
