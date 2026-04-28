# Backend Coding Style Rules (Java / Kotlin)

> **이 규칙은 Java/Kotlin 백엔드(Spring Boot) 프로젝트에 적용됩니다.**
> **Python/FastAPI 프로젝트는 `rules/python-backend-coding-style.md`를 사용하세요.**
>
> 언어 선택 기준:
> - 기존 프로젝트는 해당 언어를 따릅니다 (`build.gradle`/`pom.xml` → Java, `pyproject.toml`/`requirements.txt` → Python).
> - 빈 프로젝트에서 별도 언급이 없으면 **Java 17**로 작성합니다.
> - Kotlin은 프로젝트에 이미 Kotlin이 사용 중이거나, 사용자가 "코틀린으로"라고 명시한 경우에만 허용됩니다.

## Java 규칙
- Java 17+ 기능 활용 (record, sealed class, text block, switch expression)
- DTO는 반드시 record 사용
- Lombok: @Getter, @RequiredArgsConstructor, @Builder 허용 / @Data, @Setter 금지
- null 반환 금지 → Optional 또는 빈 컬렉션 반환
- 상수는 enum으로 관리, 매직 넘버/문자열 금지

## Spring 규칙
- @Autowired 필드 주입 금지 → 생성자 주입 (@RequiredArgsConstructor)
- @Transactional은 Service 레이어에만, 조회: @Transactional(readOnly = true) 필수
- Controller에서 Entity 직접 반환 금지 → DTO 변환
- 입력값 검증: @Valid 필수

## 응답/예외
- 모든 API: `ApiResponse<T>` 래퍼 사용
- GlobalExceptionHandler + CustomException + ErrorCode enum 패턴

## 패키지 구조
```
com.company.app.{domain}/
├── controller/
├── service/
├── repository/
├── entity/
└── dto/
```

## 환경 분리
- 4개 환경: local, dev, stg, prd (Spring Profile)
- `application.yml` (공통) + `application-{profile}.yml` (환경별)
- 민감 정보는 환경변수 `${VAR}`로 주입, 평문 커밋 금지
- 코드에서 환경 분기 금지 → @Profile 또는 설정값 사용

## 네이밍
- 클래스: PascalCase / 메서드·변수: camelCase / 상수: UPPER_SNAKE_CASE
- API 경로: kebab-case (`/api/v1/order-items`)
- 테스트 메서드: 한글 서술형 (`주문취소_성공()`)
