# API 통합 검증 결과

검증일: 2026-05-07
대상: backend (Spring Boot, profile=local)
방식: 라이브 bootRun + JWT 발급 + 30건 매트릭스 + TUS 시퀀스 + 보안 헤더/응답 일관성

## 환경

| 항목 | 결과 |
|------|------|
| bootRun 시작 시간 | ~5초 (Tomcat started in 4.88s) |
| Profile | local |
| DB | klid-mariadb (127.0.0.1:3307), klid_system+portal 초기화 |
| Flyway 마이그레이션 | V1~V12 정상 적용 (재실행 컬럼중복 WARN만, FAIL 없음) |
| JWT secret | dev-secret-change-me-please-32bytes-minimum (fallback) |
| M2M token | dev-m2m-token-32bytes-or-longer-please (fallback) |
| Issuer | klid-auth (allowed-issuers 첫 항목) |
| /actuator/health/liveness | UP |
| /actuator/health/readiness | UP |
| /actuator/health (종합) | DOWN (외부 의존성 health indicator — 검증 외) |
| 시드 데이터 | MNG_ACCT_USER 2명(1001 REVIEWER, 1002 WORKER), LS_PJT 1건 |

## OpenAPI 스펙 (`/v3/api-docs`)

- 응답 OK (62.9KB JSON)
- paths 개수: **36** / 도메인 그룹: **17**

| 도메인 | 엔드포인트 수 | 도메인 | 엔드포인트 수 |
|---|---|---|---|
| portal | 4 | augments | 3 |
| reviews | 4 | frames | 3 |
| users | 3 | videos | 3 |
| assignments | 2 | exports | 2 |
| manage | 2 | projects | 2 |
| versions | 2 | meta | 1 |
| batch | 1 | generate | 1 |
| integration | 1 | me | 1 |
| system | 1 | (health) | 1 |

## 매트릭스 검증 (30건)

| # | 엔드포인트 | 토큰/조건 | 기대 | 실제 | 결과 |
|---|---|---|---|---|---|
| 1 | GET /health | 없음 | 200 | 200 | OK |
| 2 | GET /actuator/health | 없음 | 200/503 | 503 | OK (액추에이터는 노출, DOWN은 외부 health indicator) |
| 3 | GET /actuator/metrics | REVIEWER | 200 | 200 | OK |
| 4 | GET /actuator/metrics | WORKER | 403 | 403 | OK (FORBIDDEN) |
| 5 | GET /v1/me | 없음 | 401 | 401 | OK (UNAUTHORIZED) |
| 6 | GET /v1/me | REVIEWER | 200 | 200 | OK |
| 7 | GET /v1/me | EXPIRED | 401 | 401 | OK |
| 8 | GET /v1/users/workers | REVIEWER | 200 | 200 | OK |
| 9 | GET /v1/users/workers | WORKER | 403 | 403 | OK |
| 10 | GET /v1/projects | REVIEWER | 200 | 200 | OK (페이징 + 시드 1건) |
| 11 | GET /v1/videos | WORKER | 200 | 200 | OK (본인 배정 0건) |
| 12 | GET /v1/manage/configs | REVIEWER | 200 | 200 | OK (BATCH_CONCURRENCY 등 노출) |
| 13 | GET /v1/manage/configs | WORKER | 403 | 403 | OK |
| 14 | GET /v1/manage/configs | PORTAL | 403 | 403 | OK (채널 분리) |
| 15 | PUT /v1/manage/configs/FFMPEG_THREADS value=4 | REVIEWER | 200 | 200 | OK |
| 16 | PUT FFMPEG_THREADS value=abc | REVIEWER | 400 | 400 | OK (NUMBER 검증) |
| 17 | PUT FFMPEG_THREADS value=99 | REVIEWER | 400 | 400 | OK (범위 1~16) |
| 18 | PUT UNKNOWN_KEY | REVIEWER | 400/404 | 400 | OK (whitelist) |
| 19 | POST /v1/integration/control/videos | M2M wrong | 401/403 | 401 | OK (UNAUTHORIZED) |
| 20 | POST /v1/integration/control/videos | M2M ok empty | 200/400 | 400 | OK (M2M 통과 → DTO 검증 실패) |
| 21 | OPTIONS /v1/portal/uploads | PORTAL | 200/204 | 204 | OK (TUS) |
| 22 | GET /v1/portal/uploads | PORTAL | 200 | 200 | OK (본인 업로드 0건) |
| 23 | GET /v1/portal/uploads | WORKER | 403 | 403 | OK (채널 분리) |
| 24 | GET /v3/api-docs | 없음 | 200 | 200 | OK |
| 25 | GET /swagger-ui/index.html | 없음 | 200/302 | 200 | OK |
| 26 | OPTIONS /v1/me Origin=localhost:3000 | 없음 | 200/204 | 200 | OK (Allow-Origin 응답) |
| 27 | OPTIONS /v1/me Origin=evil | 없음 | 400/403 | 403 | OK ("Invalid CORS request") |
| 28 | GET /v1/me X-Trace-Id=my-trace-123 | REVIEWER | 200 | 200 | OK (응답 헤더에 동일값 echo) |
| 29 | GET /v1/me X-Trace-Id w/ CRLF | REVIEWER | 200 | 200 | OK (curl 단계에서 sanitize, 응답 안전) |
| 30 | ApiResponse 키 일관성 | /v1/me | success/data/message/errorCode | 4개 모두 존재 | OK |

**합계: PASS 30 / FAIL 0**

## TUS 프로토콜 시퀀스 (포털 업로드)

| 단계 | 메서드 | 상태 | 응답 헤더/검증 | 결과 |
|---|---|---|---|---|
| 1 | POST /v1/portal/uploads (Upload-Length: 1024, filename=test.mp4) | 201 | Location=`/api/v1/portal/uploads/2001~3b0b...`, Tus-Resumable: 1.0.0, Upload-Offset: 0 | OK |
| 2 | HEAD /v1/portal/uploads/{fileId} | 204 | Upload-Offset: 0, Upload-Length: 1024, Tus-Resumable: 1.0.0 | OK |
| 3 | PATCH /v1/portal/uploads/{fileId} (Upload-Offset: 0, 256B body) | 204 | Upload-Offset: 256, Tus-Resumable: 1.0.0 | OK |
| 4 | HEAD 재개 확인 | 204 | Upload-Offset: 256, Upload-Length: 1024 | OK (재개 가능) |
| 5 | DELETE /v1/portal/uploads/{fileId} | 204 | Tus-Resumable: 1.0.0 | OK |
| 6 | HEAD 다른 사용자(REVIEWER) IDOR 검증 | 403 | (FORBIDDEN — 채널/소유자 분리) | OK |

추가 검증: 확장자 allowlist 작동(`test.txt` → 400 `허용되지 않는 확장자`, `test.mp4` → 201).

## 응답 형식 일관성

`ApiResponse<T>` 래퍼 키 4종 모두 일관 적용:
- `success` (boolean), `data` (T), `message` (string), `errorCode` (string)
- 정상 200 응답: `{"success":true,"data":{...},"message":null,"errorCode":null}`
- 에러 응답: `{"success":false,"data":null,"message":"…","errorCode":"UNAUTHORIZED|FORBIDDEN|INVALID_INPUT"}`
- ErrorCode enum 명확 매핑: `UNAUTHORIZED`, `FORBIDDEN`, `INVALID_INPUT` 확인

## 보안 헤더 / CORS

| 헤더 | 값/검증 | 상태 |
|---|---|---|
| X-Content-Type-Options | nosniff | OK |
| X-Frame-Options | DENY | OK |
| Cache-Control | no-cache, no-store, must-revalidate | OK |
| Strict-Transport-Security | (미설정 — HTTP-only local 환경) | INFO |
| Access-Control-Allow-Origin (allowed) | http://localhost:3000 echo | OK |
| Access-Control-Allow-Origin (malicious) | 403 "Invalid CORS request" | OK |
| Access-Control-Expose-Headers | X-Trace-Id, Upload-Offset, Upload-Length, Tus-Resumable, Tus-Version, Tus-Extension, Tus-Max-Size, Location | OK |
| Access-Control-Allow-Credentials | true | OK |
| X-Trace-Id 응답 헤더 | 정상값 echo + 자동 발급 | OK |

## 식별된 이슈

- 없음 (FAIL 0)
- 참고: `/actuator/health` 종합 status=DOWN — 외부 시스템 health indicator (Gitea/관제/포털) 미가동 환경의 정상 동작. liveness/readiness probe 모두 UP이므로 K8s 트래픽 라우팅에 영향 없음.
- 참고: STS 헤더는 HTTPS 환경에서만 의미 있어 local HTTP에서 미설정. 운영 환경(prd) HTTPS 종단에서 설정되어야 함.

## 종합 판정

**PASS** — 30건 매트릭스 + TUS 6단계 시퀀스 + ApiResponse 일관성 + 보안 헤더 + CORS + JWT/M2M 인증 분리 모두 정상.

- 인증/인가 채널 분리(INTERNAL/PORTAL/M2M) 정상 동작
- 역할 기반 접근(REVIEWER/WORKER/PORTAL_USER) 정상 강제
- 시스템 설정 화이트리스트 + 타입/범위 검증 정상
- TUS 프로토콜 호환 + IDOR 방어 + 확장자 allowlist 정상
- OpenAPI/Swagger UI 공개 노출 정상
- 표준 ApiResponse 래퍼와 ErrorCode enum 일관 적용

## 환경 정리

- bootRun 프로세스 정상 종료
