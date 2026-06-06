# 04. 화면 · IA

> 출처: D2 사용자인터페이스설계서(KLID-AT-SC 체계), 코드(`frontend/src/` 라우트)
> 관련: 각 기능 페이지

## 4.1 채널 구조

- **내부 채널 (INTERNAL)**: `/` 하위 + AppLayout(LNB+GNB). 관제서버 JWT.
- **포털 채널 (PORTAL)**: `/portal` 하위 + PortalLayout(모바일 친화, LNB 없음). 포털 JWT. → [16](16-portal.md)

화면 ID 체계: **`KLID-AT-SC-NNN`** (SC=Screen). 활성 26개(deprecated 3 제외).

## 4.2 내부 채널 화면

| 화면 ID | 화면명 | 라우트 | 권한 | 위키 |
|---------|-------|--------|------|------|
| SC-001 | 세션 인계 진입 | `/ingress` | 전체 | [03](03-auth-roles.md) |
| SC-002 | 역할 클레임 | `/role-claim` | 전체 | [03](03-auth-roles.md) |
| SC-005 | 라벨링 캔버스 | `/label/:id` | WORKER/REVIEWER | [10](10-labeling.md) |
| SC-006 | 마킹 | `/marking/:rawSn` | WORKER/REVIEWER | [06](06-marking.md) |
| SC-007 | 영상 목록 | `/video/completed` | - | [05](05-video-management.md) |
| SC-008 | 영상 처리 현황 | `/video/status` | - | [05](05-video-management.md) |
| SC-009 | 영상 상세 | `/video/:id` | - | [05](05-video-management.md) |
| SC-010 | 라벨 이력(버전) | `/history/:videoId` | - | [13](13-version-control.md) |
| SC-011 | 대시보드 | `/dashboard` | - | [17](17-statistics.md) |
| SC-012 | 작업 목록 | `/task` | - | [12](12-review-assignment.md) |
| SC-013 | 작업 배정 | `/task/assign` | REVIEWER | [12](12-review-assignment.md) |
| SC-014 | 오토라벨 요약 | `/auto/:videoId` | - | [11](11-ai-assisted.md) |
| SC-015 | VLM 메타 검토 | `/auto/:videoId/meta` | - | [09](09-vlm-timeseries.md) |
| SC-018 | 검수 목록 | `/review` | REVIEWER | [12](12-review-assignment.md) |
| SC-019 | 검수 상세 | `/review/:id` | REVIEWER | [12](12-review-assignment.md) |
| SC-020 | 작업자 통계 | `/stat` | - | [17](17-statistics.md) |
| SC-021 | 전체 통계 | `/stat/overall` | REVIEWER | [17](17-statistics.md) |
| SC-022 | 증강 요청 | `/augment` | REVIEWER | [14](14-augmentation.md) |
| SC-023 | 증강 결과 | `/augment/result/:jobId` | REVIEWER | [14](14-augmentation.md) |
| SC-024 | 사용자 관리 | `/manage/users` | REVIEWER | [03](03-auth-roles.md) |
| SC-025 | 시스템 설정 | `/manage/settings` | REVIEWER | [10](10-labeling.md#정밀도-설정) |
| SC-026 | 프리셋 관리 | `/manage/presets` | REVIEWER | [10](10-labeling.md#라벨-프리셋) |
| SC-033 | 비식별 신고 관리 | `/manage/deident-reports` | REVIEWER | [10](10-labeling.md) |
| SC-030 | 게시판 목록 | `/notice` | WORKER/REVIEWER | [20](20-notice-board.md) |
| SC-031 | 게시판 상세 | `/notice/:id` | WORKER/REVIEWER | [20](20-notice-board.md) |
| SC-032 | 게시판 작성/수정 (모달) | (SC-030/031 내) | REVIEWER | [20](20-notice-board.md) |

개발 전용: `/dev/login`(SC-004), `/dev/autolabel-test`(SC-027) — DEV 빌드만.

> SC-030~032(게시판)는 **R1 요구사항 외 추가 결정**(2026-06-05) — [20 게시판](20-notice-board.md) 참고.

## 4.3 포털 채널 화면

| 화면 ID | 화면명 | 라우트 |
|---------|-------|--------|
| (홈) | 데이터마트 영상 선택 | `/portal` |
| SC-029 | 포털 라벨링 | `/portal/label/:id` |

→ [16 포털](16-portal.md)

## 4.4 deprecated 화면

| 화면 ID | 사유 |
|---------|------|
| SC-016 / SC-017 | 비식별 목록/상세 → 외부 비식별 솔루션 검토화면으로 이관 |
| SC-028 | 포털 홈 → ADR-013(데이터마트 영상 선택 전용으로 재정의) |

## 4.5 화면 ID 외 식별 체계

| 체계 | 의미 | 문서 |
|------|------|------|
| `KLID-AT-SC-*` | 화면(Screen) | D2 |
| `KLID-AT-UC-*` | 유스케이스 | R2 |
| `KLID-AT-CO-*` | 컴포넌트 | D3 |
| `KLID-AT-CL-*` | 클래스 | D1 |
| `KLID-AT-SS-*` | 서브시스템(10개) | R2 |
| `RQ-SFR-NN-NN` | 요구사항 | R1 |

→ [19 설계 문서 카탈로그](19-external-security-cvat.md#설계-문서-카탈로그)
