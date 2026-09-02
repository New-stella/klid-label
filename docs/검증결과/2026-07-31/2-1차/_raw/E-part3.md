# E-augment-resolution-export-meta 검증 (Part 3) — E-6 / E-7

- 대상: `docs/test-cases/E-augment-resolution-export-meta.md` 230~279행 (TC-EXPORT-001~019, 040~043 / TC-EXPORT-020~037) — 41건
- 검증일: 2026-07-31 / 2-1차
- 스택: klid-backend(:18081, 컨테이너 `klid-backend`) · klid-postgres(`klid_system`/`public`) · klid-mock-server(:9400)
- 선행 실동작 근거: `_raw/pipeline-drive.md` (rawSn=4 적재→비식별→마킹→VLM→프레임추출→배정→라벨링→검수승인→관제통지→View노출 전 구간 도달)
- 본 파트는 대상 9개 소스 전문 정독(`DatasetExportService`·`DatasetExportBridge`·`AsyncDatasetExportRunner`·`DatasetExportTxService`·`DatasetExportPathResolver`·`DatasetExportFailureRecoverer`·`DatasetExportWriter`·`ExportKind`·`ExportFileNaming`·`NiaJsonBuilder`·`NiaAnnotationDoc`·`NiaVideo`·`NiaImage`·`VideoMetaMapper`) + **실제 export 산출물 실동작 확인**(DB `ls_dataset_export` 조회 + `klid-backend` 컨테이너 내 파일시스템 실측 + 산출 JSON 원문 대조) + 기존 테스트 자산 대조(`DatasetExportServiceTest` 30+케이스·`DatasetExportServiceIT` 7케이스·`DatasetExportTxServiceTest` 7케이스·`AsyncDatasetExportRunnerTest` 8케이스·`DatasetExportBridgeTest`/`DatasetExportBridgeBeanConditionTest`·`DatasetExportFailureRecoveryIT` 4케이스·`DatasetExportDeidentReportGateIT` 10케이스·`NiaJsonBuilderTest` 30+케이스·`VideoMetaMapperTest` 16케이스)로 수행.

## ★★ 실동작 근거 — rawSn=4/5/6 실제 export 다회 구동 확인

- **DB 실측**(`ls_dataset_export`): rawSn=4 가 **v1~v6 전부 SUCCEEDED**(재승인·재동결이 이 검증 회차 중 반복 트리거됨 — R6 강제재생성 실동작 반증), rawSn=5 가 v1~v2 SUCCEEDED, rawSn=6 가 v1 SUCCEEDED. `export_stts_cd` 분포는 **SUCCEEDED 10/FAILED 0** — 이번 구동에서 실패 회수 경로(TC-EXPORT-040)는 자연 발생하지 않아 **코드 정독 + 기존 IT**로 판정(정상 시나리오라 실패가 안 남— BLOCKED 아님, 재현에 벤더/2노드 불필요).
- **파일시스템 실측**(`docker exec klid-backend`): `EXPORT_PATH_NM=/app/storage/raw/seed/4` 하위에 `v1..v6/{orgnl,deid}/{0000..0029}.{jpg,json}` 전부 실재 — **co-locate 구조**(TC-EXPORT-004/014) 확인, `v1..v5` 구버전이 `v6` 생성 후에도 **삭제되지 않고 보존**(TC-EXPORT-043 "retention 미구현" 확정 정책과 정확히 일치).
- **JSON 원문 실측**(`v6/orgnl/0000.json` vs `v6/deid/0000.json`, 전문은 위 조사 결과 참조):
  - 최상위 키 순서 `info,dataset,licences,video,event,image,annotations,categories,type` — `"event": null` **키 present·값 null** (TC-EXPORT-020/021 실동작 일치)
  - `image.anonymity`: orgnl=`"N"`, deid=`"Y"` / `video.anonymity` 도 동일 분기 (TC-EXPORT-022/030 실동작 일치)
  - `dataset.name`: orgnl=`"clip-9101"`(원본 basename), deid=`"clip-9101-mask"`(비식별 basename) / `dataset.src_path` deid=비식별 경로(`/app/storage/raw/seed/4/deid/clip-9101-mask.mp4`), orgnl=원본 상대경로(`./storage/raw/seed/clip-9101.mp4`) — 원본 경로가 비식별 산출물에 새지 않음 (TC-EXPORT-024/036 실동작 일치)
  - `image.file_name="0000.jpg"`(ExportFileNaming, FRM_NO=0) vs `image.frame_num`: `0001.json` 실측 시 `frame_num=10`(VDO_FRM_NO) — **파일명(FRM_NO)과 frame_num(VDO_FRM_NO)이 서로 다른 값으로 정확히 분리** (TC-EXPORT-033/034 실동작 일치, DB `ls_data_src.vdo_frm_no=10 vs frm_no=1` 도 대조 확인)
  - `video.weather/time_of_day/season` 전부 `null`(수동 미입력) — self-fill 없음 확인 (TC-EXPORT-028 실동작 일치)
  - `video.vd_description` 키 present·값 null (TC-EXPORT-027 실동작 일치)

---

## E-6. 데이터셋 Export (DatasetExportService / Bridge / Runner / TxService / Recoverer)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-EXPORT-001 | 검수승인 AFTER_COMMIT 강제 재생성 | PASS | 정적 `DatasetExportBridge.java:36-43` ✓ `@TransactionalEventListener(AFTER_COMMIT)` + `runApprovalAsync` | `DatasetExportBridgeTest` | |
| TC-EXPORT-002 | 승인 경로 멱등 skip 미적용 | PASS | 정적 `DatasetExportService.java:150-156` ✓ `forceRegenerate` 이면 skip 판정 자체를 우회 / **실동작**: rawSn=4 가 v1~v6 전부 SUCCEEDED — 재승인마다 새 버전 채번 확인 | `DatasetExportServiceTest:294` 무수정_재승인도_승인경로는_새버전_생성한다(R6) | |
| TC-EXPORT-003 | 재동결 경로 멱등 skip | PASS | 정적 `:150-156`; `DatasetExportBridge.java:55-61` `onReExport`→`force=false` ✓ | `DatasetExportServiceTest:280,551` 재동결_동일해시_멱등skip / `DatasetExportTxServiceTest:128` | |
| TC-EXPORT-004 | 정상 산출 SUCCEEDED + EXPORT_PATH_NM=영상 루트 | **PASS(실동작)** | 정적 `DatasetExportService.java:162-233` ✓ / **실동작**: DB `ls_dataset_export.export_path_nm='/app/storage/raw/seed/4'`(rawSn=4, dirname(raw)+`/4`) + 파일시스템 `v6/orgnl`,`v6/deid` 실재, `export_stts_cd=SUCCEEDED, frame_cnt=60` | `DatasetExportServiceIT:253` 승인_산출시_v1이_SUCCEEDED로_기록 | |
| TC-EXPORT-005 | 일부 프레임 부재 PARTIAL | PASS | 정적 `:211-222` ✓ `totalSkipped>0`→PARTIAL / 실제 구동에서는 전 프레임 원천 이미지 보유(orig/deid 30/30, 3/3, 1/1)라 PARTIAL 미발생(정상 시나리오 — BLOCKED 아님) | `DatasetExportServiceTest:174` 일부프레임_skip시_PARTIAL로_전이한다 | |
| TC-EXPORT-006 | 산출 0건 FAILED | PASS | 정적 `:205-210` ✓ `markFailed`, 승인 롤백 없음 | `DatasetExportServiceTest:246` 아무것도_산출못하면_FAILED로_전이한다 | |
| TC-EXPORT-007 | 파생영상 ORIGINAL 벌 미생성(PARTIAL 아님) | PASS | 정적 `:183-199,330-336` `hasNoOriginalFrames` ✓ | `DatasetExportServiceTest:193,210,228` 3종(파생 SRC null·PARTIAL강등없음·일반영상 회귀방지) + `DatasetExportServiceIT:234` 파생영상_export_정상생성 | 이번 파이프라인 구동은 파생영상(rawSn 미해당)이라 실동작 미노출 — 단위+IT로 충분 |
| TC-EXPORT-008 | 버전 채번 UK 충돌 재시도 | PASS | 정적 `:367-377` `insertWithRetry` ✓ | `DatasetExportServiceTest:345` 동시_승인_UK위반시_재시도로_다음버전_채번 | |
| TC-EXPORT-009 | 버전 채번 재시도 소진 | PASS | 정적 `:174-178` ✓ null→VERSION_EXHAUSTED | `DatasetExportServiceTest:364,533` | |
| TC-EXPORT-010 | 파일쓰기 실패 시 승인 불변 | PASS | 정적 `:234-241` ✓ 예외 삼킴 + FAILED만 기록, 클래스명만 로깅 | `DatasetExportServiceTest:264` 파일산출_실패해도_승인은_롤백되지_않는다 | |
| TC-EXPORT-011 | 입력 부재 NO_INPUT | PASS | 정적 `:143-147`; `DatasetExportTxService.java:101-113` ✓ | `DatasetExportServiceTest:380,588` | |
| TC-EXPORT-012 | 메트릭 outcome 정확히 1회 | PASS | 정적 `:107-109,255-263` ✓ finally 단일 지점, try/finally 로 조기 return·예외 이탈 모두 커버 | `DatasetExportServiceTest:396,419,441,462,508,533,551,567,588` 등 outcome별 다수 | |
| TC-EXPORT-013 | 브릿지 조건부 비활성화 | PASS | 정적 `DatasetExportBridge.java:29-30` `@ConditionalOnProperty` ✓ | `DatasetExportBridgeBeanConditionTest:30,37,44` enabled true/false/matchIfMissing 3종 | |
| TC-EXPORT-014 | 폴더 구조 계약(co-locate) | **PASS(실동작)** | 정적 `DatasetExportPathResolver.java` ✓ `{dirname(raw)}/{rawSn}/v{n}/{orgnl\|deid}/` / **실동작**: `docker exec klid-backend find /app/storage/raw/seed/4` → `v1..v6/{orgnl,deid}/{0000..0029}.{jpg,json}` 정확히 실재 | `DatasetExportPathResolverTest` | ⚠ `ExportKind.java:6` 클래스 javadoc 이 구 문구("labeling_root 하위")를 그대로 남겨 실제 co-locate 구조와 문서가 어긋남(문서 드리프트, 동작에는 영향 없음) |
| TC-EXPORT-015 | 산출 base 3중 가드 + fail-secure | PASS | 정적 `:158-169,345-360` `markBaseRejected` ✓ allowlist→normalize→toRealPath | `DatasetExportServiceTest`(base rejection 경로), `VideoArtifactRootResolverTest` | |
| TC-EXPORT-016 | 비식별 신고 게이트=산출 자체 skip | PASS | 정적 `:136-141` ✓ 진입부 단일 게이트, 행 미생성 | `DatasetExportServiceTest:604,627,642` + `DatasetExportDeidentReportGateIT:182,199,217` | |
| TC-EXPORT-017 | 쓰기 중 신고 접수=마감 차단+v{n} 삭제 | PASS | 정적 `:180-254,266-304`; `DatasetExportTxService.java:224-240` `finalizeUnlessUnderDeidentReport` ✓ | `DatasetExportServiceTest:482` + `DatasetExportDeidentReportGateIT:237,258` | |
| TC-EXPORT-018 | export 성공 후에만 통지 | PASS | 정적 `AsyncDatasetExportRunner.java:67-76` `runApprovalAsync` — doExport true 일 때만 이벤트 발행 ✓ | `AsyncDatasetExportRunnerTest:76,87` | |
| TC-EXPORT-019 | 승인 후 수정=재export 후 통지 | PASS | 정적 `:90-106` `runReExportThenNotify` ✓ 성공 시에만 콜백 실행 | `AsyncDatasetExportRunnerTest:101,113` | |
| TC-EXPORT-040 | 실패 export 회수 후 통지 재개 | PASS | 정적 `DatasetExportFailureRecoverer.java:114-170`; `DatasetExportTxService.java:257-261` `claimForRetry` ✓ | `DatasetExportFailureRecoveryIT:123,139,153,187` | 실제 구동은 FAILED 0건이라 실동작 미노출(정상 시나리오 — BLOCKED 아님) |
| TC-EXPORT-041 | 신고 해소 시 보류분 복구 | PASS | 정적 `DatasetExportBridge.java:81-87` `onDeidentReportResolved`→`runApprovalAsync`(force=true) ✓ | `DatasetExportDeidentReportGateIT:295,352` | |
| TC-EXPORT-042 | 재export 트리거는 control-notify 토글과 무관 | PASS | 정적 `DatasetExportBridge.java:29-30` — export 토글과 통지 토글이 별도 프로퍼티(`authoring.dataset-export.enabled` vs `authoring.control-notify.enabled`) ✓ | 코드 확인(두 `@ConditionalOnProperty` 독립 선언) | |
| TC-EXPORT-043 | retention 정리 로직 없음(확정 정책) | **PASS(실동작)** | 정적 `DatasetExportService.java:45-46,171-172` TODO 주석 ✓ | — | **실동작**: rawSn=4 `v1..v6` 전 버전이 파일시스템에 그대로 보존 확인(삭제 잡 없음) — 확정 정책과 정확히 일치 |

---

## E-7. Export JSON 포맷 (NiaJsonBuilder / NiaAnnotationDoc / NiaVideo / NiaImage / VideoMetaMapper)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-EXPORT-020 | ★최상위 키는 event(rename) | **PASS(실동작)** | 정적 `NiaAnnotationDoc.java:24-37` ✓ `@JsonPropertyOrder`에 `event`(구 `event_annotation` 폐기) / **실동작**: `v6/orgnl/0000.json` 원문에 `"event": null` 이 `video` 다음 위치에 존재 | `NiaJsonBuilderTest:203,321,355` | |
| TC-EXPORT-021 | event 값 null 처리 | **PASS(실동작)** | 정적 `:24,32` `@JsonInclude(ALWAYS)` ✓ / **실동작**: 위와 동일 JSON에서 키 present·값 null 확인 | `NiaJsonBuilderTest:357` | |
| TC-EXPORT-022 | anonymity=ExportKind 파생(수동 override 금지) | **PASS(실동작)** | 정적 `NiaJsonBuilder.java:150-153` ✓ | **실동작**: `0000.json` orgnl `image.anonymity="N"` vs deid `"Y"` 확인 | `NiaJsonBuilderTest:264,285` |
| TC-EXPORT-023 | pseudonymity/privacyIncluded 수동 우선 | PASS | 정적 `:156-159,203-206` `firstNonBlank` ✓ CHAR(1) blank 취급 포함 | `NiaJsonBuilderTest:233,253` | |
| TC-EXPORT-024 | deid 산출 경로 fail-secure | **PASS(실동작)** | 정적 `:132-137`; `VideoMetaMapper.java:61-64` ✓ deidVideoPath=null→dataset/video 모두 null / **실동작**: 본 구동은 deidVideoPath 보유(`/app/storage/raw/seed/4/deid/clip-9101-mask.mp4`)라 정상값 채워짐을 확인, null 분기는 단위테스트로 보강 확인 | `NiaJsonBuilderTest:459,486` + `VideoMetaMapperTest:173` DEIDENTIFIED인데_deid경로_null이면_filename도_null | |
| TC-EXPORT-025 | malformed 라벨 skip | PASS | 정적 `:178-201` ✓ 1건 skip, 문서 전체 유지 | `NiaJsonBuilderTest:409` malformed라벨은_문서조립시_skip | |
| TC-EXPORT-026 | 잉여키 제거/키 유지 정합 | PASS | 정적 `NiaVideo.java:12`,`NiaImage.java:9` `@JsonInclude(ALWAYS)` ✓ | `NiaJsonBuilderTest:156,177,192` | |
| TC-EXPORT-027 | vd_description 필드 존재 | **PASS(실동작)** | 정적 `NiaVideo.java:46`; `VideoMetaMapper.java:101` ✓ | **실동작**: `0000.json` `video.vd_description` 키 present, 값 null | `NiaJsonBuilderTest:157,177` | |
| TC-EXPORT-028 | weather/time_of_day/season 수동값만(self-fill 폐기) | **PASS(실동작)** | 정적 `VideoMetaMapper.java:46-59`; `DatasetVideoMetaSnapshotService.java:107` "SHT_DT 기반 추정(self-fill)을 하지 않는다(E-ISSUE-42)" ✓ / **실동작**: `0000.json` `video.weather/time_of_day/season` 전부 null(수동 미입력 시나리오) | `VideoMetaMapperTest:213,232,271` | |
| TC-EXPORT-029 | 촬영환경 blank→null 정규화 | PASS | 정적 `:144-152` `firstNonBlank` ✓ | `VideoMetaMapperTest` 관련 케이스 | |
| TC-EXPORT-030 | anonymity kind override(video) | **PASS(실동작)** | 정적 `:65` ✓ | **실동작**: 위 TC-022 증거와 동일 파일에서 `video.anonymity` 도 N/Y 확인 | `VideoMetaMapperTest:187` |
| TC-EXPORT-031 | meta null 방어 | PASS | 정적 `NiaJsonBuilder.java:88-91` ✓ 400 | `NiaJsonBuilderTest`(prepareContext null 방어) | |
| TC-EXPORT-032 | FORMAT_VERSION/info 계약 | **PASS(실동작)** | 정적 `:36-40,96` ✓ | **실동작**: `0000.json` `info.version="1.3"`, `type="instances"` 확인 | `NiaJsonBuilderTest:302` |
| TC-EXPORT-033 | image.file_name은 ExportFileNaming 단일 지점 | **PASS(실동작)** | 정적 `:142-145` ✓ | **실동작**: `0000.json` `file_name="0000.jpg"` (FRM_NO=0, zero-pad 4자리) | `NiaJsonBuilderTest` + `ExportFileNaming` 자체 계약 |
| TC-EXPORT-034 | frame_num=VDO_FRM_NO, 미측정이면 null | **PASS(실동작)** | 정적 `:146-149,169-170` ✓ FRM_NO 폴백 금지 | **실동작**: DB `ls_data_src` rawSn=4 `frm_no=1,vdo_frm_no=10` ↔ `v6/orgnl/0001.json` 실측 `file_name="0001.jpg"`(FRM_NO), `frame_num=10`(VDO_FRM_NO) — 두 값이 실제로 다르게 분리 확인 | `NiaJsonBuilderTest` |
| TC-EXPORT-035 | 파생영상 video.filename은 비식별 사본 | PASS | 정적 `DatasetVideoMetaSnapshotService.java:126-133`; `DatasetExportService.java:183-199` ✓ | `DatasetExportServiceIT:234` 파생영상_export_정상생성 | 이번 파이프라인은 파생영상 미해당 — IT로 확인 |
| TC-EXPORT-036 | dataset 블록 kind 분기 | **PASS(실동작)** | 정적 `NiaJsonBuilder.java:132-137` ✓ | **실동작**: `0000.json` orgnl `dataset.src_path="./storage/raw/seed/clip-9101.mp4"` vs deid `dataset.src_path="/app/storage/raw/seed/4/deid/clip-9101-mask.mp4"` — 원본 경로가 비식별 산출물에 노출되지 않음 확인 | `NiaJsonBuilderTest:459,475` |
| TC-EXPORT-037 | frm_expln pass-through | PASS | 정적 `:174` `src.getFrmExpln()` ✓ | `NiaJsonBuilderTest:368` 프레임설명_FRM_EXPLN이_image_description에_반영 | 실측 rawSn=4 프레임 `frm_expln` 미입력(빈값)이라 실동작에서는 null만 관측 — 단위테스트로 값 보유 케이스 확인 |

---

## 집계

| 판정 | 건수 |
|------|:--:|
| PASS | 41 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| 확인필요 | 0 |

**결함 0건.** E-6/E-7 41건 전건 PASS — 그중 15건은 실제 export 산출물(DB 행 + 파일시스템 + JSON 원문)로 직접 실동작 확인, 나머지는 코드 정독 + 기존 전용 테스트(약 100+ 케이스)로 대조. 이번 파이프라인 구동에서 자연 발생하지 않은 조건(PARTIAL 프레임 부재·FAILED 회수·파생영상·deid경로 null)은 전용 단위/통합 테스트가 해당 분기를 명시적으로 커버해 판정 근거로 충분함(재현에 실벤더·2노드가 필요하지 않아 BLOCKED 대상 아님).

## 참고 — 비고 사항(결함 아님)

- `ExportKind.java:6` 의 클래스 javadoc 이 구 고정 경로("labeling_root 하위") 문구를 그대로 남겨, 실제 co-locate 구조(`DatasetExportPathResolver` 정본 주석 + 실측 파일 구조)와 문서가 어긋난다. **동작에는 전혀 영향 없는 주석 드리프트**이므로 이슈로 등록하지 않음 — 후속 문서 정리 시 참고.
