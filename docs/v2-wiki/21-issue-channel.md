# 21. 이슈 소통 채널 (검수자↔작업자)

> 출처: 코드(`backend/src/main/java/kr/co/cudo/authoring/review/`, `frontend/src/features/review/`), V57 마이그레이션
> 관련: [12 검수·작업 배정](12-review-assignment.md) · [03 인증·권한](03-auth-roles.md) · [18 데이터베이스](18-database.md)
> **성격**: **R1 요구사항 외 추가 결정** (2026-06-05) — v1 "관리자 확인 요청/이슈 탭"([v1-wiki 09](../v1-wiki/09-review-workflow.md))의 v2 재구현. 의사결정 기록: [v1-wiki 19 갭 체크리스트](../v1-wiki/19-v2-gap-checklist.md).

## 21.1 개요

검수 반려(단방향 사유 전달)만 있던 구조에 **WORKER가 작업 중 문의(INQUIRY)를 등록하고 REVIEWER가 답변·해소하는 양방향 스레드**를 추가. 반려(REJECTION) 이력과 문의가 **영상 단위 통합 스레드**로 조회된다.

| 항목 | 내용 |
|------|------|
| 포함 | 문의 등록(영상·프레임 단위), 댓글 스레드, 상태 전이(OPEN/ANSWERED/RESOLVED), 반려+문의 통합 조회, 미해소 카운트 배지 |
| 제외 | 푸시/이메일 알림(배지까지만), 관제 통지 연계(내부 소통 전용), 첨부파일, **포털 채널 노출 안 함**(PORTAL_USER 차단) |

## 21.2 데이터 모델 (V57)

| 테이블 | 내용 |
|--------|------|
| `LS_DATA_ISSUE` (확장) | +`ISSUE_TYPE_CD`(REJECTION/INQUIRY) +`ISSUE_STTS_CD`(OPEN/ANSWERED/RESOLVED) +`SRC_SN`(프레임 단위 문의, nullable, partial index) +`VERSION`(낙관적 잠금). 기존 반려 행은 backfill로 REJECTION/RESOLVED. 코드값 CHECK 제약 |
| `LS_ISSUE_COMMENT` (신규) | CMNT_SN(PK, V90 rename 구 ISSUE_COMMENT_SN), DATA_ISSUE_SN, AUTHOR_NO, AUTHOR_ROLE_CD(WORKER/REVIEWER, CHECK), CMNT_CN(1000자), REG_DT. INDEX(DATA_ISSUE_SN, AUTHOR_NO) |

- 마이그레이션은 **3단계**(nullable ADD → backfill → NOT NULL+DEFAULT)로 롤링 중 기존 reject 경로 무중단
- `작성자(AUTHOR_NO/REPORTED_USER_NO)·역할은 요청 바디가 아닌 JWT 클레임에서만 도출` (CWE-915 Mass Assignment 방어)

## 21.3 상태 머신

```
INQUIRY: 등록 → OPEN --REVIEWER 댓글(자동)--> ANSWERED --resolve()--> RESOLVED
REJECTION: 생성 시점부터 RESOLVED 고정 (이력 성격 — 상태 전이 비대상)
```

- 역행 금지·resolve 멱등 (이미 RESOLVED면 no-op 200)
- **RESOLVED 문의에 댓글 → 409**. 단 **REJECTION은 RESOLVED여도 댓글 허용** (반려 사유에 대한 후속 문답 — 상태 전이만 없음)
- 동시 답변/해소 충돌은 `@Version` 낙관적 잠금 → 409 (FE는 안내 후 재조회)

## 21.4 권한 모델 (접근 이원화)

| 동작 | WORKER | REVIEWER |
|------|--------|----------|
| 문의 등록 | **현재 배정** 영상만 (`LS_TASK_ALTMNT` LABELER 본인 row) | 허용 |
| 스레드 조회 | 현재 배정 **또는** 본인 작성 이슈가 있는 영상 | 전체 |
| 댓글 | 해당 영상 현재 배정 **또는** 이슈 작성자 본인 | 허용 (INQUIRY OPEN이면 ANSWERED 자동 전이) |
| 해소(resolve) | 불가 (403) | 전용 |

- **생성=배정 기반 / 조회·댓글=배정 OR 작성자 소유** — 재배정 후에도 이전 작업자는 본인 문의를 계속 열람·문답 가능, 새 배정 작업자는 기존 반려 스레드에 질문 가능 (명문화된 정책)
- `issueSn`만 받는 API(댓글/해소)는 이슈→`DATA_RAW_SN` 역참조 후 권한 재검증 (CWE-639 IDOR 방어)
- 프레임 단위 문의의 `srcSn`은 대상 영상 소속 검증 (타 영상 프레임 400, 부존재 404)

## 21.5 API (`/v1`)

| 메서드 | 경로 | 권한 | 설명 |
|--------|------|------|------|
| POST | `/v1/videos/{rawSn}/issues` | WORKER(배정)/REVIEWER | 문의 등록 `{content≤1000, srcSn?}` → 201 |
| GET | `/v1/videos/{rawSn}/issues` | 21.4 모델 | 반려+문의 통합 스레드 (comments 포함, REG_DT asc) |
| POST | `/v1/issues/{issueSn}/comments` | 21.4 모델 | 댓글 `{content≤1000}` → 201 |
| POST | `/v1/issues/{issueSn}/resolve` | REVIEWER | 해소 (멱등) → 200. 액션 서브리소스는 [20 게시판](20-notice-board.md) publish 선례 |

- 기존 `GET /v1/reviews/{videoId}/issues`·`POST /v1/reviews/{videoId}/reject`는 **무변경** (반려 생성은 기존 플로우 유지)
- content는 `@NotBlank @Size(max=1000)` + **C0 제어문자·U+007F 차단** `@Pattern` (CWE-117/79 예방, 개행·탭 허용)
- 응답의 `authorNo`/`reportedUserNo` + **작성자 이름 `authorName`/`reportedUserName`** 은 내부 도구(WORKER/REVIEWER) 전용 의도 노출 — 포털 DTO 재사용 금지(실명 노출)
- 이름은 사용자 마스터(`LS_ACNT_USER.USER_NM`, V169 이관)를 **스레드당 배치 조회 1회**(`findByUserNoIn`)로 해석한다 — 댓글 건별 조회(N+1) 금지
- 사번이 숫자가 아니거나 마스터에 없으면 이름은 **null** 이고 **예외를 던지지 않는다** — 작성자가 삭제·변경돼도 스레드 조회는 살아 있어야 한다(화면은 사번으로 폴백)

## 21.6 화면 (FE)

| 화면 | 위치 | 모드 | 내용 |
|------|------|------|------|
| 라벨링 화면 | `/label/:id` 우측 패널 "이슈" 탭 | worker | 문의 등록 폼 + 스레드 + **미해소 문의 카운트 배지**(INQUIRY && !RESOLVED 클라 계산). `videoId` 부재 시 탭 비노출 |
| 검수 화면 | `/review/:id` 사이드 섹션 | reviewer | 통합 스레드 + 댓글 + 해소 버튼 (기존 반려 모달 무변경) |

- 구조: `features/review/` — `useIssueThreads` 훅(쿼리+mutation 3종, 409 시 훅 레벨 invalidate), `IssueThreadPanel`(모드 분기), `issueLabels`/`formatDateTime` 공용
- 상태 전이는 클라 계산 없이 **서버 응답 신뢰**(mutation 후 invalidate)
- 본문 렌더는 텍스트 노드만(`whitespace-pre-wrap`) — `dangerouslySetInnerHTML` 금지, `<script>` 입력 무해화 테스트 보유
- 작성자 표기는 **`{이름} ({역할})`** (`issueAuthorLabel`) — 역할은 한글(`WORKER`→작업자, `REVIEWER`→검수자, 미매핑 코드는 원문 폴백)이며 **코드값을 화면에 그대로 노출하지 않는다**. 이름이 없으면 **사번으로 폴백**(빈칸 금지). 스레드 헤더에도 작성자를 같은 폴백 규칙으로 표시

## 21.7 v1 대비

| 항목 | v1 (이슈 탭) | v2 |
|------|-------------|-----|
| 소통 방향 | 관리자 확인 요청/폐기 + 이슈 탭 | INQUIRY 스레드 (등록→답변→해소 상태 머신) |
| 반려와 관계 | 별도 | **통합 스레드** (REJECTION+INQUIRY 단일 조회) |
| 폐기 요청 | 있음 | 미포함 (검수 반려 플로우로 수렴) |
| 단위 | 프로젝트 중심 | 영상(RAW_SN) + 선택적 프레임(SRC_SN) |
