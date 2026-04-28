# Performance Rules

코드 생성 및 리뷰 시 아래 성능 규칙을 항상 확인합니다.

## N+1 쿼리 방지 (Critical)

연관 엔티티를 반복 조회하는 N+1 문제는 가장 흔한 성능 병목입니다.

해결 방법: `JOIN FETCH`, `@EntityGraph(attributePaths)`, `@BatchSize(size = 100)`

### 체크리스트
- @OneToMany, @ManyToOne은 기본 LAZY 설정
- EAGER 로딩 사용 시 반드시 사유 명시
- List 반환 Repository 메서드는 쿼리 로그로 N+1 확인
- Service에서 연관 엔티티 접근 시 fetch join 또는 @EntityGraph 사용

## 페이징 필수

목록 조회 페이징 규칙은 api-design.md 참조. `findAll()` without Pageable 사용 금지.

- 대용량 페이징(10만건+)은 No-Offset(커서 기반) 방식 검토

## 인덱스

- WHERE, JOIN, ORDER BY에 사용되는 컬럼은 인덱스 확인
- 복합 인덱스 컬럼 순서: 카디널리티 높은 것 앞에
- 외래키 컬럼 인덱스 필수
- soft delete 사용 시 `deleted_at` 포함 인덱스 검토
- JSON 컬럼 사용 시 Generated Column + 인덱스 검토

## 쿼리 최적화

- SELECT * 금지 → 필요한 컬럼만 Projection (DTO 직접 조회)
- 서브쿼리보다 JOIN 사용
- 대량 처리: 1건씩 save 금지 → `saveAll()` 또는 `jdbcTemplate.batchUpdate()` 사용
- JPA batch 설정: `hibernate.jdbc.batch_size: 50`, `order_inserts: true`, `order_updates: true`

## 캐싱

변경 빈도 낮고 조회 빈도 높은 데이터는 캐시를 검토합니다.

### 캐시 적용 기준

| 적용 O | 적용 X |
|--------|--------|
| 코드성 데이터 (카테고리, 지역) | 자주 변경되는 데이터 (재고, 잔액) |
| 사용자 프로필 (변경 드뭄) | 실시간 정합성 필요한 데이터 |
| 외부 API 응답 (변경 드뭄) | 트랜잭션 내 데이터 |

### 캐시 사용 규칙
- TTL 필수 설정 (무기한 캐시 금지)
- 캐시 갱신 전략 문서화 (TTL 만료 / 이벤트 기반 무효화)
- 캐시 키 네이밍: `{도메인}:{식별자}` (예: `product:123`)

## 커넥션 & 풀

- HikariCP 기본 설정 확인 (maximum-pool-size, connection-timeout)
- 외부 API 호출 시 타임아웃 필수 설정 (connect-timeout, read-timeout)
- RestTemplate/WebClient 사용 시 커넥션 풀 설정 확인
