# 20. 게시판 (공지 · 가이드라인)

> 출처: 코드(`backend/src/main/java/kr/co/cudo/authoring/notice/`, `frontend/src/features/notice/`), V56 마이그레이션
> 관련: [03 인증·권한](03-auth-roles.md) · [04 화면·IA](04-screens-ia.md) · [18 데이터베이스](18-database.md)
> **성격**: **R1 요구사항(SFR-06~09) 외 추가 결정** (2026-06-05) — v1 게시판([v1-wiki 13](../v1-wiki/13-board-practice.md), `LS_NTC_BBS`)의 v2 재구현. 의사결정 기록: [v1-wiki 19 갭 체크리스트](../v1-wiki/19-v2-gap-checklist.md).

## 20.1 개요

REVIEWER가 라벨링 작업자에게 **공지/가이드라인을 게시·배포**하고, WORKER가 열람·첨부 다운로드하는 내부 채널 전용 게시판.

| 항목 | 내용 |
|------|------|
| 포함 | 공지 CRUD, 임시저장(DRAFT)/발행(PUBLISHED), 상단 고정(PIN), 제목/내용 검색, 페이징, 첨부파일 |
| 제외 | 댓글, 조회수, 카테고리, **포털 채널 노출 안 함**(PORTAL_USER 접근 차단) |

## 20.2 권한 모델

| 역할 | 권한 |
|------|------|
| REVIEWER | 작성·수정·삭제·발행·발행취소·첨부 업로드/삭제 + 전체(DRAFT 포함) 열람 |
| WORKER | **PUBLISHED 글만** 열람·첨부 다운로드 (DRAFT는 목록 제외 + 상세 404) |
| PORTAL_USER | 접근 불가 — `SecurityConfig`의 `/v1/notices/**` → `hasAnyRole(REVIEWER, WORKER)` 매처(`/v1/**`보다 우선)로 403 |

- 쓰기 API는 메서드 보안 `@PreAuthorize("hasRole('REVIEWER')")` 이중 방어
- WORKER의 DRAFT 상세 조회는 403이 아닌 **404** — DRAFT 존재 자체를 노출하지 않음

## 20.3 발행 상태 전이

```
작성 → DRAFT (기본)
DRAFT --publish()--> PUBLISHED (PUB_DT=now)
PUBLISHED --unpublish()--> DRAFT (PUB_DT=null)
```

- publish/unpublish는 **멱등** — 이미 해당 상태면 no-op (재발행해도 PUB_DT 불변)
- 목록 정렬: **고정(PIN_YN) 우선 → 최신순 → PK** (`PIN_YN DESC, REG_DT DESC, NOTICE_SN DESC` — 결정론적 페이징)

## 20.4 API (`/v1/notices`)

| 메서드 | 경로 | 권한 | 설명 |
|--------|------|------|------|
| GET | `/v1/notices?page&size&field&keyword` | REVIEWER/WORKER | 목록 (WORKER=PUBLISHED만). field=`TITLE/CONTENT/ALL` enum 화이트리스트 |
| GET | `/v1/notices/{id}` | REVIEWER/WORKER | 상세 (attachments 포함, WORKER의 DRAFT는 404) |
| POST | `/v1/notices` | REVIEWER | 작성 (기본 DRAFT) → 201 |
| PUT | `/v1/notices/{id}` | REVIEWER | 수정 |
| DELETE | `/v1/notices/{id}` | REVIEWER | 삭제 (첨부 DB cascade + 물리 파일 정리) → 204 |
| POST | `/v1/notices/{id}/publish` · `/unpublish` | REVIEWER | 발행/발행취소 (멱등) |
| POST | `/v1/notices/{id}/attachments` (multipart) | REVIEWER | 첨부 업로드 → 201 |
| GET | `/v1/notices/{id}/attachments/{attachId}/download` | REVIEWER/WORKER | 다운로드 (Content-Disposition 원본명, RFC 5987 한글 지원) |
| DELETE | `/v1/notices/{id}/attachments/{attachId}` | REVIEWER | 첨부 삭제 → 204 |

검증: title `@NotBlank @Size(max=200)`, content `@NotBlank`, keyword `@Size(max=100)`, page `@Min(0)`, size `@Min(1) @Max(100)`. 검색 keyword는 `% _ \` LIKE 이스케이프.

## 20.5 첨부파일 보안

저장 경로: `STORAGE_RAW_PATH/notice-attach/` (신규 env 없음).

| 가드 | 내용 |
|------|------|
| 확장자 allowlist (CWE-434) | `pdf,doc,docx,xls,xlsx,ppt,pptx,png,jpg,jpeg,zip,hwp,hwpx` — 소문자 비교, 확장자 없음/빈 파일/255자 초과 원본명 거부 |
| 크기 상한 | 20MB → 413 `PAYLOAD_TOO_LARGE` |
| 경로 순회 (CWE-22) | UUID 재명명 + `Path.normalize()` + 기준경로 `startsWith` 검증 (업로드·다운로드·삭제 전부) |
| IDOR | `findByAttachSnAndNoticeSn` 소속 검증 — 불일치 404 |
| 경로 노출 (CWE-209) | 응답 DTO에 FILE_PATH/저장파일명 미포함 |
| 로그 (CWE-117) | 원본 파일명 로그 미출력 (attachSn/확장자/크기만) |
| 가시성 일관 | DRAFT 공지의 첨부는 WORKER 다운로드 404 |

**파일-DB 정합 정책**: 업로드는 파일 쓰기 후 트랜잭션 롤백 시 `afterCompletion(ROLLED_BACK)`에서 고아 파일 정리. 삭제는 **DB 먼저 → 커밋 후 `afterCommit()`에서 물리 파일 삭제** (파일 삭제 실패는 warn 로그, 고아 파일은 수동/배치 정리).

## 20.6 테이블 (V56)

| 테이블 | 주요 컬럼 |
|--------|----------|
| `LS_NOTICE` | NOTICE_SN(PK), NOTICE_TITLE(200), NOTICE_CN(TEXT), PIN_YN, PUB_STTS_CD(DRAFT/PUBLISHED), PUB_DT, REG_ID/REG_DT, MDFR_ID/MDFCN_DT. INDEX(PUB_STTS_CD, PIN_YN, REG_DT DESC) |
| `LS_NOTICE_ATTACH` | ATTACH_SN(PK), NOTICE_SN(FK ON DELETE CASCADE), ORGNL_FILE_NM, STORE_FILE_NM(UUID), FILE_PATH, FILE_SIZE, REG_DT |

## 20.7 화면 (FE)

| 화면 ID | 화면 | 라우트 | 비고 |
|---------|------|--------|------|
| KLID-AT-SC-030 | 게시판 목록 | `/notice` | 검색(field+keyword)·페이징·고정 배지·발행상태 배지(REVIEWER만). 검색/페이지 상태는 URL searchParams |
| KLID-AT-SC-031 | 게시판 상세 | `/notice/:id` | 본문 + 첨부 다운로드 + REVIEWER 수정/발행/삭제 버튼 |
| KLID-AT-SC-032 | 작성/수정 모달 | (031 내) | zod 검증(제목 200자·필수), 첨부 업로드는 수정 모드에서만 (id 선행 필요) |

- FE 구조: `features/notice/{api,types,schemas,hooks,components}` + `NOTICE_KEYS` Query Key Factory
- 라우트는 InternalRoute(내부 채널 전체 역할) — LNB '게시판' 메뉴 전체 내부 역할 노출
- REVIEWER 전용 액션은 FE 역할 분기로 UI 차단 + BE `@PreAuthorize` 이중 방어

## 20.8 v1 대비

| 항목 | v1 (`LS_NTC_BBS`) | v2 |
|------|-------------------|-----|
| 상태 | 게시 여부 | DRAFT/PUBLISHED 명시 전이 + 멱등 발행 |
| 고정 | 상단 고정 | PIN_YN (정렬 1순위) |
| 첨부 | 첨부 지원 | allowlist + UUID + 경로검증 강화 |
| 권한 | 관리자 작성 | REVIEWER 작성 / WORKER 열람 (역할 단일화 정합) |
| 연습장 | 게시판과 세트 | **미구현** (별도 검토 후보 잔존) |
