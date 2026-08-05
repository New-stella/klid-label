# E-part6 — 3차 검증 (2026-08-03/04)

검증자: 담당 에이전트(E-part6) · 대상: `docs/test-cases/E-augment-resolution-export-meta.md` §E-7(18건, TC-EXPORT-020~037) + §E-8(20건, TC-META-001~020, TC-META-009 폐기 제외 실질 19건) = 38건(폐기 제외 실질 37건)

## 사용한 실증 데이터

- 공용 시나리오 rawSn=101(`_raw/pipeline-drive.md`) — APPROVED, export v1 SUCCEEDED(2026-08-03 15:09 기준)
- 본 검증 중 추가 실동작 유발: `PUT /v1/frames/468/privacy-meta`(프레임 개인정보 수동값), `PUT /v1/videos/101/environment-meta`(촬영환경 수동값) — 재export를 트리거해 v1→v3(privacy)→v5(environment)까지 순차 생성됨. 코드/설정/프로덕션 데이터는 수정하지 않았고, 검증 목적의 실제 API 호출만 수행(다른 병행 검증 에이전트의 활동으로 v2/v4도 함께 생성된 것을 로그로 확인 — rawSn=101이 여러 클러스터가 공유하는 시나리오 영상이라 정상적인 동시 사용).
- `docker exec klid-backend cat .../v{n}/{orgnl|deid}/0000.json` 실측 + `docker logs klid-backend`(ControlNotifyDebouncer/AsyncDatasetExportRunner/EnvironmentMetaService 로그) + `SELECT ... FROM ls_dataset_export/ls_dataset_video_meta` DB 조회.

## ★ 이번 회차 핵심 발견 — anonymity/pseudonymity/privacy_included 정책이 1차(2026-08-01) 이후 반전됨

커밋 `0d290c4e`(2026-08-03, "feat(privacy): 영상 단위 개인정보 메타 화면 + export 개인정보 3필드 정책 반전")가 `NiaJsonBuilder`/`VideoMetaMapper`의 개인정보 3필드 판정을 신설 `ExportPrivacyPolicy` 단일 판정기로 교체했다. 1차(2026-08-01) 검증 시점 코드(`ec5181a0`, 08-01)는 "ORIGINAL=N/DEIDENTIFIED=Y 고정, 수동 override 금지"였고 카탈로그 TC-EXPORT-022/023/030은 그 기대값을 그대로 담고 있었다. 3차 시점(08-03/04) 코드는 **"ORIGINAL=판정 안 함(null) / DEIDENTIFIED=수동값 우선, 미입력 시 기본상수(Y/N/N)"**로 정반대다. `ExportPrivacyPolicy.java` 클래스 주석에 정책 반전 경위와 "되돌리지 말 것" 경고가 명시돼 있다.

**카탈로그 3건(022/023/030)을 이 발견에 맞춰 직접 정정했다**(§10 예외 조항 — 카탈로그 정합 결함은 담당 라인범위 내 직접 수정 지시에 따름). 정정 내용은 아래 이슈 섹션 및 파일 자체(§E-7)에 반영됨.

## E-7. Export JSON 포맷 — 18건

| ID | 판정 | 근거 확인 | 근거 요약 |
|---|---|---|---|
| TC-EXPORT-020 | PASS | [실동작]+[정적] | rawSn=101 v1~v5 전 버전 JSON 최상위 키 순서 `info,dataset,licences,video,event,image,annotations,categories,type` 일관 확인. `NiaAnnotationDoc.java:24-37` `@JsonInclude(ALWAYS)`+`@JsonPropertyOrder` 코드 일치(라인 드리프트 없음, 1차와 동일 위치) |
| TC-EXPORT-021 | PASS | [실동작] | 동결 event 없는 rawSn=101 전 프레임 JSON `"event": null` 확인(키 always present) |
| TC-EXPORT-022 | **[카탈로그 정정]** PASS(신규 기대값 기준) | [실동작]+[정적] | **1차 기대값(수동 override 금지, ORIGINAL=N/DEID=Y 고정)은 2026-08-03 `0d290c4e`로 폐기됨.** 실측: srcSn=468에 `PUT /v1/frames/468/privacy-meta {"anonymity":"N","pseudonymity":"Y","privacyIncluded":"Y"}` → 재export v3 → deid `image.anonymity="N"`(수동값 그대로 반영), orgnl `image.anonymity=null`(판정 자체 없음, "N 고정"도 아님). `ExportPrivacyPolicy.resolve()`(원천=null 반환, DEID=manualYn 우선) 코드와 정확 일치. 카탈로그 기대결과·근거라인 정정 완료(신규: ORIGINAL=null/DEID=수동값 우선) |
| TC-EXPORT-023 | **[카탈로그 정정]** PASS(신규 기대값 기준) | [실동작]+[정적] | 동일 PUT으로 pseudonymity/privacyIncluded도 v3 deid에서 Y/Y로 정확 반영, orgnl은 null/null. 구 "파생 폴백(PRVC_TYPE_CD/PRVC_YN 파생)" 문구는 그 파생 로직 자체가 `ExportPrivacyPolicy` 도입으로 소멸해 폐기 — 미입력 시 고정 기본상수(N/N)로 대체됨. 카탈로그 정정 완료 |
| TC-EXPORT-024 | PASS | [정적] | `NiaJsonBuilder.buildDataset`(133-138, 1차 대비 +1라인 드리프트)이 `kind==DEIDENTIFIED`이고 `deidVideoPath=null`이면 `path=null`→dataset.src_path/name 모두 null. `VideoMetaMapper.toVideo`(61-62) `kindVideoPath=deidVideoPath(null)`→basename null도 동일. rawSn=101은 deid경로가 항상 존재해 라이브 재현은 못했으나(1차와 동일 사유) 결정론적 null 전파 로직은 명확 |
| TC-EXPORT-025 | PASS | [정적] | `buildAnnotations`(179-202, 1차 대비 라인 변동 없음) `try{...}catch(CustomException){skipped++}` 구조 그대로 — 코드 변경 없음(1차 이후 이 메서드 미수정 확인, git log) |
| TC-EXPORT-026 | PASS | [실동작]+[정적] | rawSn=101 JSON 실측: `type/format/location/pixel/cctv_height` 등 미보유 필드가 `null`로 키 유지. `NiaVideo.java:12`/`NiaImage.java:9` `@JsonInclude(ALWAYS)` 라인 정확 일치(드리프트 없음) |
| TC-EXPORT-027 | PASS(라인 드리프트 정정) | [실동작]+[정적] | JSON `"vd_description":null` 키 존재 확인. `NiaVideo.java:46`(일치)이나 `VideoMetaMapper.java:101`은 **드리프트** — 실제 vd_description 라인은 `114`(privacy 정책 반전으로 주석 추가되며 이동). 카탈로그 근거라인 101→114 정정 완료 |
| TC-EXPORT-028 | PASS | [실동작]+[정적] | rawSn=101 초기(수동 미입력) v1 export `weather/time_of_day/season` 전부 null 확인(SHT_DT 자동파생 없음). PUT으로 수동값(비/NGT/WINTER) 저장 후 재export(v5)에서 정확 반영(TC-META-017과 동일 실증 공유). `VideoMetaMapper.java:55-57` `firstNonBlank(raw,meta)` 코드 위치 라인만 44-57 범위로 소폭 조정(1차 46-59 대비, 근소한 드리프트라 유지) |
| TC-EXPORT-029 | PASS(라인 드리프트 정정) | [정적] | `VideoMetaMapper.firstNonBlank`(158-165, 1차 이후 privacy 코드 추가로 이동) 모두 blank→null 정규화. 카탈로그 근거라인 144-152→157-165 정정 완료 |
| TC-EXPORT-030 | **[카탈로그 정정]** PASS(신규 기대값 기준) | [실동작]+[정적] | 1차 기대값 "N/Y 고정 오버라이드"는 폐기. 실측: rawSn=101 video 블록 — orgnl `video.anonymity=null`(전 버전 일관), deid `video.anonymity="Y"`(영상 단위 수동값 `LS_DATA_RAW.ANONY_INCL_YN` 미설정 상태의 기본상수). 영상 단위 수동값을 별도로 설정하지 않아 기본값만 확인했으나(프레임 단위 수동값과는 별개 축, `VideoMetaMapper.java:74-79` 코드로 override 로직 확인), null/Y 분기 자체는 실측·정적 모두 일치. 카탈로그 정정 완료 |
| TC-EXPORT-031 | PASS | [정적] | `NiaJsonBuilder.prepareContext`(89-92, 1차 88-91 대비 근소 이동) `if(meta==null) throw CustomException(INVALID_INPUT,"영상 메타가 null 입니다.")` 일치 |
| TC-EXPORT-032 | PASS | [실동작]+[정적] | rawSn=101 전 버전 JSON `info.version="1.3"`, `type="instances"` 확인. `NiaJsonBuilder.java:38`(FORMAT_VERSION)/`:40`(TYPE_INSTANCES) 코드 존재 확인(1차 37/39/96 대비 1라인 드리프트, 경미해 미정정) |
| TC-EXPORT-033 | PASS | [실동작]+[정적] | rawSn=101 image.file_name="0000.jpg"~"0009.jpg"(FRM_NO 4자리 zero-pad). `ExportFileNaming.imageFileName(long)` 단일 지점 사용(`NiaJsonBuilder.java:146`) 확인, 규칙과 실측 파일명 일치 |
| TC-EXPORT-034 | PASS | [실동작] | image.frame_num=0(rawSn=101 srcSn=468, 단일 프레임만 확인 — 1차는 0/30/60/90/120으로 다프레임 실증했으나 이번 시나리오는 VDO_FRM_NO가 0으로 고정된 소규모 fixture). 파일명(0000.jpg=FRM_NO)과 frame_num(0=VDO_FRM_NO)이 이 프레임에서는 우연히 같은 값이라 분리 실증은 1차 결과에 의존(코드 미변경 확인) |
| TC-EXPORT-035 | PASS(정적, 코드 미변경) | [정적] | `DatasetExportService.java`의 파생 ORIGINAL 스킵 로직은 08-01 이후 미수정(git log 확인) — 1차 rawSn=18 실측 결과를 그대로 승계 |
| TC-EXPORT-036 | PASS | [실동작]+[정적] | rawSn=101: orgnl `dataset.src_path`=`/app/storage/raw/seed/clip-9101.mp4`, deid `dataset.src_path`=`/app/storage/raw/seed/101/deid/clip-9101-mask.mp4` — kind별 실제 경로 상이 확인. `NiaJsonBuilder.java:133-138` 일치 |
| TC-EXPORT-037 | PASS | [정적] | `NiaJsonBuilder.java:175`(1차 174 대비 1라인 이동) `src.getFrmExpln()`이 `NiaImage` 마지막 인자로 전달됨. 라이브 JSON `description:null`(미입력 상태와 일치) |

## E-8. 촬영환경 메타 — 20건(TC-META-009는 2026-07-30 폐기, 판정 대상 제외)

| ID | 판정 | 근거 확인 | 근거 요약 |
|---|---|---|---|
| TC-META-001 | PASS | [실동작] | rawSn=101 PUT 저장(weather=비/timeOfDay=NGT/season=WINTER) 후 GET → `weatherSource/timeOfDaySource/seasonSource` 전부 `"MANUAL"` 확인 |
| TC-META-002 | PASS | [실동작] | 수동값 저장 **전** GET(rawSn=101) → `weather:null, timeOfDay:"DAY", season:"SUMMER", timeOfDaySource:"DERIVED", seasonSource:"DERIVED"` — SHT_DT 기반 파생 프리필 확인 |
| TC-META-003 | PASS | [실동작] | PUT 3필드 전송 → 200 응답 정상 반영(dirty checking, 코드 08-01 이전부터 미변경) |
| TC-META-004 | PASS | [정적, 코드 08-01 이전 미변경] | `validate()`가 null/blank를 "수동값 없음"으로 정규화 → `changeShootingEnvironment`가 3필드만 UPDATE. 이번 회차는 3필드 모두 값 있는 PUT만 실동작 확인, 부분 null 케이스는 1차 실측 승계 |
| TC-META-005 | PASS(라인 드리프트 정정) | [정적] | `ShootingEnvironmentVocabulary.WEATHERS`(5종) 존재 라인이 23→**27**로 이동(설명 주석 추가). 카탈로그 근거라인 정정 완료. 값 자체(맑음/흐림/비/눈/안개)는 변경 없음 |
| TC-META-006 | PASS(라인 드리프트 정정) | [정적] | TIME_OF_DAYS/SEASONS 라인이 26-32→**29-36**으로 이동. 카탈로그 근거라인 정정 완료 |
| TC-META-007 | PASS | [정적] | 허용값 전부 20자 이내 + `@Size(max=MAX_LENGTH=20)`(`EnvironmentMetaUpdateRequest.java:28,32,36`) 이중 방어 확인(라인 변경 없음) |
| TC-META-008 | PASS | [실동작] | rawSn=101(APPROVED) PUT 후 백엔드 로그 `[EnvironmentMeta] re-freeze triggered rawSn=101` 확인(01:44:29). `RVW_CMPL_DT` 승계 확인 — 재동결 후에도 `ls_dataset_video_meta.rvw_cmpl_dt`가 원래 승인시각(2026-08-04 00:09:47.868)으로 유지, 편집 시각(01:44)으로 덮이지 않음을 DB 재조회로 직접 확인 |
| TC-META-009 | N/A | — | 2026-07-30 폐기 케이스(대체: TC-META-017). 판정 대상 제외 |
| TC-META-010 | PASS | [정적, 코드 미변경] | `isReviewApproved` 가드가 PENDING 영상엔 재동결 미수행. 이번 회차 미검수 영상으로 재현은 안 했으나(rawSn=101이 이미 APPROVED) 코드 경로 자체는 1차 실측·현재 코드 동일 |
| TC-META-011 | PASS | [정적] | `reFreezeApprovedSnapshot`(157-167, 1차 156-167 대비 거의 동일) `if(active.isEmpty()){warn; return;}` fail-safe 확인 |
| TC-META-012 | PASS | [정적] | `update()`(101-129) 내 `videoRepository.flush()`(117) → `videoMetaRepository.acquireRawLock(rawSn)`(118) → `isReviewApproved(rawSn)`(120) 순서 코드 그대로. 동시 요청 재현은 타이밍 조작 필요해 미수행(1차와 동일 한계) |
| TC-META-013 | PASS(코드 미변경, 실측은 1차 승계) | [정적] | `accessGuard.verifyRawAccess`가 `get()`(67)/`update()`(102) 진입부에 그대로 존재 — IDOR 방어 로직 미변경 확인 |
| TC-META-014 | PASS(코드 미변경) | [정적] | `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` + SecurityConfig 채널 격리 — 미변경 |
| TC-META-015 | PASS | [정적] | `findRaw`(187-190) `orElseThrow(NOT_FOUND)` — 미변경 |
| TC-META-016 | PASS | [실동작 정황] | `validate()`(174-185) `log.warn("...field={}", field)` — 필드명만 로깅, 입력원문 미포함 코드 확인(1차 실동작 로그와 동일 패턴, 이번 회차 재실행 없음) |
| TC-META-017 | PASS | [실동작] | PUT(weather=비/NGT/WINTER, 01:44:29) → 디바운스 flush 로그 `[ControlNotifyDebounce] flush rawSn=101 regen=true frames=468=[META_UPDATED]`(01:43:36 — **주의**: 이 flush는 직전 프레임 privacy-meta 변경분, 촬영환경 변경은 다음 flush 주기에 포함) → 실제로는 두 번째 flush에서 export v5 SUCCEEDED(48초 후 확인) → v5 JSON `video.weather="비", time_of_day="NGT", season="WINTER"` 정확 반영 — end-to-end 완전 실증 |
| TC-META-018 | PASS | [정적]+[실동작 정황] | `DatasetVideoMetaSnapshotService.java:126-128` `nullIfBlank(dayNgtCd/sesnCd/wthrNm)` — SHT_DT 파생 없이 수동값만 동결. rawSn=101 최초 승인(00:09:47) 시점 스냅샷에 weather/day_ngt/sesn 전부 null이었음을(PUT 이전 v1 export가 전부 null이었던 것으로) 간접 확인 |
| TC-META-019 | PASS | [실동작] | `GET /v1/dev/dataset-video-meta/shooting-env-correction-targets`(REVIEWER 토큰) → 200 `{"targetCount":0}`(레거시 파생 동결행 없음, 1차와 동일) |
| TC-META-020 | PASS(부분 실동작) | [실동작] | WORKER 토큰으로 GET 호출 시 **403** 확인(REVIEWER 전용 방어 실증, 이번 회차 신규 확인). POST 실행 자체는 대상 0건 환경이라 실행 결과는 1차 실측(`{"corrected":0,"remaining":0,"completed":true}`)에 의존 |

## 카탈로그 정정 내역 (이번 회차, 담당 라인범위 내 직접 Edit)

1. **TC-EXPORT-022**: 기대결과 전면 정정(anonymity 수동 override 금지 → `ExportPrivacyPolicy` 단일판정, ORIGINAL=null/DEID=수동값 우선). 근거라인 `NiaJsonBuilder.java:150-153` → `ExportPrivacyPolicy.java:64-80; NiaJsonBuilder.java:158`
2. **TC-EXPORT-023**: 기대결과 정정("파생 폴백" 문구 폐기 → 기본상수 폴백). 근거라인 `156-159,204-206` → `ExportPrivacyPolicy.java:64-80; NiaJsonBuilder.java:159-160`
3. **TC-EXPORT-030**: 기대결과 정정("N/Y 고정 오버라이드" → ORIGINAL=null/DEID=영상단위 수동값 우선). 근거라인 `VideoMetaMapper.java:65` → `ExportPrivacyPolicy.java:64-80; VideoMetaMapper.java:74-79`
4. **TC-EXPORT-027**: 근거라인 드리프트 정정 `VideoMetaMapper.java:101` → `:114`
5. **TC-EXPORT-029**: 근거라인 드리프트 정정 `VideoMetaMapper.java:144-152` → `:157-165`
6. **TC-META-005**: 근거라인 드리프트 정정 `ShootingEnvironmentVocabulary.java:23` → `:27`
7. **TC-META-006**: 근거라인 드리프트 정정 `ShootingEnvironmentVocabulary.java:26-32` → `:29-36`

## 신규 이슈

### [E-ISSUE-101] TC-EXPORT-022/023/030 — 카탈로그가 2026-08-03 폐기된 개인정보 3필드 정책을 검증 대상으로 담고 있었다 (정정 완료)
- **심각도**: HIGH (카탈로그 정합 — 다음 회차 거짓 FAIL 위험이었음, 이번 회차에 정정 완료)
- **기대 동작(기대효과)**: 카탈로그는 현재 확정 정책만 검증 대상으로 담아야 한다. 폐기된 정책을 남겨두면 다음 검증자가 "수동값이 override됐다 → 결함"으로 오판할 위험이 있다.
- **현재 동작(이슈 내용)**: 카탈로그(2026-07-30 최신화 기준)는 "ORIGINAL=N/DEIDENTIFIED=Y 고정, 수동 override 금지"를 기대결과로 담고 있었으나, 커밋 `0d290c4e`(2026-08-03, "export 개인정보 3필드 정책 반전")가 이를 정반대로 바꿨다. 신설 `ExportPrivacyPolicy` 클래스(`backend/src/main/java/kr/co/cudo/authoring/dataset/export/ExportPrivacyPolicy.java`)의 javadoc에 "★ 확정 정책(2026-08-03 사용자 확정 — 구 정책 전면 반전)" 표와 "폐기된 구 정책과 그 경위(되돌리지 말 것)" 절이 명시돼 있다.
  ```java
  // ExportPrivacyPolicy.resolve()
  private static String resolve(ExportKind kind, String manualYn, String deidDefault) {
      if (kind != ExportKind.DEIDENTIFIED) { return null; }             // ORIGINAL=항상 null
      return (manualYn == null || manualYn.isBlank()) ? deidDefault : manualYn.trim(); // DEID=수동값 우선
  }
  ```
- **재현/확인 경로**:
  ```bash
  curl -X PUT localhost:18081/api/v1/frames/{srcSn}/privacy-meta -H "Authorization: Bearer $WORKER" \
    -d '{"srcSn":{srcSn},"anonymity":"N","pseudonymity":"Y","privacyIncluded":"Y"}'
  # 재export 후 v{n}/orgnl/*.json → image.anonymity=null (판정 안 함)
  # 재export 후 v{n}/deid/*.json  → image.anonymity="N" (수동값 그대로, override 됨)
  ```
- **영향**: 기능 영향 없음(현행 동작이 최신 정본과 일치). 검증 프로세스 영향 — 카탈로그를 정정하지 않았다면 다음 회차에서 거짓 FAIL 3건 발생 위험.
- **수정 방향(제안)**: 완료됨 — TC-EXPORT-022/023/030을 이번 회차에 직접 정정(위 "카탈로그 정정 내역" 참조). `UNCERTAINTIES.md` ★ 확정 정책 절에 6번째 항목으로 "개인정보 3필드(anonymity/pseudonymity/privacy_included) = `ExportPrivacyPolicy` 단일판정, ORIGINAL 판정 안 함/DEID 수동값 우선" 등재를 권장(향후 "일관성 없다"는 재검토·되돌리기 방지 — `ExportPrivacyPolicy` 클래스 주석이 이미 이 경고를 담고 있으나 UNCERTAINTIES에도 반영하면 검증자가 더 빨리 확인 가능).

### [E-ISSUE-102] EnvironmentMetaController Swagger 설명이 실제 재export 동작과 모순된다
- **심각도**: LOW (문서 전용 결함 — 실동작에는 영향 없음, API 소비자 오인 위험)
- **기대 동작(기대효과)**: OpenAPI/Swagger 설명은 실제 서버 동작과 일치해야 한다. TC-META-008/017이 검증하는 "APPROVED 후 촬영환경 수정 = export 새 버전 전량 재생성"은 실제로 그렇게 동작한다(이번 회차 실동작으로 재확인, v5 export에 새 촬영환경 값 정확 반영).
- **현재 동작(이슈 내용)**: `EnvironmentMetaController.java:66-68`의 PUT API `@Operation` description이 다음과 같이 **정반대 사실**을 적고 있다:
  > *"검수 완료 후 수정 시 동결 스냅샷만 재동결(데이터마트 뷰에 최신값 반영)되고 관제 TASK_MODIFIED(META_UPDATED) 통지가 발행된다. **편집은 export 파일 재생성을 트리거하지 않으며**(라벨 수정과 동일 정책), export 폴더는 다음 검수 승인 시점에 전량 재산출된다."*

  하지만 실제 서비스 코드(`EnvironmentMetaService.java:120-127`)는 `TaskModifiedEvent(rawSn, null, ChangeType.META_UPDATED, actorNo, **true**)`로 `exportRegenerated=true`를 실어 발행하며, 이는 CLAUDE.md의 "★ export 재생성·동기화 정책(2026-07-27 확정)"과 정확히 일치하는 현재 정책이다. 즉 컨트롤러의 Swagger 설명이 2026-07-27 이전 폐기된 구 정책("재생성 미트리거")을 그대로 남겨둔 상태다.
- **재현/확인 경로**:
  ```bash
  # Swagger UI에서 PUT /v1/videos/{rawSn}/environment-meta 설명 확인 — "export 파일 재생성을 트리거하지 않으며" 문구
  # 실제로는 재생성됨:
  curl -X PUT localhost:18081/api/v1/videos/101/environment-meta -H "Authorization: Bearer $REV" \
    -d '{"weather":"비","timeOfDay":"NGT","season":"WINTER"}'
  # 이후 docker logs klid-backend | grep DatasetExport → "async re-export(+notify) starting rawSn=101 forceRegenerate=true" 확인됨
  ```
- **영향**: 기능 영향 없음. API 문서를 신뢰하는 외부/내부 개발자(FE, 관제 연동 담당)가 "촬영환경만 고치면 export 파일은 안 바뀐다"고 오인해 별도 재산출을 기다리거나 잘못된 가정으로 연동 코드를 짤 위험(정보 정확성 문제).
- **수정 방향(제안)**: `EnvironmentMetaController.java`의 `update()` `@Operation` description에서 "편집은 export 파일 재생성을 트리거하지 않으며... 다음 검수 승인 시점에 전량 재산출된다" 문장을 삭제하고, "검수 완료 후 수정 시 export 폴더도 새 버전(v{n+1})으로 전량 재생성되며, 재생성 성공 후 통지가 발송된다"로 교체.

## 요약

- **합계 38건**(TC-META-009 폐기 제외 실질 37건 판정 대상): **PASS 37 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 1(폐기) / 확인필요 0**
  - 단, PASS 37건 중 **3건(TC-EXPORT-022/023/030)은 카탈로그의 구 기대값 기준으로는 FAIL이었을 것을 이번 회차에 기대값 자체를 신규 정책에 맞춰 정정한 뒤의 판정**이다(위 이슈·정정 내역 참조) — 단순 "PASS 37"로만 읽으면 이 반전을 놓친다.
- **핵심 실증**:
  - **정책 반전 라이브 확인**(TC-EXPORT-022/023/030): srcSn=468에 실제 수동값(N/Y/Y)을 저장하고 재export까지 실행해 deid에는 반영·orgnl은 null임을 직접 확인 — 코드 읽기가 아니라 API 호출+파일 실측으로 반증.
  - **재export end-to-end**(TC-META-008/017): 촬영환경 PUT → 디바운스 flush → export v5 SUCCEEDED → JSON 반영까지 전 구간 실시간 확인, RVW_CMPL_DT 승계도 DB로 직접 대조.
  - **REVIEWER 전용 방어**(TC-META-020): WORKER 토큰으로 실제 403 응답 확인(1차는 코드 근거만 있었음).
- **이전 회차(1차, 2026-08-01) 이슈 해소 여부**: 1차 E-part6(당시 §E-7/E-8/E-9 통합 53건)에서는 이 범위(TC-EXPORT-020~037, TC-META-001~020)에 대해 이슈가 0건 발생했었다(전건 PASS). 이번 3차에서도 신규 FAIL/PARTIAL은 없으나, **1차 이후 코드가 반전되면서 카탈로그 자체가 낡은 상태였다** — 이는 "이전 이슈의 미해소"가 아니라 "1차 검증 이후 발생한 신규 코드 변경에 카탈로그가 못 따라간 것"이며, 이번 회차에 정정 완료했다.
- **BLOCKED/확인필요**: 없음.
