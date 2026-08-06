# F 클러스터 part2 (F-5~F-7) 2차 검증 결과

> 대상: `docs/test-cases/F-portal.md` 의 F-5(18) · F-6(13) · F-7(19) = **50건**
> 환경: 로컬 도커 스택 `localhost:18081`(backend 이미지 = HEAD `ca3c712b` 재빌드본, `SPRING_PROFILES_ACTIVE=local`) · PG `klid-postgres/klid_system`(스키마 `public`)
> 실행일 2026-07-31 · 폐기(`~~취소선~~`) 행 **0건**(F-5~F-7 구간에는 폐기 케이스가 없다)
> 대전제 준수: 포털 업로드 경로는 외부 연동이 없는 자기완결 경로(ADR-013 분리)라 목서버 경유 대상 자체가 없음 — 대신 **실제 파일을 실제로 업로드/서빙/삭제**해 DB 행 + 디스크 파일 양쪽으로 확증. self-fill 0건.

## 집계

| 구분 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|------|---:|---:|---:|---:|---:|---:|---:|
| F-5 포털 이미지 업로드 | 18 | 17 | 1 | 0 | 0 | 0 | 0 |
| F-6 자산 조회/서빙/삭제 | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| F-7 업로드 라벨 CRUD | 19 | 19 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **50** | **49** | **1** | 0 | 0 | 0 | 0 |

- 이슈 4건 — HIGH 1(A-ISSUE-21 중복) / MEDIUM 1 / LOW 2
- **실동작 검증 비중**: 50건 중 48건을 실제 HTTP 왕복으로 확증. 정적 전용은 TC-PORTALUP-007·008(prd 프로파일 한정 동작, local 스택에서 재현 불가) 2건.
- 근거 드리프트 **43건**(카탈로그 `file:line` 이 현재 소스와 어긋남). 특히 F-7 표 하단 각주 *"PortalUploadLabelService/Controller 는 2026-07-25 이후 무변경 — 라인 재확인만 하고 값은 유지"* 는 **사실과 다르다**(19건 중 18건 드리프트).

### 검증 중 생성/변조한 데이터 (타 에이전트 영향 없음)

- `ls_portal_uld` 120행 / `ls_portal_uld_frme` 120행 신규 생성(포털 사용자 `3001`·`3002`·`3009`). 전부 `LS_PORTAL_*` 전용 테이블이며 **내부 파이프라인(`ls_data_raw`/`ls_data_src`)·데이터마트 뷰와 완전 분리**돼 있어 타 클러스터 검증에 간섭하지 않음.
- **`rawSn` 126·133 무손상 확인** — 검증 종료 시점 `126=COMPLETED/de_ident_yn=Y`, `133=COMPLETED/de_ident_yn=F` 그대로. 포털 업로드 경로는 이 테이블을 건드리지 않는다.
- 검증용으로 만든 root 소유 `lockdir`·심링크(`evil-link.jpeg`)는 **전량 제거 완료**(스토리지 루트에 `images/` 만 잔존).

---

## ★파일 검증 우회 실측

`POST /v1/portal/uploads/images` (PORTAL_USER `3001`). "저장여부" = 요청 후 `/app/storage/raw/portal/images` 파일 수 증가 + `ls_portal_uld` 행 증가 여부.

| # | 공격입력 | 기대 | 실측 | 저장여부 | 차단 지점 |
|--:|---------|------|------|:---:|------|
| 1 | `fake.jpg` — 내용은 `#!/bin/sh` 셸 스크립트 | 400 | **400** `지원하지 않는 이미지 형식입니다(JPEG/PNG 만 허용).` | 미저장 | 매직바이트 `detect()` |
| 2 | `elf.jpg` — `\x7fELF` 헤더(ELF 바이너리) | 400 | **400** 동일 | 미저장 | 매직바이트 |
| 3 | `mismatch.png` — 실제 JPEG 바이트에 `.png` 확장자 | 400 | **400** `확장자와 실제 이미지 형식이 일치하지 않습니다.` | 미저장 | `matchesExtension` |
| 4 | `trunc.jpg` — JPEG SOI 만 있고 EOI(`FF D9`) 없음 | 400 | **400** `손상되었거나 완전하지 않은 이미지 파일입니다.` | 미저장 | `endsWithJpegEoi` (tail 판독) |
| 5 | `anim.gif` — GIF89a | 400 | **400** `허용되지 않는 확장자입니다. 허용: [jpg, jpeg, png]` | 미저장 | 확장자 allowlist |
| 6 | `x.svg` — `<svg onload=alert(1)>` (저장형 XSS 벡터) | 400 | **400** 동일 | 미저장 | 확장자 allowlist |
| 7 | `empty.jpg` — 0바이트 | 400 | **400** `빈 파일입니다.` | 미저장 | `file.isEmpty()` |
| 8 | MIME 헤더 위조 — 스크립트 본문에 `Content-Type: image/jpeg` 파트 헤더 부착 | 400 | **400** (요청 MIME 무시, 매직바이트로 판정) | 미저장 | 매직바이트 |
| 9 | 이중 확장자 `a.php.jpg` (본문은 정상 JPEG) | 201(정상 취급) | **201**, 디스크 저장명 `88c7cf67-….jpeg` | 저장(UUID) | 해당없음 — **최종 확장자만 해석 + UUID 강제라 무해** |
| 10 | 대문자 확장자 `UPPER.JPG` | 201 | **201**, 저장명 `db751adc-….jpeg` | 저장(UUID) | `toLowerCase(Locale.ROOT)` 정규화 |
| 11 | 경로순회 파일명 `../../../../etc/passwd.jpg` | UUID 저장 | **201**, `file_path_nm=/app/storage/raw/portal/images/6d04121c-….jpeg` | 저장(UUID) | 저장명 UUID 강제 — 원본명은 표시용 컬럼에만 |
| 12 | 널바이트 파일명 `evil.jpg\0.php` | UUID 저장 | **201**, `orgnl_file_nm='evil.jpg'`(널 이후 절단), 저장명 UUID | 저장(UUID) | 동상 |
| 13 | 유니코드 우회 `x.jpg%E2%80%8B`(zero-width space 부착) | 400 | **400** `허용되지 않는 확장자입니다` | 미저장 | `^[a-z0-9]{1,8}$` 확장자 정규식 |
| 14 | 21MB JPEG (20MB 상한 +1MB) | 400 | **400** `이미지 크기가 허용 한도를 초과했습니다.` | 미저장 | `maxImageSizeBytes` |
| 15 | 22MB JPEG (카탈로그 TC-007 입력) | local: 파싱 통과 후 서비스 400 | **400** (413 아님 — local multipart 한도 500MB) | 미저장 | `maxImageSizeBytes` |
| 16 | 정확히 20,971,520B (경계값) | 201 | **201** | 저장 | — |
| 17 | 51장 | 400 | **400** `요청당 최대 50 개까지 업로드할 수 있습니다.` | 미저장 | `maxImagesPerRequest` |
| 18 | 50장 (경계값) | 201 | **201**, 50건 저장 | 저장 | — |
| 19 | `files` 파트 자체 미첨부 | 400 | **500** `INTERNAL_ERROR` + `MissingServletRequestPartException` **전체 스택트레이스** | 미저장 | ⚠ **핸들러 부재 → `Exception.class` 종착** (F-ISSUE-21) |
| 20 | XSS 파일명 `<script>alert(1)</script>.jpg` | 201 + 이스케이프 | **201**, JSON 문자열로 그대로 직렬화(HTML 렌더 없음, FE 이스케이프 책임 — 코드 주석과 일치) | 저장(UUID) | 해당없음 |
| 21 | 이모지 200개 파일명(surrogate pair, 404 UTF-16 unit) | 255자 절단 | **201**, DB 저장값 128자 — **말미 surrogate 가 반쪽만 남아 `?`(U+FFFD 치환) 로 저장** | 저장 | ⚠ F-ISSUE-22 (LOW) |

**결론**: 매직바이트 · 확장자 allowlist · 확장자↔시그니처 정합 · JPEG EOI · 크기 · 개수 · 저장명 UUID 강제까지 **7중 방어가 모두 실효**하며 우회 성공 0건. 유일한 결함은 `files` 파트 누락 시 응답코드/로그(F-ISSUE-21).

---

## ★IDOR 실측

포털 사용자 `3001`(자산 uldSn 1~118 / frmeSn 1~118) 이 **포털 사용자 `3002` 의 자산**(uldSn 119·120, frmeSn 119·120)에 접근 시도. 부재 리소스(`999999`)와 **응답이 동일한지**(자원 열거 차단)까지 확인.

| # | 경로 | 타인 자산 | 기대 | 실측 |
|--:|------|------|------|------|
| 1 | `GET /v1/portal/uploads/{uldSn}` | 119 | 403 | **403** `본인 자산이 아니거나 존재하지 않습니다.` |
| 2 | `GET /v1/portal/uploads/999999` (부재) | — | 403(부재와 동일) | **403** — 메시지·코드 완전 동일(열거 불가) |
| 3 | `GET /v1/portal/uploads/{uldSn}/frames` | 119 | 403 | **403** |
| 4 | `GET /v1/portal/uploads/frames/{uldFrmeSn}/image` | 119 | 403 | **403** |
| 5 | `DELETE /v1/portal/uploads/{uldSn}` | 119 | 403 | **403** (행·파일 무손상 확인) |
| 6 | `PUT /v1/portal/uploads/frames/{uldFrmeSn}/labels` | 119 | 403 | **403** |
| 7 | `GET /v1/portal/uploads/frames/{uldFrmeSn}/labels` | 119 | 403 | **403** |
| 8 | `GET /v1/portal/uploads/frames/999999/labels` (부재) | — | 403 | **403** — 동일 응답 |
| 9 | `GET /v1/portal/uploads/{uldSn}/export` | 119 | 403 | **403** |
| 10 | `GET /v1/portal/uploads/{uldSn}/file` | 119 | 403 | **403** |

**사용자 식별 출처**: 전 엔드포인트가 `@AuthenticationPrincipal TokenClaims actor` → `actor.sub()` 로 **토큰에서만** 소유자를 얻는다(`PortalUploadController:138-143`, `PortalUploadLabelController:101-106`). **요청 파라미터·헤더로 사용자를 지정하는 경로 0건**(grep 확증). 서비스는 전부 소유자 스코프 리포지토리(`findByUldSnAndPortalUserNo` / `findByUldFrmeSnAndOwner` / `deleteAllByUldFrmeSnAndPortalUserNo`)만 호출한다.

### 채널 격리 실측 (내부 토큰 → 포털 업로드 경로)

| 토큰 | 경로 | 실측 |
|------|------|------|
| WORKER(INTERNAL, sub=2001) | `POST /images` · `GET /` · `GET /frames/{n}/image` · `PUT …/labels` · `GET …/export` · `GET …/file` · `DELETE /{n}` | **전부 403** `권한이 없습니다.` |
| REVIEWER(INTERNAL, sub=1001) | `GET /` · `GET …/export` | **전부 403** |
| 무토큰 | `GET /` | **401** `인증이 필요합니다.` |

---

## F-5 결과표 — 포털 이미지 업로드 (18건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTALUP-001 | 정상 다중 이미지 업로드 → 201, UUID·READY | PASS | `[실동작]` jpg+png 2장 → **201**, `uldSttsCd=READY`·`frmeCnt=1`·`frmeSn` 반환. DB `ls_portal_uld` 2행 + `ls_portal_uld_frme` 2행, 디스크 `a2883fa2-….jpeg`/`dcba3872-….png` 실재(133B/73B). `[정적]` `PortalUploadService.java:83-152` `[테스트]` `PortalUploadServiceTest#이미지_다중_업로드_성공시_READY상태와_파일기록`, `PortalUploadControllerTest#이미지_다중_업로드_성공시_201과_READY_상태` | `markReady(null,null,1)` 로 업로드 즉시 READY(추출 단계 없음) |
| TC-PORTALUP-002 | 이미지 수 상한(50) 초과 → 400 | PASS | `[실동작]` 51장 → **400** `요청당 최대 50 개까지 업로드할 수 있습니다.` `[정적]` `:90-93` `[테스트]` `PortalUploadServiceTest#요청당_개수_초과시_저장_0건` | |
| TC-PORTALUP-003 | 정확히 50장 경계 → 성공 | PASS | `[실동작]` 50장 → **201**, 응답 배열 50건 + DB 50행 | |
| TC-PORTALUP-004 | 빈 목록 → 400 | FAIL | `[실동작]` `files` 파트 미첨부 → **500** `INTERNAL_ERROR`. 로그: `MissingServletRequestPartException: Required part 'files' is not present.` + 전체 스택 `[정적]` 서비스 분기 `:87-89` 는 존재하나 `@RequestParam("files")`(required) 가 선행 차단해 **HTTP 로는 도달 불가(dead branch)** | **F-ISSUE-21** — A-ISSUE-21 과 동일 원인·동일 재현 경로(집계 시 병합 권장). 참고로 *빈 파트*(`filename=` 공백) 는 400 `빈 파일입니다.` 로 정상 |
| TC-PORTALUP-005 | 개당 20MB 초과 → 400 | PASS | `[실동작]` 21MB·22MB 두 건 모두 **400** `이미지 크기가 허용 한도를 초과했습니다.` `[정적]` `:300-303`, `PortalUploadProperties.java:40`(`@DefaultValue("20971520")`), `application.yml:438` | 프로파일 무관 서비스 상수 확인 |
| TC-PORTALUP-006 | 20MB 경계값 → 성공 | PASS | `[실동작]` 정확히 20,971,520B JPEG → **201**, `fileSz=20971520` | |
| TC-PORTALUP-007 | multipart max-file-size 초과 → 413(prd 한정) | PASS | `[실동작]` local 22MB → 파싱 통과 후 **서비스 400**(413 아님) — 카탈로그 정정 서술과 일치 `[정적]` `application-prd.yml:36-39` `21MB/1100MB`, `application.yml:14-30` 공통 `500MB/1200MB` `[테스트]` `ConfigProfileDriftGuardTest#prdMultipartLimitsStayTight`(`:43-58`), `#nonProdMultipartLimitsComeFromCommon`(`:67-80`) | prd 413 자체는 **실동작 미실증** — 실행 스택이 `SPRING_PROFILES_ACTIVE=local`. 프로파일 전환은 backend 재기동을 요구해 금지 지시에 따라 미수행 |
| TC-PORTALUP-008 | multipart max-request-size 초과 → 거부(prd 한정) | PASS | `[정적]` `application-prd.yml:39` `1100MB` / 공통 `1200MB`, 산식(50×21MB=1050MB+헤드룸) 주석 일치 `[테스트]` 동상 | **실동작 미수행** — 1.2GB 전송은 공유 스택 임시파일 스풀에 부하를 주어 동시 검증 중인 타 에이전트를 위협(A-part3 TC-EXC-016 과 동일 판단) |
| TC-PORTALUP-009 | 확장자 allowlist 밖(gif/svg) → 400 | PASS | `[실동작]` `.gif`·`.svg` 둘 다 **400** `허용되지 않는 확장자입니다. 허용: [jpg, jpeg, png]` `[정적]` `:304-309` `[테스트]` `ImageMagicByteValidatorTest#SVG_XML_시그니처는_거부`,`#GIF_BMP_WEBP_HTML은_거부` | 확장자에서 1차, 매직바이트에서 2차 — 이중 차단 |
| TC-PORTALUP-010 | 매직바이트 미탐지 → 400 | PASS | `[실동작]` 셸 스크립트/ELF 본문 + `.jpg` → **400** `지원하지 않는 이미지 형식입니다(JPEG/PNG 만 허용).` `[정적]` `:310-315` | |
| TC-PORTALUP-011 | 확장자↔시그니처 불일치 → 400 | PASS | `[실동작]` JPEG 본문 + `.png` → **400** `확장자와 실제 이미지 형식이 일치하지 않습니다.` 요청 파트 MIME 위조(`type=image/jpeg`)도 무시됨 `[정적]` `:316-320` + `ImageMagicByteValidator#matchesExtension` | |
| TC-PORTALUP-012 | truncated JPEG(EOI 없음) → 400 | PASS | `[실동작]` SOI 만 있는 20B JPEG → **400** `손상되었거나 완전하지 않은 이미지 파일입니다.` `[정적]` `:321-327`(tail 2B 판독) `[테스트]` `#헤더만_유효한_truncated_파일_400`(Service·Controller 양쪽) | PNG 는 `detect()` 가 IHDR 청크까지 검사(별도 tail 불필요) |
| TC-PORTALUP-013 | all-or-nothing: 1장 실패 시 전체 미저장 | PASS | `[실동작]` ①정상 49 + 위조 1(**마지막**) → 400, 디스크 파일수 57→**57**(불변) ②위조 1(**첫번째**) + 정상 49 → 400, 57→**57**. 응답 `data` 에 실패 인덱스·사유만 노출(`[50] mismatch.png: …`) `[정적]` `:95-109`(디스크 쓰기 **이전** 전량 사전검증) | 위조 위치와 무관하게 0건 저장 확인 |
| TC-PORTALUP-014 | write 후 DB INSERT 실패 시 보상 삭제 | PASS | `[실동작]` `setval('ls_portal_uld_uld_sn_seq',1,false)` 로 PK 충돌 유도 → **500** `이미지 저장에 실패했습니다.`, 디스크 파일수 120→**120**(보상 삭제 성공), 기존 행 무손상. 로그 `upload failed userNo=3001 causeType=DataIntegrityViolationException` `[정적]` `:138-147` `[테스트]` `#DB_저장_실패시_기록된_파일이_남지_않음` | 시퀀스는 검증 후 원값 복원 |
| TC-PORTALUP-015 | 디스크 write IOException 시 롤백 | PASS | `[실동작]` `chmod 555 images/` → **500** `이미지 저장에 실패했습니다.`, `ls_portal_uld` 118→**118**(행 0 증가). 로그 `write failed causeType=AccessDeniedException` `[정적]` `writeToDisk :365-372` + `rollbackFiles :374-385` `[테스트]` `#다건_배치중_후행_write실패시_선행파일도_롤백` | 검증 후 권한 755 복원 |
| TC-PORTALUP-016 | 저장 파일명 UUID 강제(path traversal 없음) | PASS | `[실동작]` 파일명 `../../../../etc/passwd.jpg` · `evil.jpg\0.php` 투입 → 저장 경로 전부 `/app/storage/raw/portal/images/{UUID}.{jpeg\|png}`, 스토리지 루트 하위에 `images/` 외 디렉터리 0개 `[정적]` `:119-120`(`UUID.randomUUID()+"."+format` → `resolveSafe`) `[테스트]` `#경로조작_파일명_업로드시_저장경로가_베이스_밖으로_나가지_않음` | 확장자는 **매직바이트 확정 포맷**에서 파생(원본 확장자 미사용) |
| TC-PORTALUP-017 | 원본명 255자 초과 truncate | PASS | `[실동작]` 304자 파일명 → `orgnlFileNm` **255자**로 절단 저장(`varchar(255)` 초과 없음) `[정적]` `truncate :454-459` | ⚠ **F-ISSUE-22(LOW)** — surrogate pair 를 UTF-16 unit 기준으로 자르면 반쪽 surrogate 가 남아 `?` 로 치환됨(이모지 200개 실측: DB 128자, 말미 `ascii=63`) |
| TC-PORTALUP-018 | 업로드 per-user rate limit 초과 → 429 | PASS | `[실동작]` 신규 사용자 `3009` 로 65회 연속 업로드 → **1~60회 201 / 61~65회 429**(정확히 60에서 전환) `[정적]` `PortalUploadController.java:157-165`, `application.yml:616-619`(`limit-for-period:60`,`refresh:1m`,`timeout:0`) `[테스트]` `PortalUploadControllerRateLimitTest#업로드_요청량_초과시_429` | per-user 이름(`portalUpload-{userNo}`) 격리 — `3001` 의 잔여 예산이 `3009` 소진에 영향받지 않음도 함께 확인 |

---

## F-6 결과표 — 포털 자산 조회/서빙/삭제 (13건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTALUP-020 | 본인 자산 목록 페이징 | PASS | `[실동작]` `3001` 조회 → `totalElements=58`, `content` 전건 본인 자산. `3002` 자산(uldSn 119·120)·`3009` 자산(60건) **미노출** `[정적]` `:157-172`(`findAllByPortalUserNo`) | `@PageableDefault(size=20)` |
| TC-PORTALUP-021 | type 필터 IMAGE/VIDEO | PASS | `[실동작]` `type=IMAGE`→58건 전부 IMAGE / `type=VIDEO`→1건 전부 VIDEO(검증용 VIDEO 행 임시 삽입 후 삭제) / 무필터→59건. `type=image`(소문자)도 정상 동작(`toUpperCase` 정규화) `[정적]` `:164-171` `[테스트]` `#목록조회_type필터_IMAGE만_반환` | 필터가 실제로 **분리**함을 양방향으로 확인(빈 결과만으로 판정하지 않음) |
| TC-PORTALUP-022 | 미지원 type → 400 | PASS | `[실동작]` `?type=FOO` → **400** `지원하지 않는 type 입니다. 허용: IMAGE, VIDEO` `[정적]` `:165-169`(조용한 빈 결과 대신 명시 거부) `[테스트]` `#미지원_type_필터시_400` | |
| TC-PORTALUP-023 | 페이지 크기 하드캡(100) | PASS | `[실동작]` 목록 `?size=500` → 응답 `size=100`, `totalPages=1`. 프레임 목록 `?size=500` → `size=100` `[정적]` `PortalUploadController.java:146-151` `[테스트]` `#size_100초과시_100으로_캡` | 목록·프레임 **두 경로 모두** 확인 |
| TC-PORTALUP-024 | 타 사용자 자산 상세 → 403 | PASS | `[실동작]` `3001`→uldSn 119 **403**. 부재 `999999` 도 **동일 403·동일 메시지**(자원 열거 차단) `[정적]` `:175-182` `[테스트]` `#타사용자_자산_상세_조회시_403` | 404/403 분기가 없어 존재 여부 오라클 부재 |
| TC-PORTALUP-025 | 타 사용자 프레임 목록 → 403 | PASS | `[실동작]` **403** `[정적]` `:185-192`(소유권 사전검증 + 소유자 스코프 조인 쿼리 이중) `[테스트]` `#타사용자_프레임목록_조회시_403` | |
| TC-PORTALUP-026 | 타 사용자 프레임 이미지 → 403(IDOR) | PASS | `[실동작]` frmeSn 119 **403** / 본인 frmeSn 1 **200**(실제 JPEG 바이트 수신) `[정적]` `:205-211`(`findByUldFrmeSnAndOwner` + 상위 ULD 소유권 재확인) `[테스트]` `#타사용자_프레임_이미지_조회시_403` | |
| TC-PORTALUP-027 | 프레임 이미지 서빙: DB MIME+nosniff, 신고 게이트 대상 아님 | PASS | `[실동작]` jpg 프레임→`Content-Type: image/jpeg`, png 프레임→`image/png`, 양쪽 `X-Content-Type-Options: nosniff` + `Content-Disposition: inline; filename="frame_{n}"`. DB MIME 을 `text/html` 로 변조하면 **`application/octet-stream`** 으로 fail-closed `[정적]` `:204-236` — `DeidentReportGate`·`DE_IDNTF_YN` **참조 0건**(grep), Javadoc `:198-202` 이 비대상 사유 명시 `[테스트]` `PortalFrameImageCacheControlTest` 가 자기 스코프를 `/v1/portal/frames/{srcSn}/image`(내부 파이프라인)로 한정하고 `:48` 주석으로 `/v1/portal/uploads/frames/**` 제외를 명시 | 응답에 `Cache-Control: no-store` 가 붙지만 이는 **Spring Security 전역 기본 헤더**이며 게이트 통일 규약과 무관(엔드포인트가 스스로 부여하는 코드 없음) |
| TC-PORTALUP-028 | 이미지 서빙 Path Traversal 차단 | PASS | `[실동작]` DB `file_path_nm` 을 ①`/etc/passwd`(절대경로 base 밖) ②`…/images/../../../../etc/passwd`(`..` 조합) 으로 변조 → 둘 다 **403** `허용되지 않은 경로입니다.` ③base 안에 `/etc/passwd` 를 가리키는 **심링크** 배치 → **403** `허용되지 않은 이미지 경로입니다.`(realpath 재검증). PathVariable 에 `abc` → 400, 경로 세그먼트 조작 → 403 `[정적]` `resolveSafe :414-424` + `realWithinBase :245-257` + `FrameImageService.openNoFollow`(NOFOLLOW_LINKS) `[테스트]` `FileServingLinkFollowGuardTest` | lexical 검증 → realpath 봉쇄 → NOFOLLOW open 3단이 모두 살아 있음(CWE-22/59/367) |
| TC-PORTALUP-029 | 자산 삭제: 파일 먼저 삭제 후 DB CASCADE | PASS | `[실동작]` uldSn 3 → **204**. 삭제 전 디스크 실재 확인 → 삭제 후 `No such file`. `ls_portal_uld` 0행 · `ls_portal_uld_frme` 0행(CASCADE) `[정적]` `:265-292`(파일 → DB 순서) `[테스트]` `#삭제시_파일과_DB행이_함께_제거됨`, `#삭제시_파일과_프레임_라벨_행이_함께_제거됨` | |
| TC-PORTALUP-030 | PROCESSING 중 삭제 → 409 | PASS | `[실동작]` 상태를 `PROCESSING` 으로 두고 DELETE → **409** `프레임 추출이 진행 중인 자산은 삭제할 수 없습니다.` `[정적]` `:273-276` `[테스트]` `PortalUploadProcessingDeleteTest#PROCESSING_자산_삭제시_409` | |
| TC-PORTALUP-031 | 파일 삭제 IOException 시 DB 행 보존 5xx | PASS | `[실동작]` root 소유 `chmod 555` 디렉터리에 파일 배치 후 DELETE → **500** `파일 삭제에 실패했습니다. 잠시 후 다시 시도하세요.`, `ls_portal_uld` 행 **1건 잔존**, 파일도 잔존(원본 무유실) `[정적]` `deleteFileOrThrow :400-408` `[테스트]` `#파일삭제_실패시_DB행_보존` | 재시도 가능 상태로 정확히 남음 |
| TC-PORTALUP-032 | 타 사용자 자산 삭제 → 403 | PASS | `[실동작]` `3001`→uldSn 119 **403**, 대상 행·파일 무손상 `[정적]` `:267-269` `[테스트]` `#타사용자_삭제시_403` | |

---

## F-7 결과표 — 포털 업로드 라벨 CRUD (19건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTALUP-040 | 라벨 전체교체(PUT) 멱등, 빈 배열=전체 삭제 | PASS | `[실동작]` BBOX+POLYGON 2건 저장→GET 2건 반환. 이어서 `[]` PUT → **200** `data:[]`, DB `ls_portal_uld_lbl` **500→0**. 본문 자체를 생략(`Content-Length:0`)해도 200 + 전체 삭제(`@RequestBody(required=false)` + `labels==null → List.of()`) `[정적]` `:93-133` `[테스트]` `PortalUploadLabelServiceTest#라벨_전체교체_저장은_멱등`,`#빈_배열_PUT은_전체_삭제` | ⚠ 카탈로그 '알려진 함정'대로 **요청 본문이 raw 배열**(`[{...}]`)이며 래핑 객체가 아님 — 실측 확인 |
| TC-PORTALUP-041 | 검증 실패 시 DELETE 미실행(기존 유지) | PASS | `[실동작]` 기존 500건 보유 상태에서 `[정상1건, SEGMENT 1건]` PUT → **400**, DB 여전히 **500건** `[정적]` `:99-107`(전량 검증이 락·DELETE 이전) `[테스트]` `#검증_실패시_기존_라벨_유지` | |
| TC-PORTALUP-042 | lblTypeCd allowlist(BBOX/POLYGON)만 | PASS | `[실동작]` `SEGMENT`→**400**, `SKELETON`→**400** `[1] 허용되지 않는 lblTypeCd 입니다. 허용: BBOX, POLYGON`. 소문자 `bbox`→200(정규화 후 `BBOX` 저장) `[정적]` `:265-270` fail-closed `[테스트]` `#BBOX_POLYGON_외_타입_400`,`#소문자_타입도_정규화되어_저장` | 포털 미제공(SAM2·KEYPOINT/SKELETON) 타입이 실제로 거부됨 |
| TC-PORTALUP-043 | 라벨 배열 상한 500 초과 → 400 | PASS | `[실동작]` 501건 → **400** `replaceLabels.labels: 프레임당 라벨은 최대 500 개까지 허용됩니다.`(컨트롤러 `@Size` 경유) / 500건 → **200** `[정적]` `PortalUploadLabelController.java:63` + `PortalUploadLabelService.java:100-103` **이중 강제** `[테스트]` `#라벨_500개_초과_400` | |
| TC-PORTALUP-044 | BBOX 좌표 2점 아님 → 400 | PASS | `[실동작]` 3점 → **400** `[1] BBOX 는 정확히 2 점이어야 합니다.` `[정적]` `:281-284` `[테스트]` `#BBOX_점2개_아니면_400` | |
| TC-PORTALUP-045 | POLYGON 3~200점 경계 → 400 | PASS | `[실동작]` 2점→**400** / 201점→**400** `[1] POLYGON 은 3~200 점이어야 합니다.` / 200점→**200**(경계 통과) `[정적]` `:285-290` `[테스트]` `#POLYGON_3점미만_또는_200점초과_400` | |
| TC-PORTALUP-046 | 좌표 NaN/Infinity → 400 | PASS | `[실동작]` `"NaN"` → **400**, `1e999`(JSON→Infinity) → **400** `[1] 좌표는 유한한 숫자여야 합니다.` `[정적]` `:300-303`(`Double.isFinite`) `[테스트]` `#좌표_Infinity면_400` | |
| TC-PORTALUP-047 | label 80자 초과 → 400 | PASS | `[실동작]` 81자→**400** `replaceLabels.labels[0].label: label 은 80자 이하여야 합니다.` / 80자→**200** `[정적]` 컨트롤러 `@Valid`(DTO `@Size`) 선행 + 서비스 `:271-275` 이중 `[테스트]` `#label_80자초과_400` | 컨트롤러 검증이 먼저 걸려 메시지 형식이 서비스와 다름(기능 동일) |
| TC-PORTALUP-048 | READY 외 상태 라벨 PUT → 409 | PASS | `[실동작]` `PROCESSING`·`FAILED`·`UPLOADED` **3상태 모두 409** `라벨링 가능한(READY) 자산이 아닙니다. 현재 상태: {s}` `[정적]` `:116-119` `[테스트]` `#READY_아닌_자산_라벨링_409` | 라벨 **조회**(GET)는 비READY 에서도 200 — 서비스 코드에 조회 가드 없음(설계대로, 읽기는 상태 무관) |
| TC-PORTALUP-049 | 동시 PUT 프레임 락 직렬화 | PASS | `[실동작]` 동일 프레임에 A(BBOX×100)/B(POLYGON×100) **10요청 병렬** → 전부 200, 최종 DB **BBOX 100건 단독**(총 100). 두 요청 집합이 섞인 흔적 0 `[정적]` `:110-111` `findByUldFrmeSnAndOwnerForUpdate`(비관적 락) `[테스트]` `PortalUploadLabelConcurrencyIT#동시_PUT_경합시_최종상태는_단일_요청_집합` | 부분 병합·중복 잔존 없음 |
| TC-PORTALUP-050 | 타 사용자 프레임 라벨 PUT → 403 | PASS | `[실동작]` **403** `[정적]` `:110-111` 소유자 스코프 락 조회 `[테스트]` `#타사용자_프레임_라벨_PUT시_403` | |
| TC-PORTALUP-051 | 라벨 PUT 본문 2MB 초과 → 413(파싱 전 조기) | PASS | `[실동작]` 2,173,152B 본문 → **413** `라벨 본문은 2048KB 를 초과할 수 없습니다.`(`PAYLOAD_TOO_LARGE`). 라벨 개수는 500 이하였으므로 **개수 검증이 아니라 바디 크기 필터**가 잡은 것이 확정 `[정적]` `PortalLabelBodySizeFilter.java:70-75` `[테스트]` `PortalLabelBodySizeFilterTest#본문_상한초과_413` | |
| TC-PORTALUP-052 | 라벨 PUT chunked/Content-Length 부재 → 411 | PASS | `[실동작]` `Transfer-Encoding: chunked` → **411** `Content-Length 헤더가 필요합니다. (chunked 전송은 허용되지 않습니다)` `[정적]` `:64-69` `[테스트]` `#Content_Length_부재_chunked_411` | size-cap 우회 경로 봉쇄 확인 |
| TC-PORTALUP-053 | body size 필터 라벨 PUT 경로만 적용 | PASS | `[실동작]` chunked 를 붙여도 필터가 **개입하지 않음**을 4경로로 확인 — `PUT /v1/portal/uploads/1`→405, `PUT /v1/portal/user-labels`→405, `POST …/labels`(비 PUT)→405, `PUT /v1/frames/1/labels`(내부)→400, `PUT …/frames/1/2/labels`(세그먼트 2개)→404. **411 은 한 건도 없음** `[정적]` `shouldNotFilter :51-57` + 정규식 `^/v1/portal/uploads/frames/[^/]+/labels$` `[테스트]` `#GET_라벨_경로는_필터_미적용`,`#타_경로_PUT은_필터_미적용` | 필터가 dispatcher 앞단이라 411 부재 = 미적용의 직접 증거 |
| TC-PORTALUP-054 | 라벨 목록 조회(소유자 스코프), 타인/부재 403 | PASS | `[실동작]` 타인 frmeSn 119 → **403**, 부재 999999 → **동일 403** `[정적]` `:138-144` `[테스트]` `#타사용자_라벨_GET_403`,`#라벨_조회는_소유자_스코프_프레임_검증후_반환` | |
| TC-PORTALUP-055 | export JSON: 고정명+라벨 N+1 회피 | PASS | `[실동작]` **200** `Content-Disposition: attachment; filename="portal-upload-1-labels.json"` + `Content-Type: application/json` + `nosniff`. 본문에 자산 메타 8필드 + `frames[].labels[]` 중첩 좌표 포함(pretty) `[정적]` `:153-202` — `findAllByUldSnAndPortalUserNo` **업로드 단위 1회 조회** 후 `LinkedHashMap` 그룹핑(프레임별 반복 조회 없음) `[테스트]` `#export_JSON에_자산메타_프레임_라벨_모두_포함`,`#export_JSON_구조_검증_프레임_라벨_좌표_중첩배열`,`#export는_포털_리포지토리만_사용` | 파일명에 사용자 입력 미포함(서버 생성 고정명) |
| TC-PORTALUP-056 | 원본 다운로드 Content-Disposition CRLF 인젝션 차단 | PASS | `[실동작]` `orgnl_file_nm` 을 `evil\r\nX-Injected: 1\r\n\r\n<script>.jpg` 로 변조 후 다운로드 → 응답 헤더에 `X-Injected` **미출현**, `filename*=UTF-8''evilX-Injected%3A%201%3Cscript%3E.jpg`(CR/LF 제거 후 URL 인코딩) + ASCII fallback `filename="download.jpg"`. 한글·따옴표 파일명도 `%ED%95%9C…` 로 안전 인코딩 `[정적]` `attachmentDisposition :336-344` + `sanitizeFileName :346-360`(제어문자·`"`·`\`·`/` 제거) `[테스트]` `#원본_다운로드_Content_Disposition에_개행_포함_파일명_무해화`,`#파일명이_전부_제어문자면_download_확장자_고정명` | |
| TC-PORTALUP-057 | 원본 다운로드: 경로 미확정/부재 → 404 | PASS | `[실동작]` `file_path_nm=NULL` → **404** `원본 파일이 존재하지 않습니다.` / 존재하지 않는 경로 → **404**(동일 메시지) / base 밖 `/etc/passwd` → **403** `허용되지 않은 경로입니다.` `[정적]` `:217-228` + `resolveSafe :397-407` `[테스트]` `#원본_파일경로_없으면_404`,`#원본_파일_부재시_404`,`#원본_다운로드_경로탐색_차단` | 다운로드 **기간 제한은 부재**(F-ISSUE-23 / UNCERTAINTIES #11) |
| TC-PORTALUP-058 | 타 사용자 export/다운로드 → 403 | PASS | `[실동작]` `3001`→uldSn 119 의 `/export`·`/file` **둘 다 403** `[정적]` `:155-157`, `:214-215` `[테스트]` `#타사용자_export_403`,`#타사용자_원본_다운로드_403` | |

---

## 근거 드리프트

카탈로그 `근거(file:line)` 이 현재 소스(HEAD `ca3c712b`)와 어긋난 항목. **판정에는 영향 없음**(대상 코드를 grep 으로 재특정해 검증). 총 **43건**.

### PortalUploadService.java (F-5·F-6, 24건)

| TC | 카탈로그 | 실제 | 대상 |
|----|------|------|------|
| 001 | `:82-151` | `:83-152` | `uploadImages` |
| 002·003 | `:89-92` | `:90-93` | 개수 상한 |
| 004 | `:86-88` | `:87-89` | 빈 목록 분기 |
| 005 | `:274-277` | `:300-303` | `maxImageSizeBytes` 검증 |
| 006 | `:274` | `:300` | 동상 |
| 009 | `:278-283` | `:304-309` | 확장자 allowlist |
| 010 | `:284-289` | `:310-315` | 매직바이트 detect |
| 011 | `:290-294` | `:316-320` | 확장자↔시그니처 |
| 012 | `:295-301` | `:321-327` | JPEG EOI |
| 013 | `:94-108` | `:95-109` | 사전검증 루프 |
| 014 | `:137-146` | `:138-147` | 보상 삭제 catch |
| **015** | `:339-359` | `:365-372`(`writeToDisk`) + `:374-385`(`rollbackFiles`) | ⚠ **대상 메서드 자체가 다름** — 카탈로그 범위는 현재 `readTail`(`:343-363`) |
| 016 | `:117-127` | `:117-128` | UUID 저장명 + `resolveSafe` |
| 017 | `:428-433` | `:454-459` | `truncate` |
| 020 | `:156-171` | `:157-172` | `listUploads` |
| 021 | `:164-170` | `:165-171` | type 필터 분기 |
| 022 | `:163-168` | `:164-169` | 동상 |
| 024 | `:174-181` | `:175-182` | `getUpload` |
| 025 | `:184-191` | `:185-192` | `listFrames` |
| 026 | `:204-210` | `:205-211` | `serveFrameImage` 진입 |
| 027 | `:193-231` | `:194-236` | `serveFrameImage` 전체 |
| **028** | `:213,388-398` | `:214`(호출) + `:414-424`(`resolveSafe`) + `:245-257`(`realWithinBase`) | ⚠ `388-398` 은 현재 `fileNameOf`/`addTarget` 구간 |
| 029 | `:239-266` | `:265-292` | `deleteUpload` |
| 030 | `:245-250` | `:273-276` | PROCESSING 가드 |
| 031 | `:374-382` | `:400-408` | `deleteFileOrThrow` |
| 032 | `:242-243` | `:267-269` | 소유권 검증 |

### PortalUploadLabelService.java (F-7, 15건) — ★각주 반증

> 카탈로그 F-7 표 하단 각주: *"PortalUploadLabelService.java·PortalUploadLabelController.java 모두 2026-07-25 이후 무변경(`git log --since=2026-07-25` 0건) — 라인 재확인만 하고 값은 유지."*
> **실측 결과 15건이 드리프트**한다. "무변경"이 사실이라면 라인이 어긋날 수 없으므로, 각주의 전제(파일 무변경) 또는 원 라인값 자체가 부정확하다. 어느 쪽이든 **각주를 근거로 라인 재확인을 생략하면 안 된다.**

| TC | 카탈로그 | 실제 | 대상 |
|----|------|------|------|
| 040 | `:92-132` | `:93-133` | `replaceLabels` |
| 041 | `:98-106` | `:99-107` | 사전검증 |
| 042 | `:250-255` | `:265-270` | 타입 allowlist |
| 044 | `:266-269` | `:281-284` | BBOX 점수 |
| 045 | `:270-275` | `:285-290` | POLYGON 점수 |
| 046 | `:285-288` | `:300-303` | `isFinite` |
| 047 | `:257-260` | `:271-275` | label 길이 |
| 048 | `:115-118` | `:116-119` | READY 가드 |
| 049 | `:108-109` | `:110-111` | 비관적 락 |
| 050 | `:109-110` | `:110-111` | 동상 |
| 054 | `:137-143` | `:138-144` | `listLabels` |
| 055 | `:152-201` | `:153-202` | `exportLabels` |
| 056 | `:321-345` | `:336-344`(`attachmentDisposition`) + `:346-360`(`sanitizeFileName`) | 헤더 무해화 |
| 057 | `:216-227` | `:217-228` | 경로 부재 404 |
| 058 | `:154-156` | `:155-157` | export 소유권 |

### PortalLabelBodySizeFilter.java (3건)

| TC | 카탈로그 | 실제 | 대상 |
|----|------|------|------|
| 051 | `:73-78` | `:70-75` | 413 분기 |
| 052 | `:66-72` | `:64-69` | 411 분기 |
| 053 | `:54-60` | `:51-57` | `shouldNotFilter` |

### 라인 정확 일치 (드리프트 아님)

`PortalUploadController.java:145-151`(TC-023 `capped`) · `PortalUploadController.java:157-165`(TC-018 `acquireUploadPermit`) · `PortalUploadLabelController.java:63`(TC-043 `@Size`) · `PortalUploadProperties.java:40`(TC-005 `maxImageSizeBytes`) · `application-prd.yml:37-39` · `ConfigProfileDriftGuardTest.java:43-58`.

---

## 이슈 상세

### [F-ISSUE-21] TC-PORTALUP-004 — `files` 파트 누락 시 400 이어야 할 요청이 500 + 전체 스택트레이스로 떨어진다 (A-ISSUE-21 중복)

- **심각도**: HIGH
- **중복 고지**: `A-part3.md` / `A-result.md` 의 **A-ISSUE-21** 이 **동일 원인 · 동일 재현 경로**(`POST /v1/portal/uploads/images`, multipart 에 `files` 파트 없음 → 500)를 이미 등재했다. 본 항목은 F 클러스터 TC 판정 근거로서만 기록하며, `ISSUES.md` 누적 시 **A-ISSUE-21 로 병합**하는 것을 권장한다(신규 결함 아님).
- **기대 동작(기대효과)**: 필수 multipart 파트가 없는 요청은 **클라이언트 오류(400 `INVALID_INPUT`)** 로 응답하고, 로그는 WARN + 예외 클래스명·파트명만 남겨야 한다. 서비스에는 이미 그 의도가 코드로 존재한다 — `PortalUploadService.java:87-89`

  ```java
  if (files == null || files.isEmpty()) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "업로드할 이미지가 없습니다.");
  }
  ```

- **현재 동작(이슈 내용)**: 컨트롤러가 `@RequestParam("files") List<MultipartFile> files`(required 기본값 `true`)로 받으므로, 파트 부재는 서비스 진입 **이전에** `MissingServletRequestPartException` 으로 끊긴다. `GlobalExceptionHandler` 에는 `MissingServletRequestParameterException` 핸들러(`:76`)만 있고 **`MissingServletRequestPartException` 핸들러가 없어** `@ExceptionHandler(Exception.class)`(`:273`)로 떨어진다.

  실측 응답:
  ```json
  {"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}   // HTTP 500
  ```
  실측 로그(ERROR + 전체 스택):
  ```
  ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.multipart.support.MissingServletRequestPartException: Required part 'files' is not present.
      at org.springframework.web.method.annotation.RequestParamMethodArgumentResolver.handleMissingValueInternal(...)
  ```
  같은 계열로 `Content-Type` 이 multipart 가 아닌 요청은 `MultipartException: Current request is not a multipart request` → 역시 **500**.

  부수 효과로 서비스의 빈 목록 분기(`:87-89`)는 **HTTP 로 도달할 수 없는 dead branch** 가 된다(단위 테스트 `#빈_목록_업로드시_400` 은 서비스를 직접 호출하므로 통과한다 — 테스트 GREEN 이 런타임을 보증하지 못하는 사례).

- **재현/확인 경로**:
  ```bash
  TOKEN=$(...)   # PORTAL_USER
  curl -i -X POST http://localhost:18081/api/v1/portal/uploads/images \
       -H "Authorization: Bearer $TOKEN" -F "dummy=1"
  # → HTTP/1.1 500, errorCode=INTERNAL_ERROR
  docker logs klid-backend --since 1m | grep MissingServletRequestPart
  ```
- **영향**: ①**CWE-209/CWE-779** — 클라이언트 오류에 서버 스택트레이스를 ERROR 레벨로 적재. 조작된 요청을 반복하면 로그 볼륨 증폭(CWE-770). ②FE 가 입력 누락을 **서버 장애로 오인**(재시도·알림 오발). ③운영 5xx 알림 오탐으로 실장애 탐지 저하.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `GlobalExceptionHandler` 에 405 핸들러(`:261-271`)와 동형으로 `MissingServletRequestPartException` → 400 `INVALID_INPUT`(파트명만 노출) 핸들러를 추가하고, 겸해 `MultipartException`(비-multipart 요청) → 400 을 함께 처리한다. 로그는 WARN + 예외 클래스명·파트명만(경로 원문 금지). 회귀 가드는 A-ISSUE-21 제안대로 "`Exception.class` 종착지에 도달하는 표준 MVC 예외가 없다"를 고정하는 테스트로 통합한다.

---

### [F-ISSUE-22] TC-PORTALUP-017(부가) — 원본 파일명 255자 절단이 UTF-16 단위라 surrogate pair 를 쪼개고 치환문자를 남긴다

- **심각도**: LOW
- **기대 동작(기대효과)**: `LS_PORTAL_ULD.ORGNL_FILE_NM varchar(255)` 에 안전하게 담기도록 절단하되, **문자(코드포인트) 경계를 보존**해 저장된 표시용 파일명이 원본의 앞부분과 시각적으로 동일해야 한다.
- **현재 동작(이슈 내용)**: `PortalUploadService.java:454-459`

  ```java
  private static String truncate(String name) {
      if (name == null) { return null; }
      return name.length() > ORGNL_FILE_NM_MAX ? name.substring(0, ORGNL_FILE_NM_MAX) : name;
  }
  ```
  `String.length()`/`substring` 은 **UTF-16 코드유닛** 기준이다. BMP 밖 문자(이모지·일부 CJK 확장·희귀 문자)는 2유닛이므로, 절단 지점이 surrogate pair 한가운데면 **고아 high surrogate** 가 남는다. 실측(이모지 `U+1F600` 200개 + `.jpg`, 총 404 유닛):

  ```
  uld_sn | length | octet_length | right(orgnl_file_nm,3) | ascii(right(...,1))
  121    | 128    | 509          | 😀😀?                  | 63
  ```
  마지막 문자가 `?`(치환) 로 저장됐다. **예외는 발생하지 않고 조용히 손상**된다(JDBC/PG UTF-8 인코딩 단계에서 치환).

  ※ 컬럼 초과(=INSERT 실패)는 발생하지 않는다 — UTF-16 유닛 수 ≥ 코드포인트 수이므로 255유닛 절단 결과는 항상 255자 이하다. 즉 **가용성 문제는 없고 표시 정확도 문제**다.

- **재현/확인 경로**:
  ```bash
  EMO=$(python3 -c "print('\U0001F600'*200+'.jpg')")
  curl -X POST .../v1/portal/uploads/images -H "Authorization: Bearer $P" -F "files=@ok.jpg;filename=$EMO"
  psql -c "select length(orgnl_file_nm), ascii(right(orgnl_file_nm,1)) from ls_portal_uld where uld_sn=121"
  # → 128 | 63   (마지막이 '?')
  ```
- **영향**: 표시용 파일명 말미 1문자 손상. 보안 영향 없음(저장 경로는 UUID 강제, 이 값은 어떤 경로 조합에도 쓰이지 않는다). 다운로드 `Content-Disposition` 의 `filename*` 에 치환문자가 실려 나갈 수 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 코드포인트 경계 보존 절단으로 교체 — 예: `name.codePoints().limit(ORGNL_FILE_NM_MAX).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString()`, 또는 `BreakIterator`/`offsetByCodePoints` 사용. 회귀 테스트는 "surrogate pair 경계에서 절단해도 lone surrogate 가 남지 않는다"를 고정.

---

### [F-ISSUE-23] TC-PORTALUP-057·058(관련) — 포털 다운로드/export 에 "본인 데이터 기간 내" 기간 제한이 전혀 구현돼 있지 않다 (UNCERTAINTIES #11 미해소 재확인)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` 「포털(외부 채널)」 — *"본인 데이터 **기간 내** 다운로드"*. 포털 사용자가 자신의 자산·라벨을 내려받을 수 있는 기간에 상한이 있어야 하며, 기간 경과분은 다운로드가 거부(또는 자산이 정리)되어야 한다.
- **현재 동작(이슈 내용)**: 기간 판정 로직·기준 컬럼·설정 키가 **모두 부재**하다.
  - `LS_PORTAL_ULD` 스키마 실측 컬럼 14개 — `uld_sn, portal_user_no, uld_type_cd, orgnl_file_nm, file_path_nm, file_sz, mime_type_nm, uld_stts_cd, vdo_len_sec, fps, frme_cnt, fail_rsn_cn, reg_dt, mdfcn_dt`. **만료/보존기한 컬럼 0개.**
  - `PortalUploadLabelService.downloadFile`(`:211-256`)·`exportLabels`(`:153-202`) 의 가드는 **소유권 + 경로 안전성 + 파일 실재** 3가지뿐이며 시간축 판정이 없다.
  - `portal/` 패키지 내 `expire*` 참조는 전부 **TUS 업로드 세션 만료**(`PortalUploadSweepJob:71`, `PortalVideoUploadService:133`, `PortalVideoUploadTxService:77`)로 **다운로드 기간과 무관**한 별개 개념이다.
  - `PortalUploadProperties`(11필드)에 보존기간 설정 키 없음.
- **재현/확인 경로**:
  ```bash
  # reg_dt 를 과거로 밀어도 다운로드/export 가 그대로 200
  psql -c "update ls_portal_uld set reg_dt = now() - interval '10 years' where uld_sn=1"
  curl -i .../v1/portal/uploads/1/file   -H "Authorization: Bearer $P"   # → 200
  curl -i .../v1/portal/uploads/1/export -H "Authorization: Bearer $P"   # → 200
  # 스키마·코드 확인
  psql -c "\d ls_portal_uld" | grep -iE "expir|retent|valid"    # → 0건
  grep -rn "retention\|보존기간\|downloadableUntil" backend/src/main/java/kr/co/cudo/authoring/portal/  # → 0건
  ```
- **영향**: 요구사항 미충족(기능 갭). 보안 관점으로는 데이터 최소보관 원칙 미이행 — 포털 사용자 업로드 자산이 무기한 서버에 남고 무기한 재다운로드 가능하다. 개인 자산이 삭제 요청 없이 영구 축적되면 스토리지 증가와 함께 개인정보 보관기간 준수 이슈로 번질 수 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 이 항목은 **정책 확정이 선행**돼야 한다(기간의 기준: `reg_dt` 기준 N일인가 / 마지막 접근 기준인가 / 데이터마트 다운로드와 업로드 자산에 같은 규칙을 쓰는가). 확정 후 ①`PortalUploadProperties` 에 보존기간 키 추가 ②`downloadFile`/`exportLabels` 진입부에 기간 게이트(410 Gone 또는 403) ③기존 정리 스윕(`PortalUploadSweepJob`)에 만료 자산 정리 스텝 추가 순으로 배선하는 것이 기존 구조와 정합한다. **정책 미확정 상태에서 임의 기간을 코드에 박지 말 것.**

---

### [F-ISSUE-24] TC-PORTALUP-018(부가) — rate limit 이 업로드에만 있고 라벨 PUT·export·원본 다운로드는 무제한이다

- **심각도**: LOW
- **기대 동작(기대효과)**: 자원 소비형 포털 엔드포인트(대용량 JSON 파싱·파일 스트리밍·DB 벌크 DELETE+INSERT)에도 per-user 요청량 제한을 두어 단일 사용자가 자원을 독점하지 못하게 한다(OWASP API4:2023 / CWE-770).
- **현재 동작(이슈 내용)**: `RateLimiter` 획득은 `PortalUploadController.acquireUploadPermit`(`:157-165`) **한 곳뿐**이며 `POST /images` 에서만 호출된다. 나머지 포털 업로드 계열 엔드포인트에는 어떤 제한도 없다.

  실측(동일 사용자 70연타):
  | 엔드포인트 | 70회 중 429 |
  |---|---:|
  | `POST /v1/portal/uploads/images` | **10회** (60 초과분 전부) |
  | `GET /v1/portal/uploads/{uldSn}/export` | **0회** |
  | `PUT /v1/portal/uploads/frames/{n}/labels` | **0회** |

  라벨 PUT 은 요청당 최대 2MB 본문 파싱 + 500건 검증 + 벌크 DELETE + `saveAll` 500건을 수행하고, export 는 자산 전체 라벨을 메모리에 모아 pretty JSON 으로 직렬화한다 — 둘 다 업로드 못지않은 자원 소비 경로다.

- **재현/확인 경로**:
  ```bash
  for i in $(seq 1 70); do
    curl -s -o /dev/null -w "%{http_code} " "$B/v1/portal/uploads/1/export" -H "Authorization: Bearer $P1"
  done    # → 200 ×70, 429 없음
  ```
- **영향**: 단일 포털 사용자가 export/라벨 PUT 을 폭주시켜 커넥션·힙·CPU 를 점유할 수 있다. 다만 ①본문 상한(2MB) ②라벨 개수 상한(500) ③페이지 크기 하드캡(100) 이 이미 **요청당 비용**을 제한하고 있어 즉시 서비스 정지로 이어질 여지는 낮다 → LOW.
- **관련**: `UNCERTAINTIES #12`(데이터마트 저장/조회·이미지 서빙 rate limit 부재, 미해소 유지)와 **같은 축의 별개 표면**이다. #12 는 `PortalLabelService`(데이터마트 계열), 본 건은 `PortalUploadLabelService`(업로드 자산 계열)다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `acquireUploadPermit` 과 동일한 per-user RateLimiter 패턴을 `PortalUploadLabelController` 의 4개 메서드에 config 만 달리해(예: `portalLabel` 120/1m, `portalDownload` 30/1m) 적용한다. 컨트롤러마다 복제하지 말고 공통 헬퍼(또는 `HandlerInterceptor`)로 뽑아 **획득 지점을 단일화**하는 편이 이후 표면 추가 시 누락을 막는다(게이트 배선을 호출처마다 복제하면 반드시 샌다는 기존 교훈과 동형).

---

## 부록 — 이번 회차 확정/재확인 사항

| 항목 | 상태 |
|------|------|
| **UNCERTAINTIES #5** (`portal.upload.frame-interval-sec`=5초) | F-5~F-7 구간에는 프레임 추출 케이스가 없어 **본 파트 판정에 미영향**(F-9 소관). `PortalUploadProperties` 에 해당 필드가 없고 `PortalFrameExtractRunner` 계열이 소유 — 스코프 확인만 완료 |
| **UNCERTAINTIES #11** (다운로드 기간 제한) | **미해소 재확인** — 스키마·코드·설정 3면 부재 실증(F-ISSUE-23) |
| **UNCERTAINTIES #12** (rate limit 부재) | 데이터마트 계열은 F-part1 소관. 업로드 자산 계열도 **업로드 외 전 경로 부재** 실증(F-ISSUE-24) |
| 포털 업로드 자산 ↔ 비식별 신고 게이트 분리 | **정책대로 확인** — `PortalUploadService`·`PortalUploadLabelService` 에 게이트 참조 0건, `PortalFrameImageCacheControlTest:48` 이 스코프 경계를 코드로 명시 |
| 라벨 PUT raw 배열(알려진 함정) | **실측 확인** — 요청 본문이 `[{...}]` 배열이며 래핑 객체 아님. `@RequestBody(required=false)` 라 본문 생략 시 전체 삭제로 동작 |
| backend 이미지 HEAD 뒤처짐(stack-bringup §2) | **본 파트 무영향** — 실행 이미지가 HEAD `ca3c712b` 재빌드본으로 갱신됐고(`.progress.md`), F-5~F-7 대상 4파일은 심링크/TOCTOU 하드닝(`46f47cee`)·경로순회(`476bc91a`) 수정이 이미 반영된 코드가 실동작으로 확인됨(심링크 탈출 403 실측) |
