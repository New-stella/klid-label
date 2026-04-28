# DDD (Domain-Driven Design) Rules

복잡한 비즈니스 도메인을 설계할 때 적용하는 규칙입니다.
단순 CRUD는 기존 `/cc-generate` 방식으로, 비즈니스 규칙이 복잡한 도메인은 DDD로 설계합니다.

## 적용 기준

| DDD 적용 O | DDD 적용 X (기존 방식) |
|-----------|---------------------|
| 비즈니스 규칙이 3개 이상 | 단순 CRUD (공지사항, FAQ 등) |
| 상태 전이가 있음 (주문→결제→배송) | 상태 없이 저장/조회만 |
| 여러 엔티티가 함께 변경됨 | 단일 엔티티 독립 변경 |
| 도메인 전문가 용어가 있음 | 기술적 데이터 관리 |

## 패키지 구조

```
com.company.app.{domain}/
├── domain/                    # AggregateRoot, Entity, VO, Repository 인터페이스, DomainService
├── application/               # {Domain}AppService, dto/ (Command, Info)
├── infra/                     # Repository 구현체 (JPA)
└── controller/                # {Domain}Controller
```

### 기존 구조와 비교

```
기존 (cc-generate)          DDD (cc-ddd)
─────────────────          ──────────────
controller/                controller/
service/          →        application/     (유스케이스 조합)
                           domain/          (비즈니스 로직)
repository/                infra/           (기술 구현)
entity/                    domain/          (Aggregate, VO)
dto/                       application/dto/ (Command, Info)
```

## Aggregate 설계 원칙

### Aggregate Root
- `@Entity` + `@Getter` + `@NoArgsConstructor(access = PROTECTED)` 기본 구성
- 생성은 정적 팩토리 메서드 (`Order.create(...)`)
- 비즈니스 로직은 엔티티 내부에 캡슐화 (`order.cancel()`)

### 규칙
- 외부에서 Aggregate 내부 엔티티에 직접 접근 금지 → Root를 통해서만 접근
- Aggregate 간 참조는 **ID로만** (객체 참조 `@ManyToOne` 금지 → `private Long memberId`)
- 하나의 트랜잭션에서 하나의 Aggregate만 수정
- Aggregate 크기는 작게 유지 (엔티티 4개 이하 권장)
- `@Setter` 금지 → 의미 있는 비즈니스 메서드로 상태 변경 (`order.cancel()` not `order.setStatus()`)

## Value Object

- `@Embeddable` + `@Getter` + `@NoArgsConstructor(access = PROTECTED)` + `@EqualsAndHashCode`
- 불변 (생성 후 변경 불가, 새 객체 반환)
- 자기 검증 (생성 시점에 유효성 체크)
- `@Embedded`로 사용 (별도 테이블 X)

### VO 적용 기준
| VO로 만들 것 | 그냥 필드로 둘 것 |
|-------------|-----------------|
| 금액 (Money) | 단순 카운트 (조회수) |
| 주소 (Address) | 단순 문자열 (메모) |
| 기간 (DateRange) | 단일 날짜 (생성일) |
| 좌표 (Location) | 단순 숫자 (정렬순서) |
| 이메일 (Email), 전화번호 (PhoneNumber) | 단순 식별자 (ID) |

## Domain Service

- 하나의 Aggregate로 해결할 수 없는 비즈니스 로직 (여러 Aggregate 간 협력)
- 단일 Aggregate로 해결 가능하면 만들지 않음 → Aggregate 메서드 사용
- 상태를 갖지 않음 (stateless)
- 네이밍: `{Domain}DomainService` 또는 `{Domain}Service` (domain 패키지 내)

## Application Service

- 유스케이스를 조합하는 레이어, 비즈니스 로직 포함 금지
- `@Transactional` 선언, 조회: `@Transactional(readOnly = true)` 필수
- 역할: 리포지토리 조회 → 도메인 메서드 호출 → 결과 DTO 변환
- 네이밍: `{Domain}AppService` (기존 Service와 구분)

## DTO 네이밍

| 용도 | 네이밍 | 위치 |
|------|--------|------|
| 생성/수정 요청 | `Create{Domain}Command`, `Update{Domain}Command` | `application/dto/` |
| 응답 | `{Domain}Info`, `{Domain}Summary` | `application/dto/` |
| Controller 요청 | `{Domain}Request` | `controller/` 또는 `application/dto/` |

- Command: `record` + `@Valid` 어노테이션, `toEntity()`/`toItems()` 변환 메서드 포함
- Info: `record` + `static from(Entity)` 팩토리 메서드 포함

## Repository

- 간소화: domain에 `JpaRepository` 직접 상속 (소규모 프로젝트)
- 정석: domain에 인터페이스 정의, infra에서 JPA 구현체

## 금지 패턴

- Service에 비즈니스 로직 작성 (빈약한 도메인 모델) → 도메인 객체에 위임
- Setter로 상태 변경 → 비즈니스 메서드 사용
- 외부에서 Aggregate 내부 엔티티 직접 수정 → Root를 통해 변경
