# Logging Rules

코드 생성 및 리뷰 시 아래 로깅 규칙을 항상 확인합니다.

> 보안 관련 로깅 규칙(Log Forging, Privacy Violation, 민감 정보 목록)은 security.md 참조

## 로그 레벨 기준

| 레벨 | 용도 | 예시 |
|:----:|------|------|
| ERROR | 장애, 데이터 정합성 깨짐 (알림 발송 대상) | DB 커넥션 실패, 결제 API 500 응답 |
| WARN | 잠재적 문제, 재시도 가능한 실패 | 외부 API 타임아웃 후 재시도 성공, 캐시 미스 |
| INFO | 비즈니스 이벤트 | 주문 생성, 결제 완료, 회원 가입 |
| DEBUG | 개발 중 디버깅용 (local/dev만 활성화) | 쿼리 파라미터, 메서드 진입/종료 |

## Logback 출력 패턴

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-}] %logger{36} - %msg%n"
```

- traceId: MDC에 설정 (Filter/Interceptor에서 `X-Trace-Id` 헤더 또는 UUID 12자리)

## 로그 메시지 포맷

표준 형식: `[도메인] {행위} {키=값, 키=값, ...}`

```java
log.info("[Order] created orderId={}, userId={}", orderId, userId);
log.error("[Order] processing failed orderId={}", orderId, e);
```

### 포맷 규칙
- `[도메인]` 대괄호로 도메인 표시 (Order, Payment, Member 등)
- 행위는 영문 과거형 소문자 (created, cancelled, failed, completed)
- 키=값 형태로 추적 가능한 ID 포함
- SLF4J 플레이스홀더 `{}` 사용 필수, 문자열 연결(`+`) 금지
- 예외 객체는 마지막 인자로 전달 (스택트레이스 자동 출력)
- elapsed(소요시간)는 ms 단위로 표기

## 환경별 로그 레벨

```yaml
# application-prd.yml
logging:
  level:
    root: INFO
    org.hibernate.SQL: WARN
```
