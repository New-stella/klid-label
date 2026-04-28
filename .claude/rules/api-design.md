# API Design Rules

REST API 설계 시 아래 규칙을 항상 적용합니다.

## URL 규칙

`/api/v{version}/{resource}` — 리소스명: 복수형, kebab-case, 동사 금지

- 중첩 리소스: 1단계까지만 허용, 2단계 이상은 별도 API로 분리
- `/api/v1/orders/{orderId}/items` (O) / `/api/v1/orders/{orderId}/items/{itemId}/options` (X)

## HTTP 메서드 & 상태코드

| 메서드 | 용도 | 성공 코드 | 응답 Body |
|:------:|------|:---------:|:---------:|
| GET | 조회 | 200 OK | 데이터 |
| POST | 생성 | 201 Created | 생성된 데이터 |
| PUT | 전체 수정 | 200 OK | 수정된 데이터 |
| PATCH | 부분 수정 | 200 OK | 수정된 데이터 |
| DELETE | 삭제 | 204 No Content | 없음 |

### 에러 상태코드

| 코드 | 용도 |
|:----:|------|
| 400 | 입력값 검증 실패 (잘못된 파라미터) |
| 401 | 인증 실패 (토큰 없음/만료) |
| 403 | 인가 실패 (권한 없음) |
| 404 | 리소스 없음 |
| 409 | 충돌 (중복 데이터) |
| 500 | 서버 내부 오류 |

## 응답 형식

모든 API는 `ApiResponse<T>` 래퍼 사용: `{ "success": bool, "data": T, "message": string, "errorCode": string }`

- `errorCode`: ErrorCode enum의 name() 값 (프론트에서 분기용)
- `message`: 사용자에게 보여줄 수 있는 메시지
- 스택트레이스, 내부 경로 등 기술 정보 절대 포함 금지

## 페이징

목록 조회는 반드시 페이징 적용. 전체 조회(페이징 없는 findAll) 금지.

- 요청: `GET /api/v1/orders?page=0&size=20&sort=createdAt,desc`
- 응답 data: `{ "content": [...], "totalElements": 150, "totalPages": 8, "number": 0, "size": 20 }`
- 기본값: page=0, size=20 (최대 100), sort=createdAt,desc

## 버저닝

- URL 방식: `/api/v1/`, `/api/v2/`
- Breaking change는 새 버전(`v2`)으로 분리, 기존 버전 유지
- Breaking change: 필드 삭제, 필드 타입 변경, 필수 파라미터 추가
- 하위호환 가능: 응답 새 필드 추가, 선택적 파라미터 추가, 새 엔드포인트 추가

## 요청 검증

- 모든 요청 DTO에 `@Valid` 필수
- 요청 DTO에 검증 어노테이션 필수 (`@NotNull`, `@Min`, `@Max`, `@NotBlank`, `@Size`)

## 금지 사항

- URL에 동사 사용 (`/getOrders`, `/deleteUser`)
- 페이징 없는 목록 전체 조회
- 에러 응답에 스택트레이스 포함
- API 응답에 Entity 직접 반환 (DTO 변환 필수)
- 같은 URL에 쿼리 파라미터로 행위 분기 (`?action=delete`)
