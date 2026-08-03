# E 클러스터 part7 — E-9. 프레임 개인정보 메타 (TC-META-030~044, 신규 045~047)

- 회차: 2026-08-03 **3차** · 담당 범위: `docs/test-cases/E-augment-resolution-export-meta.md` **E-9 절**(구 309~336행)
- 총 15건(기존) + **신규 3건 추가**(TC-META-045/046/047) = 18건 · 폐기 0건
- 환경: `_raw/stack-bringup.md` 기준 풀스택 기동(backend/mock-server/ai-server/frontend/DB 전부 healthy, HEAD `e065da42` 재빌드 이미지). 외부 연동 4종 전부 mock-server(:9400) 실배선.
- 공용 데이터: `_raw/pipeline-drive.md` 의 rawSn=101(APPROVED, 프레임 468~477), 추가로 raw 900(`DE_IDNTF_YN='F'` 신고 구간, 프레임 429~433) · raw 906(APPROVED, 프레임 464/465) · raw 158(미검수 파생) · raw 164/165(파생) 사용.
- 인증: `POST /v1/dev/tokens` REVIEWER(1001) / WORKER(2001).
- **코드·설정·테스트 파일 수정 0건.** 카탈로그(E-9 절)만 정정·신설. 빌드/테스트 미실행.

---

## 0. ★ 이번 회차 핵심 — V163 정책 반전(커밋 `0d290c4e`)의 카탈로그 반영 여부

| 반전 축 | 확정 정책(코드·CLAUDE.md 실측) | 반전 전 카탈로그 기술 | 반영 여부 |
|---|---|---|:--:|
| ① export 개인정보 3필드 | `ORIGINAL`=**전부 null**(판정 안 함) / `DEIDENTIFIED`=**수동값 우선**(미입력 시 Y/N/N) | TC-META-034 "anonymity 는 export 미덮음 — `ExportKind` 파생 유지" | **미반영 → 정정함** |
| ② 프레임 GET 프리필 원천 | `ExportPrivacyPolicy` 비식별 **기본상수(Y/N/N)** | TC-META-030 "미저장 시 파생(`PRVC_TYPE_CD=ANONY→Y`)" | **미반영 → 정정함** |
| ③ 입도 2축(video=`LS_DATA_RAW` V163 / image=`LS_DATA_SRC` V130) | 두 값이 달라도 모순 아님 | 카탈로그에 개념 자체 부재 | **미반영 → TC-META-047 신설** |
| ④ 신고 구간 PUT 412(단건·벌크) | `LabelAccessGuard#requireNotUnderDeidentReport`, 인가 이후 평가, GET 미차단 | 카탈로그에 케이스 부재 | **미반영 → TC-META-045/046 신설** |
| ⑤ 파생영상 **영상 축** 판정 계승(`LsDataRaw.copyPrivacyMetaFrom`) | 생성 시점 1회 스냅샷 | TC-META-044 는 **프레임 축만** 기술 | 부분 반영 → 영상 축은 E-ISSUE-126(영상 축 절 신설)로 이월 |

> `CLAUDE.md` 는 이미 최신이다(349~370행: "★export 개인정보 3필드 정책 — 비식별 산출물만 판정한다(2026-08-03 확정)", 파생 계승, 신고 리셋 2축, 게이트 차단범위 ⑧에 개인정보 메타 PUT 412 명시). **문서 드리프트는 CLAUDE.md 가 아니라 테스트케이스 카탈로그 쪽에만 있었다.**

---

## 1. 판정 결과

| ID | 판정 | 근거 확인 | 비고 |
|----|:--:|---|---|
| TC-META-030 | PASS | [실동작] `GET /v1/frames/468/privacy-meta` → `{"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}`. DB `ls_data_src(468)` 3필드 전부 NULL, 영상 `raw 101.prvc_type_cd='PRVC'` — **구 파생 규칙이라면 anonymity=N** 이 나와야 하므로 상수 프리필임이 반증적으로 확정 | 기대결과 정정(파생→기본상수) |
| TC-META-031 | PASS | [실동작] PUT `{N,Y,Y}` → 200 + DB 반영, PUT `{null,null,null}` → 200 + DB 3필드 NULL + 응답은 기본상수 Y/N/N | |
| TC-META-032 | PASS | [실동작] `"true"` → 400 `anonymity 는 Y 또는 N 이어야 합니다.` / **`"Y\n"` 도 400**(`\A[YN]\z`) | 표기 정정(`^[YN]$`→`\A[YN]\z`) |
| TC-META-033 | PASS | [실동작] path 464 vs body 465 → 400 `path 의 srcSn 과 body 의 srcSn 이 다릅니다.` | |
| TC-META-034 | PASS | [실동작] **정책 반전 실증** — raw101 v4 산출물: `orgnl/0000.json` video·image 3필드 전부 `null`; `deid/0000.json` image(468)=`N/Y/Y`(저장한 수동값 그대로), image(469)=`Y/N/N`(미입력 → 기본상수). 구 기대결과("수동값이 export 를 안 덮음")는 **반증됨** | 기대결과 전면 정정 |
| TC-META-035 | PASS | [정적+실동작] `updateBulk`(124-166) = `findAllById` 1회 → rawSn distinct 인가·게이트 1회 → `saveAll` 1회. 2건 벌크 200, 로그 `bulk-updated count=2 rawSns=1` | 쿼리 카운트 계측은 미수행 |
| TC-META-036 | PASS | [실동작] `items=[464, 9999999]` → 404 `프레임을 찾을 수 없습니다.` | |
| TC-META-037 | PASS | [실동작] WORKER(2001) `items=[468(본인), 464(타 영상)]` → 403. `items=[9999999, 464]` → **404 先** (순서 보존) | |
| TC-META-038 | PASS | [실동작] 위 404 실패 직후 DB 재조회 시 srcSn=464 값 변경 없음(NULL 유지) — 전체 롤백 확인 | |
| TC-META-039 | PASS | [실동작] 벌크 PUT(468 포함, APPROVED raw101) → 60초 디바운스 flush → 로그 `AsyncDatasetExportRunner ... re-export(+notify) starting rawSn=101 forceRegenerate=true` → `export succeeded version=5` → **그 뒤** `ControlNotifyService TASK_MODIFIED sent rawSn=101 ... reExport=true` (export 선행 → 통지 순서 실측) | |
| TC-META-040 | PASS | [실동작] `{"items":[]}` → 400 `items 는 1건 이상이어야 합니다.` / 5001건 → 400 `items 는 5000건 이하여야 합니다.` | 상한 5000 명시 |
| TC-META-041 | PASS | [실동작] 토큰 없음 → 401(프레임·영상 축 모두). WORKER 미배정 프레임 GET/PUT → 403. **신고 구간(raw900) 프레임을 미배정 WORKER 가 PUT → 412 가 아니라 403**(상태 오라클 미발생, CWE-209) | |
| TC-META-042 | PASS | [실동작] backend 로그 실측: `[FramePrivacyMeta] updated srcSn=468 rawSn=101`, `[FramePrivacyMeta] bulk-updated count=2 rawSns=1` — Y/N 판단값 미출력 | |
| TC-META-043 | PASS | [실동작] 단건 PUT(468) → v4/v5 새 버전 폴더 전량 재생성 후 통지. **미검수 영상(raw158, 상태행 없음) 프레임 567 단건 PUT → export 0건·통지 0건**(반대 케이스도 실증) | |
| TC-META-044 | PASS | [실동작] raw165(부모 906) 프레임 601/602 = `N/Y/Y` — 부모 프레임 464/465 에 저장돼 있던 수동값을 그대로 복사. 부모 미입력이던 raw153/158 프레임은 전부 NULL. 생성 후 부모 정정은 미전파(스냅샷) | |
| TC-META-045 (신규) | PASS | [실동작] 신고 구간 raw900 프레임 429 단건 PUT → **412** `비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요.` (REVIEWER 토큰 — 역할 무관 확인) | 신설 |
| TC-META-046 (신규) | PASS | [실동작] 벌크 `[468(정상), 429(신고)]` → **412 전체 거부** + 468 값 변경 없음. 같은 프레임 **GET 은 200** | 신설 |
| TC-META-047 (신규) | PASS | [실동작] raw101 video 축=`N/Y/Y`, 프레임 469 미입력 → v4 `deid/0001.json` = video `N/Y/Y` / image `Y/N/N` (입도 차이 그대로 산출). ⚠ 역방향 조합은 E-ISSUE-122 참조 | 신설 |

**집계**: PASS 18 · FAIL 0 · PARTIAL 0 · BLOCKED 0 · N/A 0 · 확인필요 0 (폐기 0 — 분모 18)

> 제품 동작은 전건 확정 정책과 일치했다. 다만 **카탈로그가 반전 이전 정책을 들고 있었고**(TC-META-030·034), 반전과 함께 들어온 신규 동작 3종이 카탈로그에 없었다 → 아래 이슈로 기록 + 담당 라인범위 내 정정 완료.

---

## 2. 이슈

### [E-ISSUE-121] TC-META-034 / TC-META-030 — 카탈로그가 2026-08-03 반전 **이전** 정책을 기대값으로 들고 있었다 (정정 완료)
- **심각도**: HIGH (카탈로그 정합 — 다음 전수 검증에서 정상 동작이 "결함"으로 재발견될 축)
- **기대 동작(기대효과)**: 카탈로그 기대결과는 확정 정책과 일치해야 한다. 어긋나면 ①검증자가 정상 동작을 FAIL 로 올리고 ②그 "수정"이 폐기된 정책을 되살린다(이 저장소의 반복 사고 패턴).
- **현재 동작(이슈 내용)**: 정정 전 TC-META-034 = *"화면·기록용만 — export `image.anonymity` 는 `ExportKind` 파생 유지"*, TC-META-030 = *"미저장 시 파생(`PRVC_TYPE_CD=ANONY→Y` 등)"*. 실제는 정확히 반대·소멸:
  - `ExportPrivacyPolicy.resolve()` (92-97) — `kind != DEIDENTIFIED` → `null`, 아니면 `manualYn` 우선 + 기본상수 폴백
  - `FramePrivacyMetaService.toEffective()` (190-196) — `firstNonBlank(src.getAnonyInclYn(), ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY)`; 서비스가 **영상 행을 더 이상 읽지 않는다**(`VideoRepository` 의존 제거)
- **재현/확인 경로**: (실행함) `PUT /v1/frames/468/privacy-meta {N,Y,Y}` → 디바운스 flush 후 `docker exec klid-backend cat /app/storage/raw/seed/101/v4/deid/0000.json` → `image = N/Y/Y`, `v4/orgnl/0000.json` → 3필드 `null`.
- **영향**: 문서 정합. 방치 시 "수동값이 export 를 덮는다"를 결함으로 오판 → 억제 로직 부활 위험.
- **수정 방향(제안)**: **이번 회차에 E-9 절에서 직접 정정 완료**(TC-META-030·034 기대결과 재작성, 절 머리말에 확정 정책 4줄 추가). 남은 조치는 파일 상단 `## 변경 이력` 표에 3차 행 추가인데, 이는 본 파트 담당 라인범위(309행~) 밖이라 **병합 담당이 반영**해야 한다. (같은 회차 part6 이 TC-EXPORT-022/023 을 동일 방향으로 이미 정정했음을 확인 — 두 정정은 서로 정합한다.)

### [E-ISSUE-122] TC-META-047 — 영상 축 미입력 시 기본상수(`privacy_included=N`)가 프레임 수동값(`Y`)과 **모순**되어 영상 단위 과소 신고가 나간다
- **심각도**: MEDIUM (데이터 정합 / 개인정보 과소 선언 — CWE-359 인접)
- **기대 동작(기대효과)**: 커밋 `0d290c4e` 와 `CLAUDE.md`(358행)가 명시한 불변식 — *"`image="Y"` / `video="N"` 은 정책이 정당화한 방향의 **역방향**이라 논리적으로 성립할 수 없는 조합이며 실질은 개인정보 잔존의 **과소 신고**"*. 그래서 파생영상에 대해 `LsDataRaw.copyPrivacyMetaFrom` 을 도입했다.
- **현재 동작(이슈 내용)**: 그 불변식은 **파생 계승 경로에서만** 닫혔고, 일반 영상에서는 열려 있다. 영상 축을 입력하지 않으면 `ExportPrivacyPolicy` 가 기본상수(`privacy_included=N`, `anonymity=Y`)를 넣기 때문에, 프레임 축에 `Y` 를 선언해도 video 블록은 "개인정보 없음"으로 나간다.
  ```
  # v5/deid/0000.json (raw101, video 축 수동값 삭제 · 프레임 468 = N/Y/Y)
  video: {'anonymity': 'Y', 'pseudonymity': 'N', 'privacy_included': 'N'}   ← 기본상수
  image: {'anonymity': 'N', 'pseudonymity': 'Y', 'privacy_included': 'Y'}   ← 사람이 선언한 사실
  ```
  같은 조합이 **파생에서도 재발**한다: raw165(부모 906) — 프레임 601/602 는 부모 프레임값 `N/Y/Y` 를 계승했으나 부모 영상 축이 NULL 이라 영상 축도 NULL → 산출 시 video 는 다시 기본상수 N.
- **재현/확인 경로**:
  ```bash
  # 영상 축은 비우고 프레임 축만 Y 선언
  curl -X PUT .../v1/videos/101/privacy-meta -d '{"anonymity":null,"pseudonymity":null,"privacyIncluded":null}'
  curl -X PUT .../v1/frames/468/privacy-meta -d '{"srcSn":468,"anonymity":"N","pseudonymity":"Y","privacyIncluded":"Y"}'
  # 60초 디바운스 후
  docker exec klid-backend cat /app/storage/raw/seed/101/v5/deid/0000.json | jq '{video:.video.privacy_included, image:.image.privacy_included}'
  # → {"video":"N","image":"Y"}
  ```
- **영향**: 관제/데이터마트가 영상 단위 1행을 UPSERT 하므로, 프레임 단위로 "개인정보 잔존"이 선언된 영상이 **영상 단위로는 '없음'** 으로 집계된다. 정책이 스스로 "성립 불가"로 규정한 조합이 상시 발생 가능하다.
- **수정 방향(제안)**: 셋 중 택1 — ⓐ 영상 축 미입력일 때 기본상수 대신 **프레임 축 수동값의 OR 집계**(어느 프레임이든 `privacy_included=Y` 면 video 도 Y)로 폴백 ⓑ 프레임 축 저장 시 영상 축이 미입력이면 **화면에서 영상 축 입력을 요구**(FE 게이트) ⓒ 정책상 허용으로 확정한다면 `CLAUDE.md`·`ExportPrivacyPolicy` 주석의 "성립 불가능한 조합" 문장을 "파생 계승 한정"으로 좁혀 기술한다. **구현은 하지 않는다** — 어느 쪽이든 사용자 확정 필요.

### [E-ISSUE-123] TC-META-042 — 프레임 축 개인정보 선언 **변경**에는 행 단위 감사가 없다 (영상 축·신고 리셋과 비대칭)
- **심각도**: MEDIUM (OWASP A09 — 감사 부재)
- **기대 동작(기대효과)**: 같은 라운드가 세운 기준 — *"PII 표기를 되돌리는 행위이므로 **행 단위 감사**가 필요하다. 로그만으로는 부족하다"*(`VideoPrivacyMetaService.auditPrivacyMetaUpdate` javadoc, `DeidentReportService` 5-1 주석). 그 기준대로 ①영상 축 PUT → `LS_TASK_EVENT_LOG(PRIVACY_META_UPDATE)` ②신고에 의한 프레임 축 리셋 → `LS_DATA_LBL_HSTRY` 프레임당 1행 ③신고에 의한 영상 축 리셋 → `LS_TASK_EVENT_LOG(PRIVACY_META_RESET)` 이 남는다.
- **현재 동작(이슈 내용)**: **사람이 프레임 축 값을 바꾸는 경로만 행 단위 이력이 없다.** `FramePrivacyMetaService.applyAndNotify`(170-183)·`updateBulk`(124-166) 는 `log.info` 한 줄뿐이고 이력 테이블에 쓰지 않는다.
  ```java
  src.updatePrivacyMeta(anonymity, pseudonymity, privacyIncluded);
  srcRepository.save(src);
  log.info("[FramePrivacyMeta] updated srcSn={} rawSn={}", src.getSrcSn(), rawSn);   // ← 이게 전부
  ```
  실측: raw101 에 프레임 PUT 을 여러 번 했으나 `ls_task_event_log`(raw_data_id=101)에는 영상 축 `PRIVACY_META_UPDATE` 1행만 존재, `ls_data_lbl_hstry` 에도 대응 행 없음.
- **재현/확인 경로**: `PUT /v1/frames/468/privacy-meta` 후 `select * from ls_task_event_log where raw_data_id=101;` / `select * from ls_data_lbl_hstry where src_sn=468 order by 1 desc limit 5;` → 변경 이력 없음.
- **영향**: "누가 언제 이 프레임을 '개인정보 없음'으로 선언했는가"를 사후 추적할 수 없다. 리셋(자동)은 추적되는데 선언(수동)은 추적 안 되는 비대칭이라 감사 목적 자체가 반쪽이다.
- **수정 방향(제안)**: 프레임 축 PUT/벌크에서 `LsDataLblHstry.recordPrivacyMetaResetEvent` 와 같은 축의 "변경" 이벤트(라벨 델타 0건, `V139` 뷰 필터로 관제 미노출)를 프레임당 1행 남긴다. 판단값(Y/N)은 담지 않고 actor·시각·changed 여부만(영상 축과 동일 기준). 벌크는 행 수가 커질 수 있으므로 `saveAll` 배치.

### [E-ISSUE-124] TC-META-030 — 프레임 응답에 **출처(MANUAL/DERIVED)가 없어** 프리필 상수가 사람의 판정으로 승격될 수 있다 (self-fill 축)
- **심각도**: MEDIUM (§1-3 self-fill 금지 원칙 — 외부/사람 입력 없이 상수가 산출물에 사실처럼 실림)
- **기대 동작(기대효과)**: 조회 프리필은 "아직 판정 안 함"을 뜻하고, export 에 사실로 실리는 값은 사람이 고른 값이어야 한다. 영상 축은 이 위험을 인지해 응답에 `anonymitySource`/`pseudonymitySource`/`privacyIncludedSource`(`MANUAL`|`DERIVED`)를 실어 FE 가 구분할 수 있게 했다.
- **현재 동작(이슈 내용)**: 프레임 축 응답은 값 3개뿐이다 — `{"srcSn":468,"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}`. FE 는 이 `Y/N/N` 이 **저장값인지 상수 프리필인지 구분할 수단이 없고**, 폼을 그대로 되돌려 보내면 상수가 `MANUAL` 로 굳어 `deid/*.json` 의 `image` 블록에 사실처럼 실린다(반전 이후 프레임 수동값이 export 를 덮으므로 위험도가 반전 전보다 커졌다). `FramePrivacyMetaResponse` 에 source 필드 없음 — 커밋 `0d290c4e` 자신이 "미해소 — 프레임 패널의 항목별 출처(MANUAL/DERIVED) 뱃지 부재, 응답 계약 변경 필요"로 기재.
- **재현/확인 경로**: `GET /v1/frames/468/privacy-meta` 응답(위)과 `GET /v1/videos/101/privacy-meta` 응답(`...Source` 3필드 포함) 대조.
- **영향**: 화면을 열고 저장만 해도 전 프레임이 "익명=Y, 개인정보=N" 으로 확정 선언된다(과소 신고). BE 는 전송값의 출처를 알 수 없어 막지 못한다.
- **수정 방향(제안)**: `FramePrivacyMetaResponse` 에 영상 축과 동일한 3개 source 필드 추가(응답 추가는 하위호환) + FE 가 `DERIVED` 항목은 null 로 전송. 근본 차단이 필요하면 요청에 출처 축을 추가하는 계약 변경이 필요하며 이는 영상 축과 함께 결정할 사안.

### [E-ISSUE-125] 문서/주석 드리프트 — 폐기된 구 정책을 참조하는 서술 2건
- **심각도**: LOW
- **기대 동작(기대효과)**: Swagger·코드 주석이 확정 정책과 같은 사실을 말해야 한다(이 저장소의 "주석이 정책 갱신에 뒤처짐" 반복 패턴).
- **현재 동작(이슈 내용)**:
  1. `FramePrivacyMetaController.java:57-58,74-75` Swagger 설명이 여전히 *"없으면 **파생값**(프리필)을 반환", "수동값이 삭제되어 **파생값으로 폴백**"* 이라고 기술 — 실제 폴백 원천은 파생이 아니라 `ExportPrivacyPolicy` 비식별 기본상수다(클래스 javadoc 은 이미 정정돼 있어 **같은 파일 안에서 서로 다른 말**을 한다).
  2. `AugmentExtractPersist.java:104-107` 주석 *"두 컬럼에 같은 값을 넣으면 … export orgnl 벌이 `anonymity="N"` 으로 오표기된다"* — 반전 이후 `ORIGINAL` 은 `anonymity` 를 **판정하지 않고 항상 null** 이라 이 근거는 성립하지 않는다(컬럼 분리 자체는 뷰 불변식 때문에 여전히 유효).
- **재현/확인 경로**: 위 file:line Read.
- **영향**: 후속 작업자가 "파생 폴백"을 되살리거나, orgnl anonymity 를 근거로 잘못된 결론을 낼 수 있다.
- **수정 방향(제안)**: Swagger `description` 을 "미저장 필드는 비식별 기본상수(Y/N/N) 프리필"로, 증강 주석의 근거를 "마트 뷰의 두 경로 상이 불변식"만 남기고 anonymity 문장 제거.

### [E-ISSUE-126] 카탈로그 커버리지 갭 — 영상 단위 개인정보 메타 API(V163 신규)에 테스트케이스가 **0건**
- **심각도**: MEDIUM (카탈로그 커버리지)
- **기대 동작(기대효과)**: 신설 화면·API 는 경계·오류·하위호환 케이스와 함께 카탈로그에 들어와야 한다(`CLAUDE.md` 문서 동기화 규칙).
- **현재 동작(이슈 내용)**: `GET/PUT /v1/videos/{rawSn}/privacy-meta`(`VideoPrivacyMetaController`/`VideoPrivacyMetaService`, V161·V163)는 이번 반전의 **핵심 신설물**인데 E 클러스터 어디에도 케이스가 없다(`grep -n 'videos/.*privacy-meta' docs/test-cases/` → 0건). 이번 검증에서 실동작으로 확인된 것만 해도: 프리필+`...Source` 3필드(MANUAL/DERIVED), 전체 교체 PUT, `Y/N` 화이트리스트 400(필드명만 노출), 미존재 404, WORKER IDOR 403 / 미인증 401, 신고 구간 412, APPROVED 후 수정 시 `TaskModifiedEvent(videoLevel)` → export 재생성 후 통지(로그 `frames=0 videoLevel=1 reExport=true`), `LS_TASK_EVENT_LOG(PRIVACY_META_UPDATE, rsn='영상 개인정보 선언 변경')` 감사 1행, 파생영상 계승(raw164 가 부모 raw101 의 `N/Y/Y` 를 계승), 잠금 순서(`raw 행락 → advisory`, `FOR SHARE` 금지 — `LockOrderGuardTest` 정적 가드).
- **재현/확인 경로**: 위 항목 전부 이번 회차에 실호출로 관측(본 문서 §1·§3).
- **영향**: 반전의 절반(영상 축)이 회차 검증 대상 밖에 있어, 회귀가 나도 카탈로그로는 잡히지 않는다.
- **수정 방향(제안)**: E-9 다음에 **E-10 "영상 개인정보 메타(VideoPrivacyMetaService — 영상 단위)"** 절을 신설하고 위 12축을 케이스화(ID 는 TC-META-060~ 대역 권장). 본 파트는 담당 라인범위 밖이라 신설하지 않고 제안만 한다.

### [E-ISSUE-127] E-9 전 행 — 근거 `file:line` 드리프트 (정정 완료)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 정확도가 이 카탈로그의 존재 이유다.
- **현재 동작(이슈 내용)**: 15행 중 **14행**의 라인 범위가 어긋나 있었다(신고 게이트 추가·프리필 재작성으로 서비스가 대폭 이동). 주요 예: TC-META-030 `66-70`→`77-80,190-196`, TC-META-031 `73-78`→`96-103`, TC-META-033 `85-88`→`93-96`, TC-META-035 `100-137`→`124-166`, TC-META-036 `101-118`→`137-142`, TC-META-037 `119-123`→`144-150`, TC-META-038 `99`→`124`, TC-META-039 `126-133,151-156`→`153-160`, TC-META-040 `104-107`→`111-116`·`19-25`→`20-25`, TC-META-041 `66-75`→`77-80,96-103`, TC-META-043 `151-156`→`177-182`, TC-META-044 `278-289`→`284-289`·`104-115`→`109-113`. 또 `NiaJsonBuilder.java` 는 경로가 `dataset/export/**json**/NiaJsonBuilder.java` 이고 개인정보 3필드는 `150-153` 이 아니라 `158-160` 이다.
- **재현/확인 경로**: `grep -n` 로 각 심볼 위치 확인(본 문서 작성 시 전 행 수행).
- **영향**: 근거 추적 실패 → 다음 회차 재검증 비용 증가.
- **수정 방향(제안)**: **정정 완료**(E-9 절 전 행 재작성).

---

## 3. 반증(적대) 시도 기록 — 무엇을 깨보려 했나

| 반증 가설 | 방법 | 결과 |
|---|---|---|
| "프리필이 여전히 `PRVC_TYPE_CD` 파생일 것"(구 카탈로그) | `PRVC_TYPE_CD='PRVC'` 인 raw101 프레임 GET → 파생이면 `anonymity=N` | 반증됨 — `Y` 반환(상수). 구 기대값 폐기 확정 |
| "수동값은 export 를 안 덮을 것"(구 카탈로그) | 프레임 수동값 저장 → 재export 대기 → v4 `deid` JSON 대조 | 반증됨 — 수동값 그대로 실림 |
| "`ORIGINAL` 에도 값이 실릴 것" | v4/v5 `orgnl/*.json` 대조 | 반증됨 — video·image 3필드 전부 null |
| "412 게이트가 인가보다 먼저 평가돼 상태 오라클이 될 것"(CWE-209) | 미배정 WORKER + 신고 영상 프레임 PUT | 403 우선 — 오라클 없음 |
| "벌크에서 신고 프레임이 섞이면 나머지는 저장될 것" | `[정상, 신고]` 벌크 PUT 후 DB 대조 | 412 전체 거부 + 정상 프레임 무변경 |
| "GET 도 412 로 막혀 화면이 안 뜰 것" | 신고 프레임 GET | 200 (의도된 정책과 일치) |
| "미검수 영상도 export 를 재생성할 것" | raw158(상태행 없음) 프레임 PUT 후 export 테이블 | 0건 — 발행 안 함 |
| "통지가 export 보다 먼저 나갈 것" | flush 로그 시퀀스 | export succeeded → TASK_MODIFIED sent 순서 확인 |
| "`Y\n` 이 `@Pattern` 을 통과할 것"(`^$` 였다면 통과) | `"anonymity":"Y\n"` PUT | 400 — `\A[YN]\z` 로 이미 하드닝됨 |
| "파생 프레임 상속이 사후 재동기화될 것" | 부모 정정 후 기존 파생 값 확인 | 미전파(스냅샷) — 설계와 일치, 결함 아님 |
| **"video/image 모순이 실제로는 못 나올 것"** | 영상 축 비우고 프레임 축만 Y | **모순 재현됨 → E-ISSUE-122** |

---

## 4. 이전 회차 이슈 해소 여부

- 1차(2026-08-01) `ISSUES.md` 에 **E-9(TC-META-030~044) 관련 `E-ISSUE-` 블록은 0건**이다(1차 `E-result.md:1318-1332` 에서 15건 전건 PASS). → **이월 이슈 없음.**
- 다만 1차의 TC-META-034 PASS 근거(*"srcSn=296 에 `ANONY_INCL_YN='Y'` 를 저장해도 export 는 kind 파생만 실었다"*)는 **구 정책 하의 사실**이며 현재는 성립하지 않는다. 1차 결과를 이번 회차와 대조할 때 이 행은 "회귀"가 아니라 **정책 반전에 따른 기대값 교체**로 읽어야 한다.
- 2차(2026-08-02)는 F/G/H 및 targeted 만 수행해 E-9 대조 대상 없음.

## 5. 카탈로그 정정 요약 (본 파트가 수행한 편집)

| 구분 | 건수 | 대상 |
|---|:--:|---|
| 기대결과 정정(정책 반전 반영) | 2 | TC-META-030(프리필 원천) · TC-META-034(export 반영 방향) |
| 표기·상세 정정 | 5 | TC-META-032(`\A[YN]\z`) · TC-META-035/039/040/041/043/044 문구 보강 |
| 근거 `file:line` 정정 | 15행 전건 | E-ISSUE-127 |
| 절 머리말 신설 | 1 | 확정 정책 4개 축 + 영상 축 범위 밖 명시 |
| 신규 케이스 | 3 | TC-META-045(단건 412) · TC-META-046(벌크 412 + GET 200) · TC-META-047(입도 2축) |
| 폐기 | 0 | — |

> ⚠ **병합 담당 조치 필요**: 파일 상단 `## 변경 이력` 표에 3차 행 추가(정정 22 / 신규 3 / 폐기 0, "V163 개인정보 3필드 정책 반전 반영")는 본 파트 담당 라인범위(309행~) 밖이라 수행하지 않았다. 또 `## E-9` 절 케이스 수가 15→18 로 늘어 파일 머리말의 총 케이스 수(232) 및 README 집계도 함께 갱신이 필요하다.

## 6. 검증 중 남긴 데이터 변경 (원복 여부)

| 대상 | 변경 | 원복 |
|---|---|:--:|
| `ls_data_raw(101)` 개인정보 3필드 | `N/Y/Y` 저장 → 삭제 | ✅ 원복(NULL) |
| `ls_data_src(464,465)` (raw906) | `N/Y/Y` 저장 → 삭제 | ✅ 원복(NULL) |
| `ls_data_src(567)` (raw158) | `N/N/N` 저장 → 삭제 | ✅ 원복(NULL) |
| `ls_data_src(468)` (raw101) | `N/Y/Y` — **part6(export 절)이 01:42:26 에 먼저 설정**한 값과 동일. 그쪽 검증 데이터일 수 있어 임의 삭제하지 않음 | ⛔ 유지 |
| raw101 export | 정책 검증 과정에서 v3→v5 로 버전 증가(정상 동작인 재생성) | 해당 없음 |
