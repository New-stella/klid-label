// 헬스 체크 타입 (Spring Boot Actuator 미러)

export type HealthStatus = 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN';

export interface HealthComponent {
  status: HealthStatus;
  details?: Record<string, unknown>;
}

export interface HealthResponse {
  status: HealthStatus;
  components?: Record<string, HealthComponent>;
}
