# F 클러스터 2차 검증 — part2 (F-3 데이터마트 사용자 라벨 저장 · F-6 포털 자산 조회/서빙/삭제)

- 대상: `docs/test-cases/F-portal.md` **F-3 (TC-PORTAL-040~050, 11건)** + **F-6 (TC-PORTALUP-020~032, 13건)** = **24건**
- 판정: **PASS 24 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0**
- 신규 이슈: **F-ISSUE-21 ~ F-ISSUE-29 (9건)** — HIGH 1(환경/배포 드리프트) · MEDIUM 4 · LOW 4
  (전건 "카탈로그 기대결과 자체는 충족하되 인접 축의 방어가 비어 있는" 결함이라 케이스 판정과 분리해 기록)
- 코드/설정/테스트 파일 수정 **0건**, 빌드/테스트 실행 **0건**. DB 는 테스트 데이터 INSERT/UPDATE 만 수행.

---

## 0. 검증 환경 · 근거 수집 방법

| 항목 | 실측값 |
|---|---|
| backend | `http://localhost:18081/api` (context-path `/api`) — `/api/actuator/health` → `{"status":"UP"}` |
| mock-server | `http://localhost:9400/health` → `{"status":"ok"}` |
| DB | `docker exec klid-postgres psql -U klid_user -d klid_system`, 스키마 `public` |
| 토큰 | `POST /api/v1/dev/tokens` — `{"role":"PORTAL_USER","channel":"PORTAL","userNo":...}` |
| 사용 계정 | **3001/3002**(F-3 라벨) · **3011/3012**(F-6 자산 — 병렬 에이전트와 sub 충돌 회피 목적으로 신규 채번) |
| 대상 데이터 | APPROVED 영상 `rawSn=4`(`DE_IDENT_YN='Y'`, frame 30건, srcSn 1~30) · 미승인 `rawSn=7`(PENDING)·`rawSn=11`(상태행 부재) · 신고 구간 `rawSn=900`(`DE_IDENT_YN='F'`, APPROVED) |
| baseline | 2026-08-02/2차에는 `test-baseline.md` 부재 → **2026-08-01/1차** 기준 대조 (BE 4,755 tests / 실패 0 / skip 5) |

### ★ 착수 전 발견한 환경 결함 — 배포 jar 가 워킹트리 소스보다 오래됨 (F-ISSUE-27)

`git status --short` 기준 워킹트리에 **미커밋 수정 80파일**이 있고, 그중 포털 관련 5개
(`SortAllowlist.java` · `PortalLabelController.java` · `PortalUploadController.java` · `PortalSam2Controller.java` · `PortalSam2Service.java`)가
**컨테이너 jar 에 반영돼 있지 않다.** 실측 근거:

```
docker inspect klid-backend → created 2026-08-01T14:10:06Z / /app/app.jar (Aug 1 14:05)
$ docker cp klid-backend:/app/app.jar → BOOT-INF/classes/.../PortalUploadController.class
   safeSort       False      ← 소스에는 있음(PortalUploadController.java:164-166)
   SortAllowlist  False
   capped         True       ← 배포됨
$ 같은 jar 의 SortAllowlist.class
   PORTAL_UPLOAD  False / PORTAL_UPLOAD_FRAME False / DEIDENT_REPORT False
```

**영향**: `GET /v1/portal/uploads?sort=status,asc` 이 라이브에서 **500**
(`PropertyReferenceException: No property 'status' found for type 'LsPortalUld'` — backend 로그 실측),
`?sort=filePathNm,desc` 는 **200 + 내부 컬럼으로 실제 정렬**(응답 `sort.sorted=true`).
**소스 기준으로는 둘 다 400** 이다(`SortAllowlist.resolve` strict).

**판정 원칙**: 본 검증은 "코드는 qa-0801 기준"이므로 소스가 정본이다.
`PortalUploadService.java` · `PortalLabelService.java` 는 **미수정 파일**이라 라이브 동작 = 소스이고,
F-3/F-6 24건 중 **정렬 축을 기대결과로 삼는 케이스는 없다**(TC-PORTALUP-023 은 `size` 하드캡 전용이며
`capped` 는 배포본에도 있고 라이브로 확인됨). 따라서 24건 판정은 영향을 받지 않으며, 드리프트 자체를
F-ISSUE-27 로 분리 기록한다.

---

## 1. F-3. 데이터마트 사용자 라벨 저장 (단방향 · 원본 미수정 · IDOR) — 11건

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|:--:|------|
| TC-PORTAL-040 | PASS | [실동작] | `POST /v1/portal/user-labels {sourceRawSn:4,sourceSrcSn:1,BBOX,points:"[[1,2],[3,4]]"}` → **201** `{"userLblSn":1,...}`. **단방향 실증** — 저장 전후 `ls_data_lbl` 전행 md5(`lbl_sn\|src_sn\|lbl_type_cd\|point_cn\|lbl_nm\|mdfcn_dt`) = `ccfaeb4d76b0cfb4155d83b9d72d6202 n=91` **동일**, `ls_data_lbl_hstry=70` 동일, `ls_label_version=9` 동일, `ls_dataset_export=30` 동일, **mock-server 인바운드 로그 증가 0줄**(관제 `TASK_MODIFIED` 미발생). 적재는 `ls_portal_user_label` 에만 (`PortalLabelService.java:218-220`) |
| TC-PORTAL-041 | PASS | [실동작] | PENDING `rawSn=7` → **403** `{"errorCode":"FORBIDDEN","message":"데이터마트에 노출되지 않은 영상입니다."}`. 반증 확장: 상태행 **부재** `rawSn=11` → 403, **존재하지 않는** `rawSn=999999` → 403 (`isExposedToDatamart` 의 `.orElse(false)` fail-closed, `:504-508`). 세 응답이 동일해 자원 열거도 차단 |
| TC-PORTAL-042 | PASS | [실동작] | SKELETON 17점 `[[x,y,v]×17]`, v∈{0,1,2} → **201**. 로드 round-trip 확인: `GET /v1/portal/frames/1/labels` 응답에 `(id=4,'SKELETON',points 17개)` + v 보존(`[...,1.0]`/`[...,2.0]`) |
| TC-PORTAL-043 | PASS | [실동작] | 16점 → **400** `"SKELETON 키포인트는 정확히 17 개여야 합니다."` (`:325-328`) |
| TC-PORTAL-044 | PASS | [실동작] | `v=3` → **400** `"가시성 v 는 0/1/2 중 하나여야 합니다."` (`:330-333`) |
| TC-PORTAL-045 | PASS | [실동작] | `NaN` 리터럴 → **400**(`"키포인트 좌표 형식이 올바르지 않습니다."` — Jackson 파싱 단계 fail-closed), `Infinity` 리터럴 → **400**(동일). ★반증: 파서를 통과하는 **`1e400`(double overflow → +Inf)** 로 재시도 → **400** `"좌표는 유한한 숫자여야 합니다."` → `Double.isFinite` 가드(`:334-337`)가 **죽은 코드가 아님을 실증**. 음수 `-1` → 400 `"좌표는 0 이상이어야 합니다."`(`:338-340`) |
| TC-PORTAL-046 | PASS | [실동작] | BBOX + `points:"[]"` → **400** `"points 좌표가 비어있습니다."`, `"[[]]"` → 400, 파싱 불가 `"not-json"` → 400(fail-secure 빈 리스트 → 동일 400). `ls_portal_user_label` 에 빈 row 생성 0건 (`:212-217`) |
| TC-PORTAL-047 | PASS | [실동작] | `points:null` / `"   "` / 키 자체 누락 → 전부 **400** `"points: points 는 필수입니다."` (`@NotBlank`, `PortalUserLabelRequest.java:17`) |
| TC-PORTAL-048 | PASS | [실동작] | 3002 가 `label:"USER3002-SECRET"` 저장(userLblSn=9) → 3002 의 `GET /v1/portal/user-labels?rawSn=4` = `[(9,'USER3002-SECRET')]`, **3001 의 같은 호출 = `[8,7,6,5,4,3,2,1]` — SECRET 미포함**. 프레임 로드 `GET /v1/portal/frames/1/labels`(3001) 에도 `grep -c USER3002-SECRET = 0`. 소유자 스코프 쿼리 `findByPortalUserNoAndSrcRawSn...`(`:233`) / `findByPortalUserNoAndSrcDataSrcSn...`(`:275`) |
| TC-PORTAL-049 | PASS | [실동작] | DB 직접 INSERT 로 stale row 3종(`point_cn` = NULL / `''` / `'[]'`, srcSn=5) 주입 → `GET /v1/portal/frames/5/labels` **200** `labels: []` (필터 `:285-287`). 혼재 케이스(srcSn=7 에 정상 1 + 빈 1) → **정상 1건만** 반환 |
| TC-PORTAL-050 | PASS | [실동작] | 손상 좌표 `'{not-json'`(BBOX) + 타입 불일치 `'[[1,2],[3,4]]'`(SKELETON) 주입(srcSn=6) → `GET .../frames/6/labels` **200** `labels: []`, **500 미발생**. `parsePointsRouted`/`parsePoints` 의 `catch(RuntimeException)` → 빈 리스트(`:361-364`, `:383-387`) |

### F-3 반증(확증편향 차단) 로그

| 반증 시도 | 결과 |
|---|---|
| 저장이 `LS_DATA_LBL`/이력/버전/export 를 건드리는가 | ✅ 전부 불변 (md5·건수 동일) |
| 저장이 관제 통지를 유발하는가(단방향 위반) | ✅ mock-server 인바운드 로그 증가 0 |
| `isExposedToDatamart` 가 상태행 부재 시 fail-open 하는가 | ✅ `.orElse(false)` → 403 |
| SKELETON 의 `isFinite` 가드가 Jackson 에 가려 죽은 코드인가 | ⚠ **아니다** — `1e400` 로 도달 실증 |
| **비SKELETON 경로도 같은 검증을 받는가** | ❌ **아니다 → F-ISSUE-21** (BBOX `1e400` → 201 저장) |
| 좌표 개수 상한이 있는가 | ❌ **없다 → F-ISSUE-22** (30,000점 → 201) |
| `lblTypeCd` 가 열거로 제한되는가 | ❌ **아니다 → F-ISSUE-23** (`"HACK"` 저장됨) |
| `sourceSrcSn` 이 `sourceRawSn` 소속인지 검증되는가 | ❌ **아니다 → F-ISSUE-24** (존재하지 않는 9001 / 타 영상 프레임 35 저장됨) |
| 신고 구간(`'F'`) 영상에 저장이 막히는가 | ❌ 막히지 않음 → **F-ISSUE-26**(조회는 412 로 정상 차단됨을 대조 확인) |
| 본인 라벨 조회가 페이징되는가 | ❌ 아니다 → **F-ISSUE-25** |

---

## 2. F-6. 포털 자산 조회/서빙/삭제 (IDOR · 페이징 · 상태) — 13건

사전 준비: 3011 이 PNG 3건 업로드(`uldSn=63,64,65` / `uldFrmeSn=63,64,65`), 3012 가 1건(`uldSn=66`). 전부 `READY`.

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|:--:|------|
| TC-PORTALUP-020 | PASS | [실동작] | `GET /v1/portal/uploads` — 3011 → `totalElements=3 ids=[63,64,65]`, 3012 → `totalElements=1 ids=[66]`. 소유자 스코프 `findAllByPortalUserNo`(`PortalUploadService.java:161`). 페이징 응답 래퍼 정상(`size/number/totalPages`) |
| TC-PORTALUP-021 | PASS | [실동작] | `?type=IMAGE` → total 3, `?type=VIDEO` → total 0, 소문자 `image`/`video` 도 동일(`toUpperCase` 정규화 `:165`) |
| TC-PORTALUP-022 | PASS | [실동작] | `?type=FOO` → **400** `"지원하지 않는 type 입니다. 허용: IMAGE, VIDEO"` (`:166-169`). 대조: `?type=`(빈값)·공백 → 200 무필터(코드 `:160-163` 과 일치, 조용한 빈결과 아님) |
| TC-PORTALUP-023 | PASS | [실동작] | `/uploads?size=` 20→20, 100→100, **101→100, 500→100, 5000→100**. `/uploads/63/frames?size=500→100, 5000→100`. `size=-1`,`0` → 기본 20. `capped()` `PortalUploadController.java:177-182`(카탈로그 표기 `:145-151` 은 드리프트) |
| TC-PORTALUP-024 | PASS | [실동작] | 3012 가 `GET /uploads/63` → **403** `"본인 자산이 아니거나 존재하지 않습니다."`. **부재와 동일 응답** 확인: `/uploads/999999` → 동일 403/동일 메시지(자원 열거 차단). 3011 본인 조회는 200 |
| TC-PORTALUP-025 | PASS | [실동작] | 3012 가 `GET /uploads/63/frames` → **403**(사전 소유권 검증 `:189` + 소유자 스코프 조인 `:190`). 3011 본인 → 200 `totalElements=1` |
| TC-PORTALUP-026 | PASS | [실동작] | 3012 가 `GET /uploads/frames/63/image` → **403**(`findByUldFrmeSnAndOwner` `:207`). 존재하지 않는 `frames/999999/image` 도 동일 403 |
| TC-PORTALUP-027 | PASS | [실동작] | 3011 본인 서빙 → **200**, `Content-Type: image/png`(DB `mime_type_nm` 와 일치), `X-Content-Type-Options: nosniff`, `Content-Disposition: inline; filename="frame_63"`(사용자 입력 미반영), 바디 매직바이트 `\x89PNG\r\n\x1a\n`. **신고 게이트 미적용 확인**(412 아님) — TC 명세대로 ADR-013 예외 경로. ★반증: DB `mime_type_nm='text/html'` 로 조작 후 재요청 → **`application/octet-stream`**(`resolveStoredMediaType` fail-closed `:426-435`) + nosniff 유지 → XSS 서빙 불가 |
| TC-PORTALUP-028 | PASS | [실동작] | DB 에 조작 경로 프레임 3종 주입 후 서빙 시도 → **전부 403** `"허용되지 않은 경로입니다."` ①`/app/storage/raw/portal/images/../../../../etc/hostname` ②절대경로 `/etc/hostname` ③상대경로 `../../../etc/hostname` (`resolveSafe` `:415-424`, 카탈로그 `:388-398` 은 드리프트). ★★반증 강화: base **안쪽**에 `images/qa-symlink-test.png → /etc/hostname` 심링크를 실제로 만들고 그 경로로 서빙 시도 → **403** `"허용되지 않은 이미지 경로입니다."`(`realWithinBase` `:245-257` 의 `toRealPath` 재검증). lexical 통과 + 심링크 탈출 모두 차단됨 (CWE-22/59/367) |
| TC-PORTALUP-029 | PASS | [실동작] | `uldSn=64`(파일 `.../3f606578-....png` 존재) + 라벨 1행(`ls_portal_uld_lbl`) 상태에서 `DELETE /uploads/64` → **204**. 직후 `ls -la` → `No such file or directory`(파일 선삭제), DB `uld=0 / frme=0 / lbl=0`(CASCADE). 순서 = 소유권→파일→DB (`:265-292`) |
| TC-PORTALUP-030 | PASS | [실동작] | `uldSn=65` 를 `PROCESSING` 으로 UPDATE → `DELETE` **409** `"프레임 추출이 진행 중인 자산은 삭제할 수 없습니다..."`, **행 보존(count=1)** (`:273-276`). 대조: `UPLOADED` → 204 삭제됨(코드와 일치, 단 주석의 "READY/FAILED 후 허용" 과 불일치 → F-ISSUE-28) |
| TC-PORTALUP-031 | PASS | [실동작] | `file_path_nm` 을 **비어있지 않은 디렉터리**(`images/qa-dir-test/`)로 가리키는 자산 주입 → `DELETE` 시 `Files.deleteIfExists` 가 `DirectoryNotEmptyException`(IOException) → **500** `"파일 삭제에 실패했습니다. 잠시 후 다시 시도하세요."`, **DB `uld_rows=1 / frme_rows=1` 보존**(`deleteFileOrThrow` `:400-408`). 응답에 경로·스택트레이스 미노출(CWE-209) |
| TC-PORTALUP-032 | PASS | [실동작] | 3012 가 `DELETE /uploads/64`(3011 소유) → **403** `"본인 자산이 아니거나 존재하지 않습니다."`, 대상 행 **보존(count=1)** (`:267-269`) |

### F-6 반증 로그

| 반증 시도 | 결과 |
|---|---|
| 403 이 "부재"와 "타인 소유"를 구분해 열거를 허용하는가 | ✅ 동일 메시지·동일 코드 → 구분 불가 |
| DB MIME 조작으로 `text/html` 서빙 가능한가 (Stored XSS) | ✅ 불가 — octet-stream fail-closed |
| base 안쪽 심링크로 `/etc` 탈출 가능한가 | ✅ 불가 — `toRealPath` 재검증 403 |
| 페이지 크기 상한 우회(`size=5000`) | ✅ 불가 — 100 클램프 |
| 파일 삭제 실패 시 DB 만 지워져 고아 행/유실이 생기는가 | ✅ 아니다 — 파일 선삭제 + 실패 시 DB 미삭제 |
| **정렬 키 allowlist 가 실제로 적용되는가** | ❌ **라이브 미적용(배포 드리프트) → F-ISSUE-27** (`sort=status` 500, `sort=filePathNm` 200 실정렬) |
| 삭제 상태 검사에 락이 있는가 | ❌ 없음 → **F-ISSUE-28** |
| 자산 상세의 프레임 목록이 페이징되는가 | ❌ 아니다(최대 2,000건 일괄) → **F-ISSUE-29** |

---

## 3. 테스트 커버 대조 (`_raw/test-baseline.md` = 2026-08-01/1차, BE 4,755 tests / 실패 0)

| 케이스 | 커버 테스트 (`파일:@DisplayName`) | baseline |
|---|---|:--:|
| TC-PORTAL-040 | `PortalUserLabelServiceTest.java:113 V2_사용자_작업_데이터_별도_적재_원본_미수정` | 통과 |
| TC-PORTAL-041 | `PortalLabelServiceKeypointTest.java:145 포털_비APPROVED_영상_사용자라벨_저장_거부_403` | 통과 |
| TC-PORTAL-042 | `PortalUserLabelServiceTest.java:158 Phase9_포털_키포인트_SKELETON_17점_정상_저장` · `PortalLabelServiceKeypointTest.java:118 …round-trip` | 통과 |
| TC-PORTAL-043 | `PortalUserLabelServiceTest.java:174 …점개수_불일치_400_저장안함` | 통과 |
| TC-PORTAL-044/045 | `PortalUserLabelServiceTest.java:188 …형식위반_400_저장안함` (v 범위/유한성 단독 케이스는 없음 — 부분 커버) | 통과 |
| TC-PORTAL-046 | `PortalUserLabelServiceTest.java:133 R17_…빈_좌표_JSON_시_INVALID_INPUT_빈라벨row_차단` | 통과 |
| TC-PORTAL-047 | `PortalLabelControllerTest.java`(DTO @Valid 경로) | 통과 |
| TC-PORTAL-048 | `PortalUserLabelServiceTest.java:215 V2_본인_작업_라벨_조회_IDOR_본인만` · `:229 V2_다른_사용자_데이터_접근_불가_IDOR` | 통과 |
| TC-PORTAL-049 | `PortalFrameLabelsServiceTest.java:149 R17_…빈좌표_user_label은_제외하고_datamart_원본_폴백` | 통과 |
| TC-PORTAL-050 | 전용 테스트 **없음**(손상 JSON fail-secure 단독 케이스 부재 — 실동작으로만 확인) | — |
| TC-PORTALUP-020/021/022 | `PortalUploadServiceTest.java:325/340`, `PortalUploadControllerTest.java:266/283` | 통과 |
| TC-PORTALUP-023 | `PortalUploadControllerTest.java:291 size_100초과시_100으로_캡` | 통과 |
| TC-PORTALUP-024/025/026 | `PortalUploadServiceTest.java:224/354/231`, `PortalUploadControllerTest.java:203/300/214` | 통과 |
| TC-PORTALUP-027 | `PortalUploadControllerTest.java:231 이미지_서빙_응답에_nosniff_헤더와_DB_확정_ContentType` | 통과 |
| TC-PORTALUP-028 | `PortalUploadServiceTest.java:419 경로조작_파일명_업로드시_저장경로가_베이스_밖으로_나가지_않음`(업로드 축) — **서빙 축 + 심링크 탈출 전용 테스트 없음(갭)** | 통과 |
| TC-PORTALUP-029 | `PortalUploadServiceTest.java:255 삭제시_파일과_DB행이_함께_제거됨`, `PortalUploadControllerTest.java:326` | 통과 |
| TC-PORTALUP-030 | `PortalUploadProcessingDeleteTest.java:34 PROCESSING_자산_삭제시_409` | 통과 |
| TC-PORTALUP-031 | `PortalUploadServiceTest.java:278 파일삭제_실패시_DB행_보존` | 통과 |
| TC-PORTALUP-032 | `PortalUploadServiceTest.java:238 타사용자_삭제시_403`, `PortalUploadControllerTest.java:223` | 통과 |

> ⚠ `PortalUploadControllerTest.java:350/368/378/396`(정렬 allowlist 4건)은 **소스에만 존재**하고 배포 jar 에는 없다 → F-ISSUE-27 의 방증.

---

## 4. 근거 드리프트 (카탈로그 `file:line` ↔ 실측)

| ID | 카탈로그 표기 | 실측 위치 |
|---|---|---|
| TC-PORTAL-040 | `PortalLabelService.java:196-223` | `:196-224` |
| TC-PORTAL-041 | `:202-205` | `:203-206` (+ 판정기 `:504-508`) |
| TC-PORTAL-042 | `:208-209` | `:209-217` (검증 본체 `:317-342`) |
| TC-PORTAL-043 | `:324-327` | `:325-328` |
| TC-PORTAL-044 | `:329-332` | `:330-333` |
| TC-PORTAL-045 | `:333-336` | `:334-337`(유한성) + `:338-340`(음수) |
| TC-PORTAL-046 | `:211-216` | `:212-217` |
| TC-PORTAL-048 | `:226-234` | `:226-235` |
| TC-PORTAL-049 | `:284-286` | `:285-287` |
| TC-PORTAL-050 | `:375-387` | `:376-388` |
| TC-PORTALUP-020 | `PortalUploadService.java:156-171` | `:156-172` |
| TC-PORTALUP-021 | `:164-170` | `:164-171` |
| TC-PORTALUP-022 | `:163-168` | `:165-169` |
| TC-PORTALUP-023 | `PortalUploadController.java:145-151` | `:176-182`(`capped`) — **31행 드리프트** |
| TC-PORTALUP-024 | `:174-181` | `:174-182` |
| TC-PORTALUP-025 | `:184-191` | `:184-192` |
| TC-PORTALUP-026 | `:204-210` | `:205-211` |
| TC-PORTALUP-027 | `:193-231` | `:194-236` |
| TC-PORTALUP-028 | `:213,388-398` | `:213-214` + `resolveSafe :415-424` + `realWithinBase :245-257` — **27행 드리프트 + 판정기 1종 누락** |
| TC-PORTALUP-029 | `:239-266` | `:265-292` — **26행 드리프트** |
| TC-PORTALUP-030 | `:245-250` | `:273-276` |
| TC-PORTALUP-031 | `:374-382` | `:400-408` |
| TC-PORTALUP-032 | `:242-243` | `:267-269` |

> 23/24 케이스가 드리프트(대부분 1~3행, 4건은 26~31행). 카탈로그 정합성 정정 대상.

---

## 5. 이슈

### [F-ISSUE-21] TC-PORTAL-045 인접 — 포털 사용자 라벨 저장이 **비SKELETON 타입에는 좌표 유한성·경계 검증을 전혀 하지 않는다**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SKELETON 에 적용된 fail-closed 검증(유한성·음수 거부)은 좌표를 저장하는 모든 타입에 적용돼야 한다. 저장된 좌표는 FE 캔버스가 `number` 로 소비하므로 비유한값이 들어가면 렌더가 깨지고, 경계를 벗어난 좌표는 학습데이터로서 의미가 없다. 내부 경로는 `LabelService.validateWithinBounds` 로 **400 거부**(CLAUDE.md ★3 "사용자 저장 = 400 거부")를 이미 강제한다.
- **현재 동작(이슈 내용)**: `PortalLabelService.saveUserLabel` 은 타입 분기에서 SKELETON 만 `validateSkeletonPoints` 로 보내고, 그 외는 "빈 좌표인지"만 본다.
  ```java
  // PortalLabelService.java:209-217
  if (LsDataLbl.TYPE_SKELETON.equals(req.lblTypeCd())) {
      validateSkeletonPoints(req.points());          // 유한성·범위·개수 검증
  } else {
      if (parsePoints(req.points()).isEmpty()) {     // ← 비어있는지만 본다
          throw new CustomException(ErrorCode.INVALID_INPUT, "points 좌표가 비어있습니다.");
      }
  }
  ```
  실측: `{"lblTypeCd":"BBOX","points":"[[1e400,1e400],[2,2]]"}` → **201**(userLblSn=5). 로드 시
  `GET /v1/portal/frames/1/labels` 응답이 `"points":[["Infinity","Infinity"],[2.0,2.0]]` — Jackson 이 비유한 double 을 **문자열**로 직렬화해 `number[][]` 계약이 깨진다. 또한 `[[-99999,-99999],[999999999,999999999]]` 도 **201**(경계 검증 부재).
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
     -d '{"role":"PORTAL_USER","channel":"PORTAL","userNo":"3001"}' | jq -r .data.token)
  curl -s -XPOST localhost:18081/api/v1/portal/user-labels -H "Authorization: Bearer $TOK" \
     -H 'Content-Type: application/json' \
     -d '{"sourceRawSn":4,"sourceSrcSn":1,"lblTypeCd":"BBOX","label":"inf","points":"[[1e400,1e400],[2,2]]"}'   # 201
  curl -s localhost:18081/api/v1/portal/frames/1/labels -H "Authorization: Bearer $TOK" | grep Infinity
  ```
- **영향**: 데이터 정합(학습데이터로 쓸 수 없는 좌표 적재) + FE 계약 파손(CWE-20 입력 검증 미흡). PII 노출은 아님.
- **수정 방향(제안)**: `PortalLabelService.saveUserLabel` 의 else 분기에 ①`Double.isFinite` 검사 ②(가능하면) 프레임 해상도 기반 경계 검사를 추가한다. 내부 `LabelService` 의 검증 헬퍼를 공용 유틸로 추출해 두 경로가 같은 판정기를 쓰게 하는 편이 드리프트를 막는다. ⚠ 구현하지 않음.

### [F-ISSUE-22] TC-PORTAL-040/046 인접 — 포털 사용자 라벨 **좌표 개수 상한 부재** (내부 경로는 1,000점 상한)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨 1건의 좌표 수는 상한을 가져야 한다. 내부 라벨 경로는 `LabelService.MAX_POINTS_PER_LABEL = 1000` 을 두고 초과 시 400 또는 단순화(`PolygonSimplifier`)한다. 외부 채널(포털)은 인증만 있으면 누구나 호출하므로 상한이 더 필요하다.
- **현재 동작(이슈 내용)**: 포털 저장 경로에는 개수 검사가 없다(`PortalLabelService.java:209-220` 전체에 `size()` 비교 없음). 실측: 30,000점 POLYGON(요청 본문 458KB) → **201**(userLblSn=8). 이후 `GET /v1/portal/frames/1/labels` 응답이 **519,395 bytes** 로 부풀고, 이 엔드포인트는 **페이징이 없어** 같은 프레임에 이런 라벨을 여러 건 쌓으면 응답이 선형 증가한다.
  ```
  $ curl ... /v1/portal/frames/1/labels -o f.json -w '%{size_download}'
  519395
  ```
- **재현/확인 경로**: `points` 에 `[[i,i] for i in range(30000)]` JSON 을 넣어 `POST /v1/portal/user-labels` → 201. `GET /v1/portal/frames/{srcSn}/labels` 응답 크기 확인.
- **영향**: 자원 소진(CWE-770 / OWASP API4:2023) — 저장·조회 양쪽. `portal.upload.max-label-body-bytes`(2MB)는 **포털 업로드 라벨 PUT 전용 필터**라 이 엔드포인트를 막지 않는다.
- **수정 방향(제안)**: `PortalLabelService.saveUserLabel` 에 `MAX_POINTS_PER_LABEL` 동일 상한(공용 상수 참조)을 적용해 초과 시 400. 아울러 `loadFrameLabels`/`listMyLabels` 응답에 항목 상한 또는 페이징 도입. ⚠ 구현하지 않음.

### [F-ISSUE-23] TC-PORTAL-040 인접 — `lblTypeCd` 가 **열거로 검증되지 않아 임의 문자열이 저장**된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 라벨 타입은 `BBOX/POLYGON/SEGMENT/SKELETON/TRACK` 등 정해진 코드값만 허용돼야 한다. 타입은 로드 시 **파싱 라우팅 키**(`parsePointsRouted`)로 쓰이므로 미지의 값은 조용히 2-튜플 경로로 흘러간다.
- **현재 동작(이슈 내용)**: DTO 제약이 `@NotBlank @Size(max = 16)` 뿐이다(`PortalUserLabelRequest.java:13`). 서비스도 `TYPE_SKELETON` 동등 비교만 한다. 실측: `{"lblTypeCd":"HACK",...}` → **201**(userLblSn=7), 로드 응답에 `{"lblTypeCd":"HACK","points":[[1.0,1.0],[2.0,2.0]]}` 그대로 반환.
- **재현/확인 경로**: `POST /v1/portal/user-labels` 에 `"lblTypeCd":"HACK"` → 201, `GET /v1/portal/frames/1/labels` 응답에 그대로 노출.
- **영향**: 데이터 정합(다운로드/Export 소비자가 알 수 없는 타입을 만남). 값이 응답 JSON 문자열로만 나가므로 XSS 는 아니나 FE 분기 로직이 깨질 수 있다(CWE-20).
- **수정 방향(제안)**: DTO 에 `@Pattern` 또는 서비스에서 허용 코드 집합(allowlist) 검사 후 400. ⚠ 구현하지 않음.

### [F-ISSUE-24] TC-PORTAL-041 인접 — `sourceSrcSn` 의 **존재 여부·부모 영상 소속을 검증하지 않아** APPROVED 게이트가 프레임 축에서 무력화된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 저장 요청의 `sourceSrcSn` 은 ①실재하는 프레임이어야 하고 ②`sourceRawSn` 에 속해야 한다. 그래야 "APPROVED 영상만 작업 가능"이라는 게이트가 프레임 단위로도 성립하고, 저장된 행이 로드 경로(프레임 기준 조회)와 일관된다.
- **현재 동작(이슈 내용)**: 게이트는 `sourceRawSn` 하나만 본다.
  ```java
  // PortalLabelService.java:203-206
  if (!isExposedToDatamart(req.sourceRawSn())) { ... throw FORBIDDEN; }
  // sourceSrcSn 은 이후 어디에서도 조회·대조되지 않고 그대로 적재된다 (:218-220)
  ```
  실측 ①존재하지 않는 프레임: `{"sourceRawSn":900,"sourceSrcSn":9001}` → **201**(rawSn 900 의 실제 프레임은 429,430). ②타 영상 프레임: `{"sourceRawSn":4,"sourceSrcSn":35}` (srcSn 35 는 **미승인** `rawSn=7` 소속) → **201**(userLblSn=19).
  DB 스키마상 FK 는 `src_raw_sn → ls_data_raw` 하나뿐이고 `src_data_src_sn` 에는 FK 가 없다(`\d ls_portal_user_label`).
- **재현/확인 경로**:
  ```bash
  curl -XPOST .../v1/portal/user-labels -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"sourceRawSn":4,"sourceSrcSn":35,"lblTypeCd":"BBOX","label":"cross","points":"[[1,1],[2,2]]"}'   # 201
  # 대조: 그 프레임을 읽으려 하면 정상적으로 막힌다
  curl .../v1/portal/frames/35/labels -H "Authorization: Bearer $TOK"   # 403 (미승인 영상)
  ```
- **영향**: 데이터 정합 위주. **정보 유출은 없다**(로드 경로 `loadFrameLabels` 가 프레임의 실제 `rawSn` 으로 다시 게이팅해 403/412 를 낸다 — 실측 확인). 다만 미승인·신고 영상 프레임 번호에 매달린 고아 라벨이 무제한 쌓이고, 향후 다운로드/집계가 프레임 기준으로 조인하면 잘못된 영상에 라벨이 붙는다(CWE-20 / 약한 형태의 CWE-639).
- **수정 방향(제안)**: `saveUserLabel` 에서 `srcRepository.findById(sourceSrcSn)` 로 프레임을 조회해 부재 시 404, `frame.getRawSn() != sourceRawSn` 이면 400/403. 겸사 `src_data_src_sn` 에 FK 추가 검토. ⚠ 구현하지 않음.

### [F-ISSUE-25] TC-PORTAL-048 인접 — `GET /v1/portal/user-labels` 가 **페이징 없이 전건 반환**한다
- **심각도**: LOW
- **기대 동작(기대효과)**: `.claude/rules/api-design.md` — "목록 조회는 반드시 페이징 적용. 전체 조회(페이징 없는 findAll) 금지."
- **현재 동작(이슈 내용)**: 컨트롤러·서비스 모두 `List<>` 를 그대로 반환한다.
  ```java
  // PortalLabelController.java:106-113  → @GetMapping("/user-labels") … ApiResponse<List<PortalUserLabelResponse>>
  // PortalLabelService.java:228-235
  return userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(actor.sub(), rawSn)
          .stream().map(PortalUserLabelResponse::from).toList();
  ```
  같은 영상에 사용자가 저장한 라벨이 누적되면(현 설계상 매 저장이 **INSERT**, upsert 아님) 응답이 무한 증가한다. `loadFrameLabels`(`:249-304`)도 동일하게 무페이징이며 F-ISSUE-22 와 곱해진다.
- **재현/확인 경로**: 같은 `(rawSn, srcSn)` 으로 `POST /v1/portal/user-labels` 를 N 회 반복 → `GET /v1/portal/user-labels?rawSn=4` 응답 항목이 N 개로 선형 증가(실측: 8건까지 확인).
- **영향**: 자원 소진(CWE-770), 규칙 위반.
- **수정 방향(제안)**: `Pageable` 도입(기본 20 / 상한 100, `PortalUploadController.capped` 패턴 재사용). 하위호환이 걸리면 응답 래퍼 유지 + 상한만 우선 적용. ⚠ 구현하지 않음.

### [F-ISSUE-26] TC-PORTAL-040 인접 — 비식별 누락 신고 구간(`DE_IDENT_YN='F'`) 영상에도 **포털 사용자 라벨 저장이 허용**된다 (조회는 412)
- **심각도**: LOW
- **기대 동작(기대효과)**: 신고 구간에는 그 영상에 대한 작업 자체를 잠그는 것이 일관적이다. 내부 경로는 작업락으로 저장·수정을 409 차단하고, 포털 조회 경로는 412 로 막는다(CLAUDE.md 차단 범위 ④).
- **현재 동작(이슈 내용)**: `saveUserLabel` 에는 `accessGuard.requireNotUnderDeidentReport` 호출이 없다(`PortalLabelService.java:196-224`). 같은 서비스의 `loadFrameLabels`(`:267`)·`serveFrameImage`(`:443`) 에는 있다.
  실측: `rawSn=900`(APPROVED, `DE_IDENT_YN='F'`) 에 저장 → **201**(userLblSn=18). 대조로 같은 영상 프레임 조회 → **412** `"비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."`
- **재현/확인 경로**:
  ```bash
  curl -XPOST .../v1/portal/user-labels -d '{"sourceRawSn":900,"sourceSrcSn":429,...}'   # 201
  curl .../v1/portal/frames/429/labels                                                    # 412
  ```
- **영향**: PII 노출 없음(이미지·라벨 조회가 모두 막혀 있어 유의미한 라벨을 만들 수 없다). **일관성/데이터 정합** 축의 갭이며, 신고 해제 후 남는 무의미한 라벨 행이 문제.
- **수정 방향(제안)**: 정책 판단 필요 — ①`saveUserLabel` 앞에 동일 게이트를 추가해 412 로 통일하거나 ②"저장은 본인 작업본이므로 허용"을 의도로 못 박고 주석·문서에 남긴다. ⚠ 구현하지 않음. (CLAUDE.md 차단 범위 목록에 write 경로가 없어 **확정 정책 위반으로 단정하지 않는다**)

### [F-ISSUE-27] TC-PORTALUP-023 인접 — **배포된 backend jar 가 워킹트리 소스보다 오래되어** 포털 정렬 allowlist 가 라이브에 없다 (`sort=status` → 500)
- **심각도**: HIGH (검증 환경 신뢰성 · 라이브 노출 기준으로는 CWE-209/770)
- **기대 동작(기대효과)**: 검증 대상 스택은 검증 기준 소스(qa-0801)와 동일 산출물이어야 한다. 소스는 `PortalUploadController.safeSort/safeFrameSort` → `SortAllowlist.resolve`(strict)로 미등록 정렬 키를 **400** 으로 거부한다(A-ISSUE-61 수정분, `PortalUploadController.java:164-174`).
- **현재 동작(이슈 내용)**: 컨테이너 jar 에 그 배선이 없다.
  ```
  docker inspect klid-backend → created 2026-08-01T14:10:06Z, /app/app.jar mtime Aug 1 14:05
  jar!BOOT-INF/classes/.../PortalUploadController.class : safeSort=False, SortAllowlist=False, capped=True
  jar!BOOT-INF/classes/.../SortAllowlist.class          : PORTAL_UPLOAD=False, PORTAL_UPLOAD_FRAME=False, DEIDENT_REPORT=False
  git status --short | wc -l → 80 (미커밋 수정), 그중 SortAllowlist.java·PortalUploadController.java·PortalLabelController.java 포함
  ```
  라이브 실측:
  ```
  GET /v1/portal/uploads?sort=status,asc      → 500 INTERNAL_ERROR
     backend log: ERROR GlobalExceptionHandler - unhandled exception
       org.springframework.data.mapping.PropertyReferenceException: No property 'status' found for type 'LsPortalUld'
  GET /v1/portal/uploads?sort=filePathNm,desc → 200, 응답 sort.sorted=true  (응답에 노출되지 않는 내부 저장경로 컬럼으로 실제 정렬)
  GET /v1/portal/uploads?sort=orgnlFileNm,asc → 200 (소스에서는 PII 사유로 의도적 제외 키)
  ```
- **재현/확인 경로**: 위 3개 curl + `docker cp klid-backend:/app/app.jar` 후 클래스 문자열 검사.
- **영향**: ①**검증 무효화 위험** — 이 스택 위의 "실동작 PASS" 가 소스 기준 동작을 보증하지 못한다(이번 F-3/F-6 24건은 무관함을 개별 확인했으나, 정렬·SAM2·`PortalLabelController` 를 다루는 다른 파트는 영향권). ②배포본 자체 기준으로는 내부 엔티티명이 ERROR 로그에 적재되는 CWE-209 + 정렬 항목 상한 부재(CWE-770)가 그대로 살아 있다.
- **수정 방향(제안)**: 검증 착수 전 **소스 재빌드 → 이미지 재배포**를 환경 게이트(§3-1)에 추가하고, `_raw/stack-bringup.md` 에 "배포 산출물 커밋 해시 = 검증 기준 해시" 대조 항목을 명시한다. 아울러 워킹트리의 미커밋 80파일을 커밋/정리해 기준을 확정한다. ⚠ 코드는 건드리지 않음.

### [F-ISSUE-28] TC-PORTALUP-030 인접 — 삭제 상태 검사에 **락이 없고 `UPLOADED` 도 삭제 허용**되어 비동기 추출 러너와 경합 가능
- **심각도**: LOW
- **기대 동작(기대효과)**: 주석이 선언한 대로 "처리 완료(READY/FAILED) 후 허용"이어야 하고, 상태 판정 후 파일 삭제까지 그 상태가 유지돼야 한다(프레임 추출 러너가 쓰는 파일을 삭제 중 제거하면 파일-DB 불일치).
- **현재 동작(이슈 내용)**:
  ```java
  // PortalUploadService.java:268-276
  LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(uldSn, portalUserNo)   // 락 없음
          .orElseThrow(this::forbidden);
  if (LsPortalUld.STTS_PROCESSING.equals(uld.getUldSttsCd())) { throw CONFLICT; }   // PROCESSING 만 차단
  ```
  실측: `uld_stts_cd='UPLOADED'` 로 두고 `DELETE` → **204**(삭제됨). `UPLOADED` 는 TUS 업로드 완료 후 프레임 추출 시작 **직전** 상태라, 검사 통과 직후 러너가 `UPLOADED→PROCESSING` 으로 전이하면 삭제와 추출이 겹친다(TOCTOU). `findByUldSnAndPortalUserNo` 에 `@Lock` 없음(레포지토리 확인).
- **재현/확인 경로**: `UPDATE ls_portal_uld SET uld_stts_cd='UPLOADED' WHERE uld_sn=N;` → `DELETE /v1/portal/uploads/N` → 204. 경합 자체는 추출 러너 기동과 동시 실행이 필요해 결정적 재현은 미수행(관찰은 상태 허용 사실까지).
- **영향**: 데이터 정합(고아 프레임 행/파일), 확률 낮음(CWE-362).
- **수정 방향(제안)**: ①차단 상태를 `PROCESSING` + `UPLOADED` 로 넓히거나 ②조건부 UPDATE(`... SET uld_stts_cd='DELETING' WHERE uld_sn=? AND uld_stts_cd IN ('READY','FAILED')`)로 원자 클레임 후 삭제. 최소한 주석과 코드의 상태 집합을 일치시킨다. ⚠ 구현하지 않음.

### [F-ISSUE-29] TC-PORTALUP-023 인접 — 자산 상세(`GET /v1/portal/uploads/{uldSn}`)의 **프레임 목록이 무페이징**
- **심각도**: LOW
- **기대 동작(기대효과)**: 목록성 응답은 페이징하거나 상한을 둔다(api-design.md). 별도 엔드포인트 `/{uldSn}/frames` 는 이미 100 하드캡이 있는데 상세는 없다.
- **현재 동작(이슈 내용)**:
  ```java
  // PortalUploadService.java:176-182
  List<LsPortalUldFrme> frames = frmeRepository.findAllByUldSnOrderByFrmeNo(uld.getUldSn());
  return PortalUploadDetailResponse.of(uld, frames);   // frames 전건을 DTO 로 직렬화
  ```
  영상 자산의 프레임 수는 `portal.upload.max-frames`(기본 2,000)까지 가능하므로 상세 1회 호출이 최대 2,000 항목을 반환한다. 실측(이미지 자산 1프레임)에서는 노출되지 않는 경로라 코드/설정 기준.
- **재현/확인 경로**: 프레임 다수(수백~2,000)인 VIDEO 자산에 `GET /v1/portal/uploads/{uldSn}` → 응답 `frames` 배열 길이 = 전체 프레임 수.
- **영향**: 자원 소진(CWE-770). 상한이 2,000 으로 유한해 심각도 LOW.
- **수정 방향(제안)**: 상세 응답에서 `frames` 를 제거(별도 `/frames` 페이징 엔드포인트로 유도)하거나 상위 N 건 + `frmeCnt` 만 반환. ⚠ 구현하지 않음.

---

## 6. 남긴 테스트 데이터 (다음 회차 참고)

| 테이블 | 내용 |
|---|---|
| `ls_portal_user_label` | `portal_user_no` 3001(userLblSn 1~8,10~16,18,19) · 3002(9) — F-3 검증용. 10~16 은 stale/손상 좌표 주입분 |
| `ls_portal_uld` / `_frme` | 3011(`uldSn=63`), 3012(`uldSn=66`) 잔존. `64/65/77/79` 는 검증 중 삭제 완료 |
| 파일시스템 | `images/qa-symlink-test.png`(심링크)·`images/qa-dir-test/` 는 **검증 후 제거 완료** |
