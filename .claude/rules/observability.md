# Observability Rules

코드 생성 및 리뷰 시 아래 관찰가능성(Observability) 규칙을 항상 확인합니다.

## 적용 기준

Spring Boot 프로젝트에 자동 적용됩니다.

## Spring Actuator

- `spring-boot-starter-actuator` 의존성 필수
- 헬스체크 엔드포인트 `/actuator/health` 항상 노출
- 운영 환경에서 민감 엔드포인트 노출 금지

```yaml
# application.yml (공통)
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  endpoint:
    health:
      show-details: when-authorized

# application-prd.yml
management:
  endpoints:
    web:
      exposure:
        include: health   # 운영: health만 노출
```

### 금지 패턴
- `include: "*"` 운영 환경에서 사용 금지 (env, beans, heapdump 등 민감 정보 노출)
- 인증 없이 `/actuator/**` 전체 공개 금지

## Micrometer 메트릭

- 비즈니스 주요 이벤트에 커스텀 메트릭 추가
- 메트릭 네이밍: `{도메인}.{이벤트}` snake_case (예: `order.created`, `payment.failed`)
- 태그(tag)로 차원 추가 (status, type 등)

```java
// Counter 예시
meterRegistry.counter("order.created", "status", "success").increment();
meterRegistry.counter("payment.failed", "reason", errorCode).increment();

// Timer 예시 (외부 API 호출 시간 측정)
Timer.Sample sample = Timer.start(meterRegistry);
// ... 외부 API 호출
sample.stop(meterRegistry.timer("external.api.duration", "service", "payment-gateway"));
```

### 커스텀 메트릭 적용 기준

| 적용 O | 적용 X |
|--------|--------|
| 주문/결제/회원가입 등 핵심 비즈니스 이벤트 | 단순 CRUD 조회 |
| 외부 API 호출 (성공/실패/응답시간) | 내부 유틸 메서드 |
| 배치 처리 (처리 건수, 소요 시간) | 캐시 히트/미스 (별도 메트릭 존재) |

## 분산 추적 (Trace ID)

- 모든 요청에 `traceId` 부여 — MDC에 설정 (logging.md 참조)
- `X-Trace-Id` 헤더로 전파 (외부 요청 시 헤더 포함)
- 로그 패턴에 `[%X{traceId:-}]` 포함 필수

```java
// Filter 또는 Interceptor에서 설정
String traceId = Optional.ofNullable(request.getHeader("X-Trace-Id"))
    .orElse(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
MDC.put("traceId", traceId);
response.setHeader("X-Trace-Id", traceId);
```

## 헬스체크 커스텀

외부 의존성(DB, Redis, 외부 API)이 있으면 커스텀 헬스 인디케이터 추가를 검토합니다.

```java
@Component
public class ExternalApiHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // 외부 API 연결 확인
        return Health.up().withDetail("externalApi", "available").build();
    }
}
```

## 금지 패턴

- `System.currentTimeMillis()`로 직접 성능 측정 후 로그만 출력 → Micrometer Timer 사용
- 운영 환경 `/actuator/*` 인증 없이 전체 노출
- traceId 없이 에러 로그만 출력 (원인 추적 불가)
- 외부 API 호출 결과 메트릭 미수집 (장애 감지 불가)
