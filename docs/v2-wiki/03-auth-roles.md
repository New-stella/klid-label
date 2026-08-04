# 03. 인증 · 권한 · 역할

> 출처: CLAUDE.md(인증·진입, 역할 정의), R2 §4(액터), 코드(`auth/`, `user/`)
> 관련: [04 화면·IA](04-screens-ia.md) · [16 포털](16-portal.md)

## 3.1 인증·진입 (독립 로그인 없음)

- 저작도구는 **독립 로그인 UI 없음** — 관제서버(내부)/포털 서버(외부)가 발급한 **JWT 토큰을 인계**받는다.
- 관제서버와 **동일 도메인 운영** → 브라우저 스토리지(localStorage/sessionStorage) 공유로 JWT 전달. URL 쿼리(`?token=`) 미사용.
- 두 채널 모두 **동일 JWT 발급 서버** → 단일 검증 로직(`JwtAuthenticationFilter`).
- 토큰 `channel` 클레임으로 채널(INTERNAL/PORTAL) 분기. **저작도구 인가 역할(REVIEWER/WORKER/PORTAL_USER)은 JWT `role` 클레임이 아니라 저작도구 소유 `LS_USER_ROLE`(USER_NO→역할)에서 조회**한다 — JWT는 식별·인증(sub·channel·exp·서명) 전담, 인가 역할은 LS 전담(`@PreAuthorize("hasRole('REVIEWER')")`). INTERNAL 채널은 `UserRoleResolver`(Caffeine 캐시 TTL 60s)로 LS 조회, PORTAL 채널은 `PORTAL_USER` 고정.
  - (역할 분리 리팩토링 2026-06) 실제 관제 JWT의 `role` 클레임은 관제 역할(SYSTEM_ADMIN/LEARN_MANAGER 등)이라 저작도구 역할과 무관하므로, 저작도구 인가는 LS 기준으로 일원화했다. 역할 변경 시 캐시는 트랜잭션 커밋 후(AFTER_COMMIT) evict.
- 세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트.

화면: `KLID-AT-SC-001`(세션 인계 진입 `/ingress`) → channel 클레임으로 `/portal` 또는 `/dashboard` 라우팅. `KLID-AT-SC-002`(역할 클레임 `/role-claim`) — role 미부여 시 진입.

## 3.2 역할

| 역할 | 코드 | 권한 |
|------|------|------|
| 검수자 | `REVIEWER` | 사용자 관리·시스템 설정, 작업자 배정·재배정·이력 조회, 검수 승인/반려, 증강 요청·검수, 비식별 요청·옵션 설정, 버전 비교·복구, 수정 통지 발행 |
| 라벨링 작업자 | `WORKER` | 본인 배정 영상 라벨 수정·검수 제출, AI 보조 라벨링, 라벨 버전 저장, 비식별 누락 신고(본인 배정 한정) |
| 포털 회원 | `PORTAL_USER` | (외부 채널) 데이터마트 영상 선택, 기존 라벨 확인·수정·저장, 본인 데이터 다운로드 |

> **ADMIN 폐지** — 모든 관리 권한이 REVIEWER에 통합. UI 호칭 '검수자', 관리 화면 URL `/manage/*`.

> **역할 저장·부여**: 저작도구 역할은 저작도구 소유 `LS_USER_ROLE`(USER_NO→ROLE_CD) 에 저장·조회한다 — 인가 판정의 단일 진실원이다. 구 관제 권한 매핑 테이블 2종은 참조가 0 이 된 뒤 **V165 로 삭제**됐고, 관제 사용자 마스터는 **V169 로 저작도구 소유 `LS_ACNT_USER` 로 이관**됐다(2026-08). 역할 부여/변경은 `PATCH /v1/users/{userNo}`(REVIEWER), 첫 역할 부트스트랩은 `POST /v1/auth/role-claim`(관리자 PW + rate limit). LS 역할 미배정 사용자는 보호 엔드포인트 403(fail-closed).

> **★ 역할 클레임 = REVIEWER 자가부여 개방 + 사용자 자동등록 (2026-08-04 확정, V169)**
>
> - **자가부여 가능 역할 = `WORKER`·`REVIEWER`**. 구 정책(A-ISSUE-17)은 `WORKER` 단일이었고 "REVIEWER 는 기존 REVIEWER 가 `/manage` 에서 부여" 를 전제했는데, **신규 설치에는 그 REVIEWER 가 없어** 최초 부트스트랩 경로가 없었다(운영 문서가 dev 편의 경로 `/dev/login` 을 안내하던 결함). ⚠ **잔여 위험을 사용자가 인지·수용**했다 — 관리자 공유 패스워드를 아는 사람은 누구나 REVIEWER 가 되며, **그 패스워드의 관리 수준이 시스템 전체의 권한 경계**다. "보안 강화" 명목으로 WORKER 단일로 되돌리지 말 것.
> - **유지되는 방어**: rate limit(계정+전역) · BCrypt 상수시간 비교 · 평문 패스워드 로그 금지 · INTERNAL 채널 + `role==null` 게이트 · `PORTAL_USER` 거절 · 이미 역할 보유 시 409.
> - **사용자 자동등록**: 사용자 마스터 행이 없으면 클레임 시점에 만든다. 표시 정보(`userId`·`userNm`)는 **관제가 브라우저 `localStorage` 에 넣어 준 값**을 FE 가 요청에 동봉한 것이며, **사용자 식별(`userNo`)은 JWT `sub` 에서만** 취한다(바디에 `userNo` 필드 자체가 없다 — CWE-639/915). 인계값이 없으면 필드를 생략하고 기존 값을 보존한다(하위호환). 관제가 함께 넣는 `authority` 는 **읽지 않는다** — 브라우저 저장소 문자열로 권한을 판정하면 사용자가 값을 바꿔 권한을 올릴 수 있다.

## 3.3 권한 경계 (보안)

- **IDOR 차단**: WORKER는 본인 배정 외 프레임 편집 시 403 (`LabelAccessGuard`)
- **관리 화면**: `/manage/*` (사용자·시스템 설정·프리셋) REVIEWER 전용
- **포털 격리**: 저장 시 원본 미수정, `LS_PORTAL_USER_LABEL` 별도 적재 (ADR-013)

## 3.4 사용자 관리 (REVIEWER)

화면: `KLID-AT-SC-024`(사용자 관리 `/manage/users`) — 사용자 목록·역할 설정. 코드: `user/UserController`.

## 3.5 외부 통합 정책

- **외부 시스템 양방향 통합(M2M) deprecated** — 관제/외부 학습데이터 시스템 송수신 API 및 M2M 인증 인프라 미운영.
- **예외 — 단방향 outbound 통지**: 저작도구 → 관제서버 `TASK_COMPLETED`/`TASK_MODIFIED`는 보유. 인계 토큰 또는 IP 화이트리스트로 보호 → [15](15-control-notify.md).

## 3.6 액터 (R2 §4)

| 액터 | 유형 |
|------|------|
| REVIEWER, WORKER, PORTAL_USER | 주요 |
| 배치시스템(Quartz), ai-server(YOLO/SAM2) | 보조(내부) |
| 생성형AI서비스, 관제서버, 비식별솔루션, 외부 비식별 SW | 보조(외부) |

> ai-server는 **외부가 아니라 저작도구 내부 추론 서버**(운영팀 직접 운영). 외부 액터와 구분 → [19](19-external-security-cvat.md).
