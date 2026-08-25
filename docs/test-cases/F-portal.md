# F. 포털(외부 채널) — 테스트 케이스

> 241 케이스(표 행 실측 — **폐기 행 포함**, 행을 지우지 않으므로) · 계층: unit / integration / security · [← README](README.md) ※ 카운트 = `grep -cE '^\| ~*TC-'`(ID 취소선 폐기 행 포함, 2026-08-05 머지 회차 7 정정 · 회차 5 에서 177→180, 포털 TUS 존재 오라클 차단 `TC-TUS-033~035` 신설 · **회차 6 에서 180→201, F-12 데이터마트 ZIP 다운로드·보존기간 만료 자동 삭제 신설** · **회차 7 에서 201→210, F-12d 화면 배선(다운로드 버튼·만료 예정일 표기) 신설** · **회차 8 에서 210→215, F-12e 대용량 전송 제한시간·전송 중단 안내·진행 표시 신설** · **회차 9 에서 215→228, F-12f 대용량 다운로드 취소(두 경로) 신설**(구 머리말이 이 회차 반영을 빠뜨리고 있었다 — 이번에 정정) · **회차 10 에서 228→237, F-12g~i 방치 업로드 자산의 실패 전이·정리/보존기간 스윕 자기 토글 분리·비동기 스트리밍 응답 서버측 절대 제한시간 신설** · **회차 11 에서 237→241, F-12g 의 하트비트 판정 서술이 신설 직후 거짓으로 확인돼 정정 + 유계 부등식·프레임 대기 상한·바인딩 실패 검증 4건 신설**)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|------|:--:|:--:|:--:|------|
| 1 | 2026-07-30 | 77건 | 10건 | 0건 | 포털 SAM2 원본→비식별 전송 전환(FrameImageEncoder 쌍둥이 삭제) + 게이트 412 반영, 포털 프레임 이미지 no-store 통일, PortalUploadProperties record 전환에 따른 라인 재정렬(PortalUploadService +6, PortalVideoUploadTxService -1), SecurityConfig 라인 전면 재확인(Phase 1·7 인가 표면 수정으로 매처 순서 이동), multipart 21MB/1100MB 는 prd 전용(공통은 500MB/1200MB)으로 정정, frame-interval-sec 기본값 5초 확정(UNCERTAINTIES #5 해소), TUS 스윕 2노드 조건부 UPDATE/DELETE 신규 케이스 |
| 2 | 2026-08-03 | 73건 | 0건 | 0건 | **근거 `file:line` 전수 재확인 회차** — F-2/F-3(`PortalLabelService.java`)·F-5/F-6(`PortalUploadService.java`)·F-7(`PortalUploadLabelService.java`) 라인 대량 드리프트 정정(F-7 은 구 노트 "07-25 이후 무변경"이 오기였음 — 실제로는 476bc91a·dcdbb827 2건 반영되어 있었음, 08-03 정정). F-8/F-9(TUS·프레임추출)·F-10/F-11(스윕·파이프라인 분리)은 전건 정확 확인(수정 없음). F-4 헤더의 포털 SAM2 제거일을 08-02→**08-03**(dcdbb827) 로 정정. TC-PORTAL-032/033 은 구현이 `resolveSafe`(lexical)→`StorageSubtreePolicy.verifyDeidentifiedFile`(실경로) 로 교체된 사실을 기대결과 문구에 반영. 폐기·UNRESOLVED 신규 없음(기존 F-3 042~045 폐기 표기는 재확인 후 유지) |
| 3 | 2026-08-04 | 8건 | 0건 | 0건 | **3차 전수 검증 회차(4파트 병렬 + 병합)** — 2차 HIGH 3건(`/v1/portal/datamart/labels` 게이트 전무·라벨 body-size 필터 URL인코딩 우회·포털 SAM2 노출) **전건 해소 실동작 확증**. F-4 머리말 폐기범위 오기 정정(`075~078`→`075~077`, TC-PORTAL-078은 활성 케이스) + F-3/F-4 절 간 `TC-PORTAL-060~062` ID 충돌 경고 블록 신설 + TC-PORTAL-075~077 폐기 삭제일 정정(08-02→08-03) + TC-PORTAL-038/052·TC-PORTALUP-021/022 근거 `file:line` 드리프트 정정 + F-3 채번 주의 노트 갱신(058~062 반영, 다음 신규 063부터). 신규 FAIL 0건(3건은 전부 F-5 2차 이슈 미해소 이월), 신규 PARTIAL 0건(5건 전부 이월). 상세는 `docs/검증결과/2026-08-03/3차/F-result.md`·`ISSUES.md` 참조 |
| 4 | 2026-08-05 | 3건 | 0건 | 0건 | **ID 중복 3건 해소 — 재번호(케이스 내용·행 수 불변)**. F-3 절(데이터마트 사용자 라벨 저장)의 신규 3건을 `TC-PORTAL-060`·`061`·`062` → **`TC-PORTAL-095`·`096`·`097`** 로 옮겼다. 같은 3개 ID 를 F-4 절(포털 SAM2 미제공 확정)이 함께 쓰고 있었고, **외부 참조가 걸린 쪽은 F-4** 다(`docs/검증결과/2026-08-02/2차/F-result.md:622-624` · 같은 회차 `ISSUES.md` 의 **F-ISSUE-41** 이 `TC-PORTAL-060/061` 을 포털 SAM2 케이스로 인용) — 그래서 참조 영향이 없는 F-3 쪽을 재번호했다. 새 번호는 이 문서의 `TC-PORTAL` **마지막 번호(094) 다음부터** 채번(저장소 규칙). 회차 2 머리말의 "별도 판단이 필요해 이번 정정 범위에서 제외" 유보는 이로써 해소. 행 수 불변이라 **총계(177) 영향 없음** ⚠ **머지 합류(2026-08-05)로 회차 번호 3 → 4 재부여** — main 의 회차 3(3차 전수 검증)과 겹쳤다. |
| 5 | 2026-08-07 | 3건(TC-TUS-012 · TC-PORTAL-072 · 072c) | 3건(TC-TUS-033~035) | 0건 | **★포털 TUS 세션의 존재 오라클 차단 — 소유자 불일치 403 → 404**(내부 업로드 [B-23](B-batch-deidentify.md) 과 같은 규약). **TC-TUS-012 의 구 기대값 403 은 폐기** — 403 은 "그 세션은 있는데 네 것이 아니다"가 되어 응답 코드가 **세션 존재 오라클**이 된다(CWE-209). 이제 `HEAD`·`PATCH`·`DELETE` 3경로 모두 미존재와 **상태코드도 메시지도 동일한 404** 이고(TC-TUS-033), 거부는 offset·완료 검사·파일 삭제보다 **먼저** 평가돼 부수효과가 0 이다(TC-TUS-034). ⚠ **인증 401 · 역할 403 · 만료 410 · offset 409 는 불변**(TC-TUS-035 가 회귀 가드) — 소유자 본인의 정상 재개 흐름은 달라지지 않았다. ⚠ 한 경로라도 403 을 남기면 그 경로로 같은 판별이 가능해 나머지 차단이 무의미해진다(오라클은 가장 느슨한 경로를 따라간다). 그 밖에 좌측 도구바 컴포넌트 개명(`DarkToolbar` → `ToolBar`)에 따라 TC-PORTAL-072·072c 의 근거 테스트 파일명을 정정했다 — **기대결과 불변** |
| 6 | 2026-08-17 | 0건 | 21건(TC-PORTAL-098~109, TC-PORTALUP-088~096) | 0건 | **★F-12 신설 — 구 V1.5 "포털 데이터마트 다운로드는 포털 자체 책임" 정책 폐기(사용자 확정).** 데이터마트 작업 데이터 ZIP 다운로드(`GET /v1/portal/datamart/videos/{rawSn}/download`, 200/403/410/412/429) + 보존기간 만료 자동 삭제(데이터마트 라벨·업로드 자산 READY/FAILED 독립 축) + 만료 예정 시각 조회 시점 파생 노출(`myLabelExpiresAt`/`expiresAt`)을 검증 대상에 추가했다. [UNCERTAINTIES.md #11](UNCERTAINTIES.md) "미해소 유지" 판정도 이 회차에서 해소로 갱신. 기존 F-7 절의 `TC-PORTALUP-055~058`(업로드 자산 원본 다운로드)은 **별개 축이라 무변경**. ⚠ **화면(SCREEN-028) 다운로드 버튼 배선은 이번 범위 밖** — 백엔드 API만 신설, TC-PORTAL-072~074(FE 포털 제약 회귀)는 무변경 |

| 7 | 2026-08-18 | 0건 | 9건(TC-PORTAL-110~117, TC-PORTALUP-097) | 0건 | **★F-12d 신설 — 화면 배선 완료.** 회차 6 이 남겨 둔 «화면 배선은 이번 범위 밖» 유보를 해소했다. 포털 홈(SCREEN-028) 영상 카드에 만료 예정일 병기 + 작업 데이터 다운로드 버튼, 포털 업로드 목록(SCREEN-033)에 만료 예정일 병기. ⚠ **구 V1.5 «포털 다운로드 미제공» 을 고정하던 FE 회귀 가드 2건을 반대 단언으로 뒤집었다** — `PortalHomePage.test.tsx(다운로드_UI_미제공_V1_5_포털_자체_책임)` → `영상_카드마다_작업_데이터_다운로드_버튼이_있다_V1_5_미제공_정책_폐기` · `PortalLayout.test.tsx(다운로드_메뉴_미존재_V1_5)` → `자식_화면의_다운로드_UI를_가리지_않되_GNB에_전역_다운로드_메뉴는_두지_않는다_V1_5_미제공_정책_폐기`(행을 지우지 않고 뒤집는다 — 지우면 «왜 이 케이스가 없어졌지» 로 되돌아온다). **mutation 실증 6건**: 반영 없음 → 아래 각 행 참조 |

| 8 | 2026-08-18 | 1건(TC-PORTAL-113 **반전**) | 5건(TC-PORTAL-113b, 118~121) | 0건 | **★F-12e 신설 — 대용량 전송이 30초에 끊기던 결함 + 만료 부재를 한 원인으로 단정하던 결함.** ①이 요청은 공용 API 클라이언트의 기본 제한시간(30초)을 그대로 쓰고 있었는데 **서버는 같은 응답을 «영상이 포함되면 GB 급» 이라고 스스로 적고 스트리밍으로 내려보낸다** — 브라우저 XHR 의 timeout 은 **응답 완료까지의 총 경과 시간**이라 30초로는 구조적으로 끊기고, 끊긴 시점엔 서버가 이미 전량을 흘려보낸 뒤라 사용자가 재시도할수록 서버가 같은 전송을 반복하고 버렸다. 전용 제한시간(30분)으로 덮고, 상태코드 없는 실패(중단·네트워크·제한시간 초과)를 서버가 준 4종과 **갈라** 안내한다(구 동작은 «잠시 후 다시 시도» — 기다려도 달라지지 않는 상황에 재시도를 권했다). ②`TC-PORTAL-113` **반전** — 만료 부재를 «저장 라벨 없음» 으로 단정해 버튼을 막던 동작을 폐기했다(원인이 둘인데 화면이 가를 근거가 없다). **mutation 실증 8건 전부 원복 확인** — 아래 F-12e 절 참조 |
| 9 | 2026-08-18 | 0건 | 13건(TC-PORTAL-122~127, TC-PORTALUP-098~104) | 0건 | **★F-12f 신설 — 대용량 다운로드 취소 + 업로드 원본 경로의 30초 상한 결함.** ①대용량 전송을 시작하면 끝날 때까지 멈출 수 없었다 — **취소를 대용량 두 경로에만** 둔다(포털 작업 데이터 묶음 · 포털 업로드 **원본 파일**). 라벨 내보내기·공지 첨부·통계 리포트는 작아서 취소 버튼이 뜨기 전에 끝나므로 두지 않는다. ②**포털 업로드 원본 파일(최대 5GB)이 여전히 공용 기본값 30초** 였다 — 회차 8 이 데이터마트에서 고친 것과 똑같은 결함이 형제 경로에 남아 있었다(전용 상한 3시간). ★★**사용자 취소는 오류가 아니라 정상 종료**라 안내를 띄우지 않는다 — 판정은 오류 객체가 아니라 **화면이 쥔 중단 신호**로 하며(공용 `ApiError` 는 취소 표식을 남기지 않는다) 회차 8 의 «응답 없는 실패» 통합은 **뒤집지 않는다**(사용자 취소만 그 판정 앞에서 갈라낸다). 회차 8 의 «취소 수단이 없다» 근거 서술은 **폐기**하되 유한 상한은 유지(취소는 사용자가 화면을 볼 때만 동작하는 수동 장치라 대체가 아니다). **mutation 실증 10건 전부 원복 확인** — 아래 F-12f 절 참조 |
| 10 | 2026-08-18 | 1건(TC-PORTALUP-083 케이스명에서 하드코딩 수치 제거) | 9건(TC-PORTALUP-105~111, TC-PORTAL-128~129) | 0건 | **★F-12g~i 신설 — 방치 업로드 자산의 실패 전이·스윕 자기 토글 분리·비동기 스트리밍 응답 서버측 절대 제한시간.** ①**F-12g** — 「업로드됨·처리중」 자산이 **최종 변경 일시(MDFCN_DT) 무갱신으로 설정된 시간**(`portal.upload.stuck-timeout-minutes`, 기본 30분)이 지나면 `FAILED` 로 강제 전이되고, 그러면 이미 있는 실패 보존기간(기본 1일)이 그 자산을 지운다. **판정 축은 총 처리 시간이 아니다** — 프레임 추출 러너의 하트비트가 그 값을 계속 갱신하므로 대용량 영상의 추출이 몇 시간 걸려도 방치로 판정되지 않는다(TC-PORTALUP-105). 설정이 0 이하·해석 불가면 **그 회차의 전이를 건너뛴다**(기본값 폴백 없음 — 전이는 삭제의 예고라 잘못된 설정으로 정상 처리 중인 자산을 즉시 죽이면 안 된다, TC-PORTALUP-106~107). 회귀로 유효값은 그대로 판정에 쓰인다(TC-PORTALUP-108). 기존 `TC-PORTALUP-083` 의 케이스명에 박혀 있던 `30m` 하드코딩은 **설정값 축**으로 정정했다(값 자체가 운영자가 바꿀 수 있는 설정인데 기본값이 케이스명에 고정돼 있었다). ②**F-12h** — 정리 스윕(`portal.upload.sweep.enabled`)과 보존기간 삭제 스윕(`portal.retention.sweep.enabled`)이 **각자 전용 토글**로 스케줄링된다(TC-PORTALUP-110~111). 구 동작은 두 잡 모두 자기 `@EnableScheduling` 없이 관제 통지·작업락 스윕이 켜 둔 스케줄링에 얹혀 있어, 포털과 무관한 그 기능을 끄면 두 스윕이 **소리 없이 멈췄다**(예외·로그·실패 테스트 없음) — `PortalSweepSchedulingIT` 로 회귀 고정. ③**F-12i** — 비동기 스트리밍 응답(`StreamingResponseBody`)의 **서버측 절대 제한시간**이 설정에 없으면 컨테이너 기본값 30초가 적용되는데, 이 값은 "다음 쓰기까지의 공백"이 아니라 **처리 시작 이후의 절대 경과시간**이라 서버가 계속 데이터를 써도 리셋되지 않는다 — 그래서 포털 작업 데이터 내려받기가 화면 쪽 제한시간을 아무리 늘려도 사실상 전부 30초에 잘리고 있었다. 공통 `application.yml` 에 `spring.mvc.async.request-timeout=1800000`(30분, 전 프로파일 공통·어떤 프로파일도 덮어쓰지 않음)을 명시해 해소했다(TC-PORTAL-128~129, `AsyncRequestTimeoutConfigGuardTest`). 신규 사례 0건, 코드는 이미 반영돼 있었고 카탈로그 갱신만 이번 회차의 작업 |
| 11 | 2026-08-18 | 1건(TC-PORTALUP-105 기대결과 교체) | 4건(TC-PORTALUP-112~115) | 0건 | **★F-12g 신설 직후 검증 정정 — 하트비트 판정 서술이 그 시점엔 거짓이었다.** 회차 10 이 문서화한 *"프레임 N 장마다 하트비트라 대용량 영상의 추출이 몇 시간 걸려도 방치로 판정되지 않는다"* 는 **검증 결과 사실이 아니었다** — 실측: 하트비트가 프레임 50장마다 한 번뿐이었고 **프레임 1장의 추출 비용은 영상 내 위치에 선형 비례**해 그 간격에 상한이 없어, 정상 추출 중인 자산이 방치로 오판돼 FAILED 로 죽고 실패 보존기간(1일) 경과 후 **원본까지 삭제될 수 있었다.** 같은 회차 안에서 다음으로 고쳤다(코드+카탈로그 동시 반영): ①하트비트를 **프레임 개수가 아니라 경과 시간** 기준으로 전환 ②하트비트 간격을 **상수가 아니라 방치 커트라인에서 파생**(1/4, 5초~60초 clamp — TC-PORTALUP-112) ③**프레임 추출 프로세스에 대기 상한 신설**(`authoring.ffmpeg.frame-timeout-sec`, 기본 600초, 전엔 무한 대기 — TC-PORTALUP-114). 이 셋이 함께 `무갱신 최대 경과 ≤ 하트비트 간격 + 프레임 대기 상한 < 방치 커트라인` 을 성립시켜 유계를 보장한다(TC-PORTALUP-113, `PortalFrameExtractHeartbeatTest`). **대가**: 프레임 한 장이 대기 상한을 넘기면 그 추출은 실패로 끝난다 — 조용한 방치 오판이 진단 가능한 실패로 바뀐 것이며, 대기 상한을 늘릴 때는 위 부등식이 계속 성립하는지 함께 확인해야 한다. 또한 회차 10 이 *"설정이 비었거나 0 이하·해석 불가면 건너뛴다"* 로 뭉뚱그린 것도 부정확했다 — **해석 불가·빈 값은 런타임 건너뛰기가 아니라 애플리케이션 기동 자체가 실패**한다(`stuckTimeoutMinutes` 는 타입이 있는 설정값이라 바인딩 시점에 걸러진다). 0 이하일 때만 기동엔 성공하고 그 회차를 건너뛴다(TC-PORTALUP-106·107 은 무변경, 신규 TC-PORTALUP-115 가 바인딩 실패 갈래를 별도로 검증). 위 `docs/v2-wiki/16-portal.md` §16.6 「방치된 업로드 자산 → FAILED 전이」 절도 같은 정정을 반영했다 |
| 12 | 2026-08-24 | 2건(TC-PORTALUP-007 — **기대결과 반전**, 입력값 22MB→500MB 초과로 재설계 · TC-PORTALUP-008 — 산식 「50×21MB」→「20MB×50장」) | 0건 | 0건 | **수동 업로드 운영(prd) 노출 — CO-008.** prd 개당 multipart 한도가 **21MB → 500MB** 로 올라 이 카탈로그의 기대결과가 바뀐다. ⚠ **구 정책 폐기**: 「prd 는 개당 21MB 로 좁게 조인다 · 그 근거는 dev 업로드 경로 부재」 — 둘 다 무효다. 수동 업로드가 운영 상시 기능이 되면서 전제가 무너졌고, **개당 한도는 이제 전 프로파일 500MB 로 같다**(요청 총량만 prd 1100MB / 그 밖 1200MB 로 갈린다) |

> **2026-08-05 헤더 카운트 정정(케이스 내용 변경 없음)**: 머리말 총계 169 → **177** 로 실측 정정. 후속 회차가 행을 추가하면서 머리말만 169 로 남아 있었다 — [README](README.md) 최신화 이력에는 이미 "회차 2 … F 169→177" 로 기록돼 있어 **README 와 이 파일 머리말이 서로 달랐다**. **카운트 기준 = 표 행 실측(폐기 행 포함)**.
>
> ✅ **2026-08-05 ID 중복 해소(회차 4 — 머지 합류로 3→4 재부여)**: `TC-PORTAL-060`·`061`·`062` 가 F-3 절(신규 저장 검증)과 F-4 절(포털 SAM2 제거 확인)에 **각각 다른 케이스로** 중복돼 있었다. **F-3 쪽을 `TC-PORTAL-095`·`096`·`097` 로 재번호**했다 — 1·2차 검증 결과 문서가 이 3개 ID 를 **F-4(포털 SAM2)** 케이스로 인용하고 있어(F-ISSUE-41) 그쪽 번호를 유지해야 추적성이 끊기지 않는다. 케이스 내용·행 수는 불변이라 카운트(177) 영향 없음.

## F-1. 채널·역할 게이팅 / 인가 경계

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-001 | PORTAL_USER+PORTAL 채널로 /v1/portal/** 허용 | PORTAL_USER | role=PORTAL_USER,channel=PORTAL | 200 | integration | High | SecurityConfig.java |
| TC-PORTAL-002 | INTERNAL 채널(WORKER)로 /v1/portal/** 403 | WORKER/INTERNAL | GET /v1/portal/datamart/videos | 403 | security | High | SecurityConfig.java |
| TC-PORTAL-003 | REVIEWER(INTERNAL)로 포털 API 403 | REVIEWER | /v1/portal/uploads | 403 | security | High | SecurityConfig.java |
| TC-PORTAL-004 | PORTAL_USER이나 channel≠PORTAL → 403 | role=PORTAL_USER,channel=INTERNAL | /v1/portal/** | 403(allOf 두 조건) | security | High | SecurityConfig.java |
| TC-PORTAL-005 | PORTAL_USER가 내부 /v1/frames/** 차단 | PORTAL 채널 | POST /v1/frames/{srcSn}/sam2-track(내부) | 403 — `/v1/frames/**` 전용 매처는 없고 하위 catch-all `/v1/**`(CHANNEL_INTERNAL 필수)이 차단 | security | High | SecurityConfig.java |
| TC-PORTAL-006 | PORTAL_USER가 /v1/manage/** 차단 | PORTAL 채널 | PUT /v1/manage/labels/... | 403 | security | High | SecurityConfig.java |
| TC-PORTAL-007 | PORTAL_USER가 /v1/notices 차단 | PORTAL 채널 | GET /v1/notices | 403 | security | Med | SecurityConfig.java |
| TC-PORTAL-008 | 라벨 마스터 조회(GET)는 PORTAL_USER 허용 | PORTAL 채널 | GET /v1/manage/labels | 200(authenticated) | integration | Med | SecurityConfig.java |
| TC-PORTAL-009 | 토큰 sub 없음 → 401 | actor.sub()==null | 포털 컨트롤러 진입 | 401 | unit | High | PortalUploadController.java |
| TC-PORTAL-010 | FE 채널가드: PORTAL_USER의 내부 화면 접근 → forbidden | FE 라우팅 | PORTAL_USER /video/completed | FORBIDDEN_PAGE | unit | Med | portalGuard.test.tsx |
| TC-PORTAL-011 | FE 채널가드: INTERNAL의 /portal 접근 → forbidden | FE 라우팅 | 내부 사용자 /portal | FORBIDDEN_PAGE | unit | Med | portalGuard.test.tsx |

> 근거 라인 전면 재확인(07-30): `SecurityConfig.java`는 Phase 1(dd288bed, 웹훅 인증 우회 차단 + 인가 표면 15건) · Phase 7(41b0504d, `/v1/genai/callback` permitAll 추가 등)에서 매처 순서·행이 이동했다. `/v1/portal/**` 매처는 108→134-135 행으로 이동. `/v1/frames/**` 전용 매처는 원래도 없었고(구 근거 "121-122"는 부정확했음), 실제로는 하위 범용 `/v1/**`(CHANNEL_INTERNAL + REVIEWER/WORKER/STREAM_SIGNED) 매처(147-153행)가 차단한다 — TC-PORTAL-005 근거를 정정.

## F-2. 데이터마트 Load (APPROVED 게이트 · IDOR · 비식별 서빙 · 신고 게이트)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-020 | 데이터마트 목록은 APPROVED만 노출 | 검수완료/미완료 혼재 | GET /v1/portal/datamart/videos | APPROVED만(INNER JOIN) | integration | High | PortalLabelService.java |
| TC-PORTAL-021 | 프레임 0건 영상 목록 제외 | firstSrcSn 부재 | 목록 조회 | 제외 | unit | Med | PortalLabelService.java |
| TC-PORTAL-022 | 목록 N+1 회피(IN 조회) | 다수 영상 | 목록 | 각 1회 IN 쿼리 | unit | Low | PortalLabelService.java |
| TC-PORTAL-023 | 라벨 Load 페이징 clamp | rawSn 존재 | size=99999,page=-1 | size≤100, page≥0 | unit | Med | PortalLabelService.java |
| TC-PORTAL-024 | rawSn null 라벨 Load → 400 | - | rawSn 누락 | INVALID_INPUT | unit | Med | PortalLabelService.java |
| TC-PORTAL-025 | 프레임 라벨 Load: 본인 user-label 우선 | 본인 저장분 존재 | GET labels | user-label만 | unit | High | PortalLabelService.java |
| TC-PORTAL-026 | 프레임 라벨 Load: 없으면 datamart 원본 | 본인 저장분 0 | 동일 | datamart 원본 | unit | High | PortalLabelService.java |
| TC-PORTAL-027 | 미승인 영상 프레임 라벨 Load → 403 | not APPROVED | GET labels | 403 | security | High | PortalLabelService.java |
| TC-PORTAL-028 | 비존재 srcSn Load → 404 | 부재 | 동일 | 404 | unit | Med | PortalLabelService.java |
| TC-PORTAL-029 | 프레임 이미지 서빙: APPROVED 비식별만 | APPROVED | GET image | 200+nosniff+**no-store**(구 private/max-age=300 폐기, 아래 TC-PORTAL-037) | integration | High | PortalLabelService.java |
| TC-PORTAL-030 | 프레임 이미지 서빙: 미승인 → 403 | 비APPROVED | 동일 | 403 | security | High | PortalLabelService.java |
| TC-PORTAL-031 | deid 경로 부재 시 원본 폴백 금지 → 404 | deidFilePath null/blank | 동일 | 404(원본 차단) | security | High | PortalLabelService.java |
| TC-PORTAL-032 | 프레임 이미지 Path Traversal 차단 | 경로 조작 | baseDir 밖 | `StorageSubtreePolicy.verifyDeidentifiedFile` 거부(구 resolveSafe 는 폐기·대체됨) | security | High | PortalLabelService.java |
| TC-PORTAL-033 | baseDir=deidentified-path(raw 회귀 방지) | deid 절대경로 | 서빙 | 실경로(toRealPath) 기준 서브트리 검증(구 startsWith lexical 검증은 심링크 우회 가능해 폐기·대체됨) | unit | Med | PortalLabelService.java |
| TC-PORTAL-034 | 파일 부재 시 내부경로 비노출 404 | 파일 없음 | 서빙 | 404, 경로 미노출 | security | Med | PortalLabelService.java |
| TC-PORTAL-035 (신규) | 신고 구간(DE_IDNTF_YN='F') 프레임 라벨 Load → 412 | 자기 rawSn 신고 중 | GET labels | 412(PRECONDITION_FAILED) — APPROVED 게이트만으로는 안 걸림(신고는 LS_RAW_DATA_STATUS 를 건드리지 않음) | security | High | PortalLabelService.java |
| TC-PORTAL-036 (신규) | 신고 구간 프레임 이미지 서빙 → 412(파일 읽기 전 차단) | 자기 rawSn 신고 중 | GET image | 412, 파일 미판독 | security | High | PortalLabelService.java |
| TC-PORTAL-037 (신규) | 프레임 이미지 응답 캐시는 no-store로 통일 | 임의 프레임 | GET image | `Cache-Control: no-store`(구 `private, max-age=300` 폐기 — 신고 직후 최대 5분 재노출되던 경로 차단) | security | High | PortalLabelService.java |
| TC-PORTAL-038 (신규) | 신고 게이트는 자기 rawSn 행만 판정(조상/자손 전파 없음) — 파생영상은 부모 신고와 무관 | 부모 'F', 자기 rawSn 'Y' | GET labels/image (파생 srcSn) | 200(정상 서빙) — "파생 경유 열람"은 확정 정책(CLAUDE.md 2026-07-29)의 필연적 귀결이며 결함 아님. 조상 순회는 4라운드 시도 후 철회됨 | security | High | DeidentReportGate.java(javadoc), AiInferenceDeidentReportGateTest.java(`segmentNotBlockedByOriginReport` — 내부 SAM2 경로로 동일 게이트 검증, 포털도 같은 컴포넌트 재사용) |
| TC-PORTAL-039 (신규) | **데이터마트 라벨 Load 미승인/미존재 → 403** | 비APPROVED 또는 미존재 rawSn | GET /v1/portal/datamart/labels?rawSn | 403(FORBIDDEN), **라벨 풀조회 미수행**. 미존재와 미승인을 구분하지 않는다(존재 여부 오라클 차단 — CWE-209). 이 메서드에만 게이트가 복제 누락돼 rawSn 하나로 전건 열람이 가능했다(CWE-862/639) | security | High | PortalLabelService.java, PortalUserLabelServiceTest.java(미승인_PENDING_영상의_datamart_라벨조회는_403이다 / rawSn이_존재하지_않으면_예외없이_403이다) |
| TC-PORTAL-051 (신규) | **데이터마트 라벨 Load 신고 구간 → 412** | APPROVED + `DE_IDNTF_YN='F'` | 동일 | 412(PRECONDITION_FAILED). 신고는 LS_RAW_DATA_STATUS 를 건드리지 않아 APPROVED 게이트만으로는 안 걸린다. resolve('F'→'Y') 로 자동 복원(200) | security | High | PortalLabelService.java, PortalUserLabelServiceTest.java(비식별신고구간_영상의_datamart_라벨조회는_412이다 / 신고_해제_후_datamart_라벨조회는_다시_200으로_복원된다) |
| TC-PORTAL-052 (신규) | **라벨 Load 페이징 정수 오버플로 → 500 없음** | APPROVED | `page=2147483647&size=100` | 빈 리스트(200). 구현이 `page*size` 를 int 로 계산해 음수로 접히면 `subList` IndexOutOfBounds → 500 이었다(CWE-190/129, 인증된 PORTAL_USER 누구나 트리거). long 연산으로 방어 | security | High | PortalLabelService.java, PortalUserLabelServiceTest.java(page가_Integer_MAX_근처여도_오버플로_없이_빈리스트를_반환한다) |
| TC-PORTAL-053 (신규) | 라벨 Load 페이징 clamp 경계 회귀 | APPROVED | size=0/-10 → 1, size=99999/MAX → 100, page=-5/MIN → 0 | clamp 값대로 반환 | unit | Med | PortalLabelService.java, PortalUserLabelServiceTest.java(size가_1미만이면_1로_clamp된다 외 2건) |

## F-3. 데이터마트 사용자 라벨 저장 (단방향 · 원본 미수정 · IDOR)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-040 | 사용자 라벨 저장은 LS_PORTAL_USER_LABEL만 | APPROVED | POST user-labels | 201, 원본 LS_DATA_LBL 불변 | integration | High | PortalLabelService.java |
| TC-PORTAL-041 | 미승인 sourceRawSn 저장 시도 → 403 | 비APPROVED | 저장 | 403 | security | High | PortalLabelService.java |
| ~~TC-PORTAL-042~~ | ~~SKELETON 저장: 17점 삼중값 통과~~ | **폐기 (2026-08-03)** — 포털 키포인트 서버 기능 제거. 대체: TC-PORTAL-058 | - | - | - | - | PortalKeypointRemovedTest.java |
| ~~TC-PORTAL-043~~ | ~~SKELETON 개수≠17 → 400~~ | **폐기** — SKELETON 자체가 400(타입 allowlist 위반) | - | - | - | - | 상동 |
| ~~TC-PORTAL-044~~ | ~~SKELETON v 범위 밖 → 400~~ | **폐기** — 상동 | - | - | - | - | 상동 |
| ~~TC-PORTAL-045~~ | ~~SKELETON NaN/Infinity → 400~~ | **폐기** — 상동 | - | - | - | - | 상동 |
| TC-PORTAL-046 | 빈 좌표('[]','[[]]') 거부 → 400 | type=BBOX, points='[]' | 저장 | 400(빈 row 차단) | unit | High | PortalLabelService.java(saveUserLabel) |
| TC-PORTAL-058 (신규) | **lblTypeCd allowlist — BBOX\|POLYGON 만** | APPROVED | type=SKELETON / SEGMENT / TRACK / 임의 문자열 | 400, 저장 미수행(fail-closed). FE 도구 게이팅은 신뢰 경계가 아니며, allowlist 부재로 16자 이하 임의 문자열이 그대로 `LBL_TYPE_CD` 에 적재됐다. BBOX/POLYGON 은 정상 저장(회귀) | security | High | PortalLabelService.java(validateAndNormalizeType), PortalKeypointRemovedTest.java |
| TC-PORTAL-059 (신규) | **레거시 SKELETON row 조회 안전** | 정책 이전 적재된 삼중값 row 존재 | GET /v1/portal/frames/{srcSn}/labels | 예외 없이 해당 항목만 스킵(500 미발생), BBOX/POLYGON 은 정상 반환 | unit | Med | PortalLabelService.java(parsePoints), PortalKeypointRemovedTest.java |
| TC-PORTAL-095 (신규 · 구 TC-PORTAL-060) | **좌표 개수 상한(CWE-770)** | APPROVED | POLYGON 201점 / 2점, BBOX 3점 | 400. 형제 `PortalUploadLabelService` 상수(BBOX=2, POLYGON 3~200) 재사용 — 길이 상한(65,536자)과 별개 층 | security | Med | PortalLabelService.java(validatePointCount), PortalUserLabelServiceTest.java |
| TC-PORTAL-096 (신규 · 구 TC-PORTAL-061) | **저장 경로 비식별 신고 게이트** | APPROVED + `DE_IDNTF_YN='F'` | POST /v1/portal/user-labels | 412, 저장 미수행. 조회 4경로는 모두 게이트를 갖는데 저장만 누락돼 있었다 | security | Med | PortalLabelService.java(saveUserLabel), PortalUserLabelServiceTest.java(비식별신고구간_영상의_본인라벨_저장은_412이다) |
| TC-PORTAL-097 (신규 · 구 TC-PORTAL-062) | **저장 per-user 속도 제한** | - | 같은 사용자 연속 POST(한도 초과) | 429(TOO_MANY_REQUESTS), 사용자별 격리(타 사용자 무영향). 라벨 행은 삭제 API 가 없어 누적되므로 유입 속도 제한이 자원 방어선 | security | Med | PortalLabelController.java(acquireSavePermit), PortalLabelControllerRateLimitTest.java |
| TC-PORTAL-047 | points @NotBlank NULL/공백 → 400 | null/공백 | 저장 | 400(DTO) | unit | Med | PortalUserLabelRequest.java |
| TC-PORTAL-048 | 본인 작업 라벨 조회 IDOR(token sub) | 타인 저장분 존재 | GET user-labels?rawSn | 본인만 | security | High | PortalLabelService.java |
| TC-PORTAL-049 | 빈 user-label row Load 제외(stale 방어) | pointCn NULL row | Load | 빈 항목 필터 | unit | Med | PortalLabelService.java |
| TC-PORTAL-050 | 손상 좌표 JSON fail-secure | pointCn 손상 | Load | 빈 좌표, 500 미발생 | unit | Med | PortalLabelService.java |
| TC-PORTAL-054 (신규) | **본인 라벨 조회 게이트 대칭 — 미승인 403 / 신고구간 412** | 비APPROVED, 또는 APPROVED + `'F'` | GET /v1/portal/user-labels?rawSn | 403 / 412, 조회 미수행. 형제 경로(datamart labels·frame labels)와 **동일 순서·동일 컴포넌트**. 이 경로만 무게이트라 신고 구간에도 동일 좌표가 다른 URL 로 200 으로 새어나갔다(CWE-862/359) | security | High | PortalLabelService.java, PortalUserLabelServiceTest.java(미승인_영상의_본인라벨_조회는_403이다 / 비식별신고구간_영상의_본인라벨_조회는_412이다 / APPROVED_비신고_영상의_본인라벨_조회는_기존과_동일하게_200이다) |
| TC-PORTAL-055 (신규) | **사용자 라벨 저장 본문 크기 상한(pre-parse)** | - | POST /v1/portal/user-labels, Content-Length > 상한 / chunked | 413 / 411, **체인 미진행**(Jackson 역직렬화 전 차단). 인코딩(`%6C`)·trailing slash·matrix 변형에서도 동일. 구 테스트가 이 경로를 "상한 미적용이 정상"으로 단언해 결함이 고착돼 있었다(CWE-770) | security | High | PortalLabelBodySizeFilter.java, PortalLabelBodySizeFilterTest.java(본인라벨_저장_요청도_본문상한이_적용된다 외 3건) |
| TC-PORTAL-056 (신규) | **points 필드 길이 상한(파싱 후)** | - | points 65,536자 초과 | 400(@Valid). 좌표 JSON 은 문자열이라 타입 구조로 제한되지 않고 적재 컬럼도 TEXT 무제한이었다. 본문 필터(pre-parse)와 **2층 방어**로 서로 대체하지 않는다 | security | High | PortalUserLabelRequest.java, PortalUserLabelRequestValidationTest.java |
| TC-PORTAL-057 (신규) | 경로 판정이 servlet-path-prefix 까지 MVC 와 정합 | `spring.mvc.servlet.path=/api2` 형상 | 라벨 PUT · user-labels POST | 413(상한 적용). 자체 파싱(`RequestPath.parse(uri, ctx)`)은 contextPath 만 반영해 "MVC 는 라우팅, 필터는 스킵" fail-open 이 재발한다 → `ServletRequestPathUtils.parseAndCache` 단일 규약으로 통일(CWE-436 잔여). 동일 결함 클래스의 `WebhookProtectedPaths` 도 함께 정정 | security | Med | PortalLabelBodySizeFilter.java, WebhookProtectedPaths.java, PortalLabelBodySizeFilterTest.java(servlet_path_prefix_설정_환경에서도_MVC와_동일_경로로_판정한다), WebhookProtectedPathsServletPrefixTest.java |

> **ID 채번 주의**: TC-PORTAL-040~050 이 이미 사용 중이라 3차 QA 신규 케이스는 039 + 051~057, 이어서 **058~062** 로 채번했다(섹션 순서와 번호가 연속하지 않는다). ⚠ 구 노트가 057 까지만 기술해 058~062 중복 채번 위험이 있었다(2026-08-03 3차 실측 정정).
> 그중 **060~062 는 F-4 절(포털 SAM2 제거 회귀)의 동명 ID 와 실제로 충돌**해 2026-08-05 에 이 절 쪽을 **095~097 로 재번호**했다(구 060·061·062). 따라서 이 절이 보유한 번호는 **039 · 051~059 · 095~097** 이며, **행 위치는 원래 자리(저장 검증 맥락)를 유지**했다 — 번호 순서로 읽지 말 것.
> ⚠ **다음 신규는 098 부터** 채번한다(구 노트의 "063 부터"는 재번호 이후 무효).

## F-4. 포털 SAM2 (★2026-08-03 **제거 완료** — ADR-013 정합)

> ★확정: CLAUDE.md/ADR-013상 포털 SAM2는 **제공되지 않는다**. 구 정책 위반(엔드포인트·FE 도구 노출)은
> 2026-08-03(dcdbb827) 에 **BE 컨트롤러/서비스 삭제 + FE 도구·단축키·단축키 안내 게이팅**으로 해소됐다
> (08-02 자 67dc48ca 는 이 시점엔 fail-closed 보정만 했고 실제 파일 삭제는 08-03 이었다 — 08-03 정정).
> 따라서 구 TC-PORTAL-060~071·075~077(포털 SAM2 동작 케이스)은 **대상 코드가 존재하지 않아 폐기**한다.
> *(3차 정정 — 구 표기 `075~078` 은 오기였다. TC-PORTAL-078 은 폐기가 아니라 아래 표의 **활성 신규 케이스**다.)*
> 남은 검증은 "제거됐음"의 회귀 고정뿐이며, 내부(INTERNAL) SAM2 는 SFR-08-01(VOS) 핵심 기능으로 무변경이다.
>
> ⚠ 신고 게이트·비식별본 전용 전송(구 TC-PORTAL-075~077)의 **내부 경로 검증은 계속 유효**하며
> `AiInferenceDeidentReportGateTest` 가 담당한다(포털 절만 제거됨).
>
> ✅ **ID 충돌 해소 (3차 발견 F-ISSUE-01 → 2026-08-05 회차 4 에서 해소)** — 이 절의
> `TC-PORTAL-060`·`061`·`062` 가 **F-3 절의 동명 케이스**(좌표 개수 상한 / 저장 경로 신고 게이트 /
> 저장 per-user 속도 제한)와 겹쳐 있었다. F-3 머리말이 "039 + 051~057 로 채번"이라 적어 두고 실제로는
> 058~062 까지 채번해 이 절의 기존 060~062 를 침범한 것이었다. **F-3 쪽을 `095`·`096`·`097` 로
> 재번호**해 해소했다 — 1·2차 검증 결과 문서가 이 3개 ID 를 **본 절(포털 SAM2)** 케이스로 인용하고
> 있어 인용이 깨지지 않는 쪽을 옮겼다. **본 절의 060~062 = 포털 SAM2 제거 회귀**로 그대로 유지된다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과(정책 기준) | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-060 (갱신) | 포털 SAM2 분할 엔드포인트 **미제공 확정** | PORTAL 토큰(채널 통과) | POST /v1/portal/frames/{srcSn}/sam2-segment (path≠body srcSn — 구 컨트롤러면 400) | **404**(핸들러 부재). 400 이 나오면 컨트롤러가 되살아난 것 | integration | High | PortalSam2RemovedTest.java(POST_portal_frames_sam2_segment_엔드포인트는_더이상_존재하지_않는다) |
| TC-PORTAL-061 (갱신) | 포털 SAM2 추적 엔드포인트 **미제공 확정** | PORTAL 토큰 | POST /v1/portal/frames/{srcSn}/sam2-track (path≠body srcSn) | **404**(핸들러 부재) | integration | High | PortalSam2RemovedTest.java(POST_portal_frames_sam2_track_엔드포인트는_더이상_존재하지_않는다) |
| TC-PORTAL-062 (갱신) | 포털 경로에 SAM2 핸들러 매핑 0건 | 앱 기동 | RequestMappingHandlerMapping 스캔 | `/v1/portal/**` 중 `sam2` 포함 패턴 0건 — 경로명을 바꿔 되살리는 퇴행까지 차단 | integration | High | PortalSam2RemovedTest.java(포털_경로에_SAM2_핸들러_매핑이_한_건도_등록되지_않는다) |
| TC-PORTAL-063 (갱신·회귀) | **내부 SAM2 는 영향 없음** | 앱 기동 | 매핑 스캔 | `/v1/frames/{srcSn}/sam2-segment`·`sam2-track` 매핑 잔존(SFR-08-01 VOS) | integration | High | PortalSam2RemovedTest.java(내부_INTERNAL_채널의_SAM2_엔드포인트는_영향받지_않는다) |
| TC-PORTAL-064 (갱신·회귀) | 내부 SAM2 는 포털 토큰에 여전히 403 | PORTAL 토큰 | POST /v1/frames/{srcSn}/sam2-track | 403(채널 격리) — 포털 전용 경로 제거가 내부 경로를 외부에 열지 않았다 | security | High | PortalSam2RemovedTest.java(내부_SAM2_엔드포인트는_포털_토큰에_대해_기존대로_403을_유지한다) |
| TC-PORTAL-065 (갱신) | 소스에 포털 SAM2 잔재 0건 | - | `grep -rn "PortalSam2" backend/src` | 회귀 테스트 파일 외 0건(컨트롤러·서비스·Bulkhead 빈·yml config 전부 제거) | unit | Med | Resilience4jConfig.java, application.yml(resilience4j) |
| TC-PORTAL-072 (갱신) | FE 포털 도구바: AI분할/AI추적/스켈레톤 **미노출** | 포털 모드 | 라벨링 화면 | 세 버튼 모두 부재, BBOX/폴리곤은 잔존(과잉 차단 가드) | unit | Med | ToolBar.test.tsx(구 `DarkToolbar.test.tsx` — 2026-08-07 개명), LabelingPagePortalRestrictions.test.tsx |
| TC-PORTAL-072a (신규) | FE 포털 단축키 게이팅: G / Shift+T / K 무반응 | 포털 모드 | keydown | activeTool 이 SAM_SEGMENT/TRACK/KEYPOINT 로 바뀌지 않음(버튼만 숨기면 키로 우회됨), B/P 는 정상 동작 | security | Med | useLabelingShortcuts.test.tsx |
| TC-PORTAL-072b (신규) | FE 포털 단축키 **안내**에서도 제외 | 포털 모드 | 단축키 도움말(?) | 'AI 분할'·'AI 추적'·'스켈레톤' 행 미표시(내부 모드는 표시) | unit | Low | ShortcutCheatSheet.test.tsx |
| TC-PORTAL-072c (신규·회귀) | FE 내부 라벨링 도구/단축키는 무변경 | 내부 모드 | 도구바·keydown | 세 도구 버튼 노출 + G/Shift+T/K 정상 전환 | unit | High | ToolBar.test.tsx(구 `DarkToolbar.test.tsx` — 2026-08-07 개명), useLabelingShortcuts.test.tsx |
| TC-PORTAL-073 | FE 포털 라벨링: 검수제출 버튼 미렌더 | 포털 모드 | 화면 | submit-review-button 없음 | unit | Med | LabelingPagePortalRestrictions.test.tsx |
| TC-PORTAL-074 | FE 포털 라벨링: VLM/시계열 메타 탭 미노출 | 포털 모드 | 화면 | 메타 탭·VLM 텍스트 없음 | unit | Med | LabelingPagePortalRestrictions.test.tsx |
| TC-PORTAL-075~077 (폐기) | 구 포털 SAM2 의 비식별본 전송·신고 게이트 케이스 | - | - | **대상 코드 삭제(2026-08-03 `dcdbb827`)로 폐기.**(3차 정정 — 구 표기 `2026-08-02` 는 오기. 08-02 `67dc48ca` 는 수정만 했고 파일 삭제는 08-03 이며 머리말과 일치시킴) 동일 방어의 내부 경로 검증은 계속 유효 — 신고 구간 412 + `verifyNoInteractions(aiServerClient)` | security | High | AiInferenceDeidentReportGateTest.java(내부 SAM2 분할/추적·YOLO 추적·온라인 오토라벨) |
| TC-PORTAL-078 (신규) | 게이트 없는 `encodeToBase64(String)` 오버로드는 더 이상 존재하지 않는다(원본픽셀 유출 경로 삭제 확인) | - | 정적 확인 | 모든 외부 추론 전송이 `resolveFrameImageForInference`/`encodeFrame`/`encodeDeidentifiedFrameForInference` → private `encode(Path)` 로 수렴, public 문자열 오버로드 부재 | unit | High | FrameImageEncoder.java(주석) |

## F-5. 포털 이미지 업로드 (파일 검증 · all-or-nothing · 경계)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-001 | 정상 다중 이미지 업로드 → 201, UUID·READY | jpg/png ≤20MB | POST /portal/uploads/images | 201, 대표 프레임, READY | integration | High | PortalUploadService.java |
| TC-PORTALUP-002 | 이미지 수 상한(50) 초과 → 400 | 51장 | 업로드 | 400 | unit | High | PortalUploadService.java |
| TC-PORTALUP-003 | 정확히 50장 경계 → 성공 | 50장 | 업로드 | 201 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-004 | 빈 목록 → 400 | empty | 업로드 | 400 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-005 | 개당 20MB 초과 → 400 | 21MB | 업로드 | 400(서비스 검증 `maxImageSizeBytes`, 프로파일 무관 상수) | unit | High | PortalUploadService.java, PortalUploadProperties.java |
| TC-PORTALUP-006 | 20MB 경계값 → 성공 | 정확히 20MB | 업로드 | 201 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-007 | multipart max-file-size 초과 → 413 (**회차 정정 — 기대결과 반전**) | 전 프로파일, **500MB 초과** | 업로드 | **개당 한도가 전 프로파일 500MB 로 같아져 프로파일 구분이 사라졌다.** 500MB 초과분만 파싱 단계 413 이고, 그 이하(예 22MB)는 파싱을 통과해 **서비스 20MB 검증(400)** 에 걸린다. ⚠ 구 기대 「prd 22MB → 413」 폐기(2026-08-24 CO-008) — prd 개당 한도가 21MB→500MB 로 올라 **어느 프로파일도 22MB 에서 413 을 내지 않는다.** 413 을 보려면 입력을 500MB 초과로 바꿔야 해 케이스 입력값을 재설계했다 | integration | Med | application-prd.yml(spring.servlet.multipart) · application.yml(spring.servlet.multipart) · ConfigProfileDriftGuardTest(prd_프로파일_multipart_한도는_500MB_1100MB로_유지된다) |
| TC-PORTALUP-008 | multipart max-request-size 초과 → 거부(prd 한정) | prd 프로파일, 총량 초과 | 업로드 | prd: 1100MB 초과 거부(**포털 이미지 서비스 상한 20MB × 50장 + 헤드룸**). **local/dev/stg 는 1200MB** — 요청 총량은 여전히 프로파일마다 다르다. ⚠ 구 산식 「50×21MB」 폐기(2026-08-24 CO-008) — 21MB 는 그때의 서블릿 개당 한도였고 지금은 500MB 라 그 수치가 어디에도 한도로 없다(서블릿 한도로 오독한다) | integration | Med | application-prd.yml(spring.servlet.multipart) · ConfigProfileDriftGuardTest(prd_프로파일_multipart_한도는_500MB_1100MB로_유지된다) |
| TC-PORTALUP-009 | 확장자 allowlist 밖(gif/svg) → 400 | .gif | 업로드 | 400 | security | High | PortalUploadService.java |
| TC-PORTALUP-010 | 매직바이트 미탐지 → 400 | txt 내용 .jpg | 업로드 | 400(JPEG/PNG만) | security | High | PortalUploadService.java |
| TC-PORTALUP-011 | 확장자↔시그니처 불일치 → 400 | MIME 위조 | 업로드 | 400 | security | High | PortalUploadService.java |
| TC-PORTALUP-012 | truncated JPEG(EOI 없음) → 400 | header-only | 업로드 | 400(CWE-434) | security | Med | PortalUploadService.java |
| TC-PORTALUP-013 | all-or-nothing: 1장 실패 시 전체 미저장 | 50장 중 1장 위조 | 업로드 | 400, 디스크 미기록 | integration | High | PortalUploadService.java |
| TC-PORTALUP-014 | write 후 DB INSERT 실패 시 보상 삭제 | INSERT 예외 | 업로드 | 롤백+고아 파일 삭제 | integration | High | PortalUploadService.java |
| TC-PORTALUP-015 | 디스크 write IOException 시 롤백 | 디스크 고갈 | 업로드 | INTERNAL_ERROR+롤백 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-016 | 저장 파일명 UUID 강제(path traversal 없음) | 경로형 파일명 | 업로드 | UUID.ext 저장 | security | High | PortalUploadService.java |
| TC-PORTALUP-017 | 원본명 255자 초과 truncate | 긴 파일명 | 업로드 | 255자 절단 | unit | Low | PortalUploadService.java |
| TC-PORTALUP-018 | 업로드 per-user rate limit 초과 → 429 | 폭주 | 연속 업로드 | 429(CWE-770) | security | High | PortalUploadController.java |

## F-6. 포털 자산 조회/서빙/삭제 (IDOR · 페이징 · 상태)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-020 | 본인 자산 목록 페이징 | 소유 자산 | GET /portal/uploads | 본인 것만 | integration | High | PortalUploadService.java |
| TC-PORTALUP-021 | type 필터 IMAGE/VIDEO (대소문자 무관 — `toUpperCase` 정규화) | - | ?type=IMAGE / ?type=image | 필터 결과 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-022 | 미지원 type → 400 | ?type=FOO | 목록 | 400 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-023 | 페이지 크기 하드캡(100) | size=500 | 목록/프레임 | 100 클램프 | unit | Med | PortalUploadController.java |
| TC-PORTALUP-024 | 타 사용자 자산 상세 → 403 | 타인 uldSn | GET /{uldSn} | 403(부재와 동일) | security | High | PortalUploadService.java |
| TC-PORTALUP-025 | 타 사용자 프레임 목록 → 403 | 타인 uldSn | /{uldSn}/frames | 403 | security | High | PortalUploadService.java |
| TC-PORTALUP-026 | 타 사용자 프레임 이미지 → 403(IDOR) | 타인 uldFrmeSn | /frames/{uldFrmeSn}/image | 403 | security | High | PortalUploadService.java |
| TC-PORTALUP-027 | 프레임 이미지 서빙: DB MIME+nosniff, 신고 게이트 대상 아님 | 소유 이미지(본인 업로드분) | 서빙 | Content-Type=저장MIME, nosniff — **비식별 신고 게이트·no-store 통일 대상 아님**(ADR-013 예외, LS_DATA_RAW 라이프사이클 없음. TC-PORTAL-037 과 혼동 금지) | security | High | PortalUploadService.java |
| TC-PORTALUP-028 | 이미지 서빙 Path Traversal 차단 | 조작 경로 | 서빙 | resolveSafe 거부 | security | High | PortalUploadService.java |
| TC-PORTALUP-029 | 자산 삭제: 파일 먼저 삭제 후 DB CASCADE | READY | DELETE /{uldSn} | 204, 파일+행 삭제 | integration | High | PortalUploadService.java |
| TC-PORTALUP-030 | PROCESSING 중 삭제 → 409 | 추출 진행중 | DELETE | 409 | unit | High | PortalUploadService.java |
| TC-PORTALUP-031 | 파일 삭제 IOException 시 DB 행 보존 5xx | 파일 삭제 실패 | DELETE | 5xx, DB 미삭제 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-032 | 타 사용자 자산 삭제 → 403 | 타인 uldSn | DELETE | 403 | security | High | PortalUploadService.java |

## F-7. 포털 업로드 라벨 CRUD (전체교체 · 상한 · READY 가드 · 다운로드)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-040 | 라벨 전체교체(PUT) 멱등, 빈 배열=전체 삭제 | READY | PUT labels [] | 전체 삭제 | integration | High | PortalUploadLabelService.java |
| TC-PORTALUP-041 | 검증 실패 시 DELETE 미실행(기존 유지) | 1건 위조 | PUT | 400, 기존 보존 | integration | High | PortalUploadLabelService.java |
| TC-PORTALUP-042 | lblTypeCd allowlist(BBOX/POLYGON)만 | type=SEGMENT | PUT | 400 fail-closed | security | High | PortalUploadLabelService.java |
| TC-PORTALUP-043 | 라벨 배열 상한 500 초과 → 400 | 501건 | PUT | 400(@Size+서비스 이중) | security | High | PortalUploadLabelController.java |
| TC-PORTALUP-044 | BBOX 좌표 2점 아님 → 400 | BBOX 3점 | PUT | 400 | unit | Med | PortalUploadLabelService.java |
| TC-PORTALUP-045 | POLYGON 3~200점 경계 → 400 | 2점/201점 | PUT | 400 | unit | Med | PortalUploadLabelService.java |
| TC-PORTALUP-046 | 좌표 NaN/Infinity → 400 | Inf | PUT | 400 | security | Med | PortalUploadLabelService.java |
| TC-PORTALUP-047 | label 80자 초과 → 400 | 81자 | PUT | 400 | unit | Low | PortalUploadLabelService.java |
| TC-PORTALUP-048 | READY 외 상태 라벨 PUT → 409 | 비READY | PUT | 409 | unit | High | PortalUploadLabelService.java |
| TC-PORTALUP-049 | 동시 PUT 프레임 락 직렬화 | 병렬 PUT | 동일 프레임 | 비관적 락 | integration | Med | PortalUploadLabelService.java |
| TC-PORTALUP-050 | 타 사용자 프레임 라벨 PUT → 403 | 타인 uldFrmeSn | PUT | 403 | security | High | PortalUploadLabelService.java |
| TC-PORTALUP-051 | 라벨 PUT 본문 2MB 초과 → 413(파싱 전 조기) | 대용량 | PUT | 413(pre-parse DoS) | security | High | PortalLabelBodySizeFilter.java |
| TC-PORTALUP-052 | 라벨 PUT chunked/Content-Length 부재 → 411 | -1 | PUT | 411 | security | High | PortalLabelBodySizeFilter.java |
| TC-PORTALUP-053 | body size 필터 라벨 PUT 경로만 적용 | 다른 경로 | 다른 PUT | 필터 skip | unit | Low | PortalLabelBodySizeFilter.java |
| TC-PORTALUP-054 | 라벨 목록 조회(소유자 스코프), 타인/부재 403 | 타인 프레임 | GET labels | 403 | security | Med | PortalUploadLabelService.java |
| TC-PORTALUP-055 | export JSON: 고정명+라벨 N+1 회피 | 소유 자산 | GET export | JSON attachment | integration | Med | PortalUploadLabelService.java |
| TC-PORTALUP-056 | 원본 다운로드 Content-Disposition CRLF 인젝션 차단 | 파일명 CRLF | GET file | 제어문자 제거, filename* 인코딩 | security | High | PortalUploadLabelService.java |
| TC-PORTALUP-057 | 원본 다운로드: 경로 미확정/부재 → 404 | filePathNm null | GET file | 404 | unit | Med | PortalUploadLabelService.java |
| TC-PORTALUP-058 | 타 사용자 export/다운로드 → 403 | 타인 uldSn | export/file | 403 | security | High | PortalUploadLabelService.java |

> F-7 은 PortalUploadLabelService.java·PortalUploadLabelController.java 가 2026-07-25 이후 **무변경이 아니다** — `git log --since=2026-07-25` 에 476bc91a(2026-07-30, 서빙 경로 링크추종 폐쇄)·dcdbb827(2026-08-03, 2차 검증 HIGH 11건 수정)로 2건 히트한다(구 회차 노트가 잘못 기록됨, 08-03 정정). 이번 회차에서 위 두 파일 전체 근거 라인을 재확인해 정정했다(값은 대부분 유지, 라인만 이동).

## F-8. 포털 영상 TUS 업로드 (세션 · 재개 · 완료검증 · 동시성)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-TUS-001 | OPTIONS: TUS 능력 광고(max 5GB) | PORTAL_USER | OPTIONS tus | 204+Tus-Max-Size=5368709120 | integration | Med | PortalTusUploadController.java |
| TC-TUS-002 | POST 세션 생성 → 201+Location | 유효 Upload-Length·확장자 | POST | 201, Location /tus/{uldId} | integration | High | PortalVideoUploadService.java |
| TC-TUS-003 | Upload-Length 누락 → 400 | 헤더 없음 | POST | 400 | unit | Med | PortalTusUploadController.java |
| TC-TUS-004 | Upload-Length 5GB 초과 → 413 | >maxFileSizeBytes | POST | 413 | security | High | PortalVideoUploadService.java |
| TC-TUS-005 | 확장자 allowlist(mp4/mov/avi) 밖 → 400 | .exe | POST | 400 | security | High | PortalVideoUploadService.java |
| TC-TUS-006 | 동시 진행 세션 상한(3) 초과 → 429 | IN_PROGRESS 3건 | POST | 429 | security | Med | PortalVideoUploadService.java |
| TC-TUS-007 | 저장 파일명 UUID 강제(경로순회 차단) | 경로형 메타 | POST | UUID.ext | security | High | PortalVideoUploadService.java |
| TC-TUS-008 | Upload-Metadata 1KB 초과 → 413 | 큰 메타 | POST | 413 | security | Low | PortalTusUploadController.java |
| TC-TUS-009 | Upload-Metadata base64 디코딩 실패 → 400 | 잘못된 base64 | POST | 400 | unit | Low | PortalTusUploadController.java |
| TC-TUS-010 | 지원 안 되는 Tus-Resumable → 412 | 버전 불일치 | 모든 메서드 | 412 | unit | Med | PortalTusUploadController.java |
| TC-TUS-011 | HEAD offset 조회(재개) | 소유 세션 | HEAD | Upload-Offset/Length+no-store | integration | High | PortalTusUploadController.java |
| TC-TUS-012 | **★타 사용자 세션 HEAD/PATCH/DELETE → 404 (정정 2026-08-07 · 구 403 폐기)** | 타인 세션 | 각 메서드 | **404**(IDOR 차단은 그대로이나 응답이 바뀌었다). 구 기대값 **403 은 폐기** — 403 은 "그 세션은 있는데 네 것이 아니다"가 되어 **응답 코드 자체가 세션 존재 오라클**이 된다(CWE-209). 미존재와 **구분 불가능**해야 한다 → TC-TUS-033 | security | High | PortalVideoUploadService.java(`getForOwner` · `cancel`) · PortalVideoUploadTxService.java(`appendChunk` · `sessionNotFound`) |
| TC-TUS-013 | 만료(24h) 세션 HEAD → 410 | expiresAt 경과 | HEAD | 410 | unit | Med | PortalVideoUploadService.java |
| TC-TUS-014 | PATCH 청크 append offset 전진 | 진행중 | PATCH | 204+새 Upload-Offset | integration | High | PortalVideoUploadTxService.java |
| TC-TUS-015 | PATCH Upload-Offset 누락 → 400 | 헤더 없음 | PATCH | 400 | unit | Med | PortalTusUploadController.java |
| TC-TUS-016 | offset 불일치(재개 무결성) → 409 | expected≠서버 | PATCH | 409 | integration | High | PortalVideoUploadTxService.java |
| TC-TUS-017 | offset 범위 밖(음수/length 초과) → 400 | 잘못된 offset | PATCH | 400 | unit | Med | PortalVideoUploadTxService.java |
| TC-TUS-018 | 청크 크기 상한(16MB) 초과 → 413 | >maxChunk | PATCH | 413 | security | Med | PortalVideoUploadTxService.java |
| TC-TUS-019 | 청크 길이가 잔여 용량 초과 → 400 | offset+len>length | PATCH | 400 | unit | Med | PortalVideoUploadTxService.java |
| TC-TUS-020 | 동시 PATCH 낙관적 락 충돌 → 409+truncate 복원 | 병렬 PATCH | 동일 세션 | 409, offset 복원 | integration | High | PortalVideoUploadTxService.java |
| TC-TUS-021 | 취소된 세션 PATCH → 409 | CANCELLED | PATCH | 409 | unit | Med | PortalVideoUploadTxService.java |
| TC-TUS-022 | 완료 세션 마지막 청크 재전송 → 멱등 | COMPLETED | PATCH | 완료 응답(uldSn 재반환) | unit | High | PortalVideoUploadTxService.java |
| TC-TUS-023 | 완료 검증: 매직바이트 불일치 → 거부(CANCELLED)+400 | 위조 컨테이너 | 최종 청크 | 400+파일 삭제+CANCELLED | security | High | PortalVideoUploadService.java |
| TC-TUS-024 | 완료 검증: ffprobe 실패 → 400+CANCELLED | 손상 파일 | 최종 청크 | 400 | unit | Med | PortalVideoUploadService.java |
| TC-TUS-025 | 완료 검증: 비디오 스트림 없음 → 400 | 오디오만 | 최종 청크 | 400 | unit | Med | PortalVideoUploadService.java |
| TC-TUS-026 | 완료 검증은 락/트랜잭션 밖(커넥션 점유 방지) | 대용량 | 최종 청크 | 짧은 tx 후 ffprobe | integration | Med | PortalVideoUploadService.java |
| TC-TUS-027 | 완료 원자 전이(멱등): affectedRows==1만 이벤트 | 동시 완료 | finalizeCompleted | 1회만 이벤트 | integration | High | PortalVideoUploadTxService.java |
| TC-TUS-028 | 검증 중 취소된 세션 완료 시도 → 409+ULD 보상 삭제 | 완료 중 cancel | finalizeCompleted | 409, ULD 삭제 | integration | Med | PortalVideoUploadTxService.java |
| TC-TUS-029 | DELETE 세션 취소+임시파일 삭제 | 진행중 | DELETE | 204+파일 삭제+CANCELLED | integration | Med | PortalVideoUploadService.java |
| TC-TUS-030 | 완료 세션 DELETE → no-op(영구 파일 삭제 금지) | COMPLETED | DELETE | 파일 미삭제 | unit | High | PortalVideoUploadService.java |
| TC-TUS-031 | cancel도 행 잠금(PATCH와 직렬화) | 병렬 PATCH+cancel | DELETE | 락 통일 | integration | Med | PortalVideoUploadService.java |
| TC-TUS-032 | TUS 저장 경로 Path Traversal 차단 | 조작 경로 | 세션 생성 | resolveSafe 거부 | security | High | PortalVideoUploadService.java |
| TC-TUS-033 | **★소유자 불일치와 미존재는 상태코드도 메시지도 동일하다 — 3경로 (신설 2026-08-07 · 핵심 가드)** | ①존재하지 않는 `uldId` ②타인 소유 세션 | `HEAD`·`PATCH`·`DELETE /v1/portal/uploads/tus/{uldId}` 각각 | 두 입력의 응답이 **완전히 동일**(404 + 같은 문구). 응답 생성은 팩토리 **한 곳**(`sessionNotFound`)에서만 한다 — 두 사유를 인라인으로 나누면 다음 수정에서 한쪽 문구만 바뀌어 **코드는 같은데 메시지가 실재를 알려주는** 상태로 되돌아간다. ⚠ **3경로 전부여야 한다** — 한 경로라도 403 을 남기면 공격자가 그 경로로 같은 판별을 할 수 있어 나머지 차단이 무의미해진다(오라클은 가장 느슨한 경로를 따라간다) | security | High | PortalVideoUploadTxService.java(`sessionNotFound`) · PortalVideoUploadServiceTest.java(`HEAD_소유자불일치와_미존재는_상태코드도_메시지도_동일하다` · `DELETE_소유자불일치와_미존재는_상태코드도_메시지도_동일하다` · `PATCH_소유자불일치와_미존재는_상태코드도_메시지도_동일하다`) |
| TC-TUS-034 | 소유자 불일치 거부 시 **부수효과 0** (신설 2026-08-07) | 타인 세션 | `PATCH`(청크 동반) · `DELETE` | PATCH → 파일에 **한 바이트도 기록되지 않음**·offset 미전진 / DELETE → **임시파일 미삭제·세션 미취소**. 거부가 offset·완료 검사·파일 삭제보다 **먼저** 평가된다 | security | High | PortalVideoUploadTxService.java(`appendChunk`) · PortalVideoUploadService.java(`cancel`) · PortalVideoUploadServiceTest.java(`nonOwnerPatchNotFound` · `nonOwnerHeadDeleteNotFound`) |
| TC-TUS-035 | 인증 401 · 역할 403 · 만료 410 · offset 409 는 **불변** (신설 2026-08-07 · 하위호환 회귀 가드) | ①토큰 없음 ②PORTAL_USER 아닌 역할 ③소유자 본인 + 만료 세션 ④소유자 본인 + offset 불일치 | 각 경로 | ①401 ②403 ③410 ④409 — 종전 그대로(TC-TUS-013·016 과 동일). 이번 변경은 **인가를 통과한 호출자의 소유자 판정**만 404 로 바꿨다. 미인증·미권한까지 404 로 뭉개면 인증 실패 원인을 알 수 없어지고, 소유자 본인의 정상 재개 흐름이 404 로 뭉개지면 재개 자체가 깨진다 | security | High | SecurityConfig.java · PortalVideoUploadService.java(`getForOwner`) · PortalVideoUploadTxService.java(`appendChunk`) |

> **⚠ 소유자 불일치 응답은 내부 업로드(`/v1/uploads/{uploadId}`)와 같은 규약**이다 — [B-23](B-batch-deidentify.md) TC-ULD-051~056. 두 경로 중 한쪽만 403 으로 되돌리면 그 경로가 오라클로 남는다.

> F-8 은 PortalTusUploadController.java·PortalVideoUploadService.java 자체는 2026-07-25 이후 무변경(라인 유지). `PortalVideoUploadTxService.java`는 9dfa0d6e(`PortalUploadProperties` record 전환)로 생성자에서 `@Value` import 1줄이 빠지며 이후 라인이 전체 **-1** 이동 — TC-TUS-014~028 라인을 재확인해 반영(값·순서·응답코드는 변경 없음).

## F-9. 포털 영상 프레임 추출 (비동기 · 간격 · 상한 · 실패)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-060 | 영상 완료 후 AFTER_COMMIT → 비동기 추출 | 완료 이벤트 | PortalVideoUploadedEvent | 별도 풀에서 추출 시작 | integration | High | PortalFrameExtractRunner.java |
| TC-PORTALUP-061 | UPLOADED→PROCESSING 원자 전이 실패 → 중단 | 이미 삭제 | beginProcessing | skip | unit | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-062 | 프레임 간격 sysconfig 스냅샷(기본 5초) | config 5 | 추출 | 5초 간격 — **확정(UNCERTAINTIES #5 해소)**: DB 시드값(V109) = 코드 폴백(`DEFAULT_INTERVAL_SEC`) = **5**, 60 아님 | unit | Med | PortalFrameExtractRunner.java, V109__create_ls_portal_uld.sql, ConfigKeys.java |
| TC-PORTALUP-063 | sysconfig 미설정/오류 시 5초 폴백 | config 부재 | 추출 | DEFAULT_INTERVAL_SEC=5 | unit | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-064 | 프레임 간격 config 경계 [1,600] | 검증 | - | 범위 밖 거부 | unit | Low | ConfigKeys.java |
| TC-PORTALUP-065 | maxFrames 2000 초과 → 균등 샘플링 | 긴 영상 | 추출 | ≤2000 균등 | unit | High | PortalFrameExtractRunner.java |
| TC-PORTALUP-066 | maxFrames 경계: 정확히 2000 | - | computeFrameNumbers | 2000 이하 | unit | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-067 | 영상 길이<간격 → 최소 1프레임 보장 | 짧은 영상 | 추출 | 프레임 1개 | unit | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-068 | fps 미상 시 30 폴백 | probe fps 0 | 추출 | DEFAULT_FPS=30 | unit | Low | PortalFrameExtractRunner.java |
| TC-PORTALUP-069 | 추출 성공 후 원자 커밋 READY+메타 | 전체 추출 | completeReady | READY | integration | High | PortalFrameExtractRunner.java |
| TC-PORTALUP-070 | ffmpeg/probe 실패 → 부분 파일 정리+FAILED | 예외 | 추출 | FAILED+파일 정리 | unit | High | PortalFrameExtractRunner.java |
| TC-PORTALUP-071 | 진행 중 자산 삭제 감지 → 중단+정리 | mid-extract 삭제 | touchProcessing | abort+cleanup | integration | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-072 | 프레임 출력 경로 Path Traversal 차단 | 조작 uldSn | resolveSafeFramesDir | root 밖 거부 | security | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-073 | @Async 예외 전파 금지(runner 흡수) | 예외 | runAsync | 로그만 | unit | Low | PortalFrameExtractRunner.java |

> `PortalFrameExtractRunner.java`는 2026-07-25 이후 무변경 — 라인 전부 유지, `properties.maxFrames()` 시그니처도 `PortalUploadProperties` record 전환의 영향을 받지 않음(호출부 불변).

## F-10. 상태 전이 · 정리 스윕 / F-11. 내부 파이프라인 분리

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-080 | LsPortalUld 상태 전이 비즈니스 메서드만 | - | markProcessing/Ready/Failed | @Setter 없이 전이 | unit | Med | LsPortalUld.java |
| TC-PORTALUP-081 | 이미지 업로드는 즉시 READY | 이미지 | uploadImages | markReady(null,null,1) | unit | Med | PortalUploadService.java |
| TC-PORTALUP-082 | 만료 TUS 세션 스윕: 임시파일+행 제거 | EXPRY_DT 경과 | 30분 스윕 | 세션 정리 | integration | Med | PortalUploadSweepJob.java |
| TC-PORTALUP-083 (2026-08-18 정정 — 케이스명 하드코딩 `30m` 제거) | 고착 자산(무갱신 설정 경과) → FAILED+프레임 정리 | `MDFCN_DT` 가 `portal.upload.stuck-timeout-minutes`(설정값, 기본 30분) 이상 무갱신 | 스윕 | FAILED, 원본 보존. ⚠ **구 케이스명의 `30m` 은 기본값을 박아 둔 것**이었다 — 이 값은 `PORTAL_STUCK_TIMEOUT_MINUTES` 로 운영자가 바꿀 수 있는 설정이라 케이스명에 수치를 고정하면 값이 바뀔 때마다 케이스명이 낡는다. 판정 축·기본값·건너뛰기 조건의 상세는 아래 F-12g 참조(TC-PORTALUP-105~108) | integration | Med | PortalUploadSweepJob.java(failStuckUploads) |
| TC-PORTALUP-084 | 스윕 프레임 디렉토리 정리 root 가드 | 경로 검증 | cleanupFrameDir | root 밖 skip | security | Low | PortalUploadSweepJob.java |
| TC-PORTALUP-085 | 스윕 트랜잭션 경계 별 위임(프록시 우회 방지) | - | run | txService 위임 | unit | Low | PortalUploadSweepJob.java |
| TC-PORTALUP-086 (신규) | 만료 TUS 세션 정리는 조건부 DELETE로 2노드 중복 실행 안전 | 2노드 동시 스윕 | claimExpiredSessions | 한 노드만 1행 삭제(파일 정리도 그 노드만), 다른 노드는 0행(멱등, 예외 없음) — Active-Active 배포(CLAUDE.md "배치 성능") 대응 | integration | Med | PortalUploadSweepTxService.java, LsPortalTusUploadRepository.java |
| TC-PORTALUP-087 (신규) | 고착 자산 FAILED 전이도 조건부 UPDATE로 2노드 중복 방지 | 2노드 동시 스윕 | failStuckUploads | 조건부 UPDATE(`failIfInStatus`, 상태 재확인 WHERE)로 한쪽만 1행 전이 | integration | Med | PortalUploadSweepTxService.java |
| TC-PORTAL-090 | 포털 업로드는 LS_PORTAL_* 전용, 내부 미참조 | 라벨 서비스 | - | 포털 3종 리포지토리만 | integration | High | PortalUploadLabelService.java |
| TC-PORTAL-091 | 포털 업로드 영상 비식별 미적용 | 영상 업로드 | 완료 | 비식별 파이프라인 미연결 | integration | High | PortalVideoUploadService.java |
| TC-PORTAL-092 | 포털 사용자 라벨 저장이 LS_DATA_LBL 불변 | user-label 저장 | 실행 | 데이터마트 원본 오염 없음 | integration | High | PortalLabelService.java |
| TC-PORTAL-093 | 포털 프레임 추출 풀 관제 배치와 격리 | 추출 실행 | - | portalExtractExecutor 별도 풀 | integration | Med | PortalFrameExtractRunner.java |
| TC-PORTAL-094 | 포털 자산은 데이터마트 View 미노출 | 포털 자산 존재 | 데이터마트 뷰 | 미포함 | integration | Med | PortalUploadLabelService.java |

## F-12. 데이터마트 ZIP 다운로드 · 보존기간 만료 자동 삭제 (★2026-08-17 신설 — 구 V1.5 "포털 다운로드는 포털 자체 책임" 정책 폐기)

> ★확정(2026-08-17, 사용자 확정): 구 V1.5 "포털 데이터마트 다운로드는 포털 자체 책임 · 다운로드 기간
> 제한 미해소"는 **뒤집혔다**. `GET /v1/portal/datamart/videos/{rawSn}/download`(라벨 JSON + 비식별
> 프레임 이미지 + 비식별 영상 ZIP)와 **보존기간 만료 자동 삭제**(데이터마트 저장 라벨·업로드 자산)가
> 신설됐다 — [UNCERTAINTIES.md #11](UNCERTAINTIES.md) 참조.
>
> ⚠ **화면(SCREEN-028) 다운로드 버튼은 이번 범위 밖이다.** 백엔드 API만 신설됐고, 포털 홈 다운로드
> 버튼 배선은 별도 라운드에서 진행한다. 아래 TC-PORTAL-072/073 및 `LabelingPagePortalRestrictions.test.tsx`
> 계열 FE 회귀 테스트는 여전히 "다운로드 UI 없음"을 단언하며 이번 변경으로 손대지 않는다.
>
> ⚠ **F-7 절의 `TC-PORTALUP-055~058`("원본 다운로드")과는 축이 다르다** — 그건 포털 **업로드 자산**
> (본인 이미지/영상 업로드, `PortalUploadLabelService`)의 export/원본 파일 다운로드이고, 이 절은
> **데이터마트 영상**(관제 인입 → 검수 완료 영상, `PortalDatamartDownloadService`)의 작업 데이터
> 다운로드다. 혼동 금지.

### F-12a. 데이터마트 작업 데이터 ZIP 다운로드 (API-203)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-098 (신규) | 정상 다운로드 — labels.json + 비식별 프레임 이미지 + 비식별 영상 | APPROVED, 본인 저장 라벨 존재, 비식별 영상 존재 | GET /v1/portal/datamart/videos/{rawSn}/download | 200, ZIP({rawSn}/labels.json + {rawSn}/frames/{FRM_NO 4자리 zero-pad}.jpg + {rawSn}/video.{ext}) | integration | High | PortalDatamartDownloadController.java(download), PortalDatamartDownloadTxService.java(plan), PortalDatamartDownloadService.java(download) |
| TC-PORTAL-099 (신규) | 데이터마트 미노출(비APPROVED 또는 미존재) → 403, 신고 상태를 묻지 않는다 | 비APPROVED 또는 미존재 rawSn | 동일 | 403(FORBIDDEN). 판정 순서가 고정(③노출→④신고→⑤라벨0건)이라 미노출 영상의 신고 여부가 응답으로 새지 않는다(CWE-209 오라클 차단) | security | High | PortalDatamartDownloadTxService.java(plan), PortalDatamartDownloadServiceTest.java(notApproved_forbidden_beforeDeidentGate) |
| TC-PORTAL-100 (신규) | 비식별 누락 신고 구간(`DE_IDNTF_YN='F'`) → 412 | APPROVED + 신고 중 | 동일 | 412(PRECONDITION_FAILED), ZIP 미생성. `resolveDeidPath` 호출 이전에 독립 게이트로 평가 — 신고 영상이 "영상 없는 200"으로 새지 않는다 | security | High | PortalDatamartDownloadTxService.java(plan), PortalDatamartDownloadServiceTest.java(underDeidentReport_preconditionFailed) |
| TC-PORTAL-101 (신규) | 본인 저장 라벨 0건 → 410 | APPROVED + 비신고, 본인 저장분 없음(신규 미작업 또는 보존기간 만료 삭제) | 동일 | 410(GONE) | unit | High | PortalDatamartDownloadTxService.java(plan), PortalDatamartDownloadServiceTest.java(noSavedLabel_gone) |
| TC-PORTAL-102 (신규) | per-user 속도 제한 초과 → 429 | 같은 사용자 연속 GET(분당 3회 초과) | 동일 | 429(TOO_MANY_REQUESTS), 자원 판정(403/412/410) 이전에 차단되어 그 비용이 발생하지 않는다. 사용자별 격리(타 사용자 무영향) | security | High | PortalDatamartDownloadController.java(acquireDownloadPermit), PortalDatamartDownloadControllerRateLimitTest.java(과도한_다운로드_요청은_429로_제한되고_자원_판정에_도달하지_않는다 · 제한은_사용자별로_격리된다_다른_사용자는_영향없음) |
| TC-PORTAL-103 (신규) | 원본 폴백 없음 — 비식별 경로 부재 시 원본 영상은 어떤 경우에도 담기지 않는다 | 비식별 경로 null | 동일 | 원본(비식별 이전) 영상은 ZIP에 포함되지 않는다(AC-034 불변 규칙). 서브트리 검증 실패 프레임도 같은 원칙(`resolveFrameImages`)으로 사유 코드만 로그에 남기고 조용히 빠지나, 이 조합의 전용 단위 테스트는 아직 없다 | security | High | PortalDatamartDownloadService.java(resolveDeidVideo, resolveFrameImages), PortalDatamartDownloadServiceTest.java(noDeidVideo_neverFallsBackToOriginal) |
| TC-PORTAL-104 (신규) | 비식별 영상이 없으면 영상 없이 라벨·이미지만 담긴다 | APPROVED, 비식별 영상 이력 없음 | 동일 | 200, ZIP에 video.* 엔트리가 생성되지 않는다(labels.json + frames만) | integration | Med | PortalDatamartDownloadService.java(resolveDeidVideo), PortalDatamartDownloadServiceTest.java(noDeidVideo_zipHasLabelsAndFramesOnly) |
| TC-PORTAL-105 (신규) | 다른 사용자의 저장 라벨은 담기지 않는다(본인만) | 타 사용자 저장분 존재 | 동일 | labels.json 에 본인 저장분만 반영, 타 사용자 저장분은 병합 대상 아님 | security | High | PortalDatamartDownloadTxService.java(plan), PortalDatamartDownloadServiceTest.java(otherUsersLabelsNotIncluded) |
| TC-PORTAL-106 (신규) | 응답은 no-store, 파일명은 서버 생성 고정명 | - | 동일 | `Cache-Control: no-store`(신고 게이트가 매 요청 재평가되어야 함), `Content-Disposition` 파일명에 사용자 입력 미포함(CWE-113 여지 구조적으로 없음) | security | Med | PortalDatamartDownloadService.java(download), PortalDatamartDownloadServiceTest.java(responseHeaders) |

### F-12b. 보존기간 만료 자동 삭제 — 축 A: 데이터마트 저장 라벨

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-107 (신규) | 보존기간(기본 7일) 경과 시 그룹 조건부 DELETE — 2노드 멱등 | (사용자, 영상) 그룹의 `MAX(REG_DT)` 가 커트라인보다 이전 | 스윕 실행 | 해당 그룹 라벨 삭제, 2노드 동시 실행돼도 두 번째 노드는 0행(파일이 없어 클레임 단계 불요) | integration | High | PortalRetentionSweepTxService.java(sweepDatamartLabels), LsPortalUserLabelRepository.java(findExpiredLabelGroups, deleteExpiredLabelGroup), PortalRetentionSweepTxServiceTest.java(만료_그룹마다_조건부_삭제를_호출하고_실제로_지워진_그룹만_센다) |
| TC-PORTAL-108 (신규) | 보존기간 설정(`portal.datamart.retention-days`) 부재 시 미삭제 — 조회조차 하지 않는다 | 설정 행 없음 | 스윕 실행 | 그 회차를 skip(ERROR 로그), 폴백 상수 없이 0건 반환 — 파괴적 배치는 fail-open 하지 않는다 | security | High | PortalRetentionSweepTxService.java(sweepDatamartLabels), PortalRetentionSweepTxServiceTest.java(데이터마트_보존기간_설정이_없으면_라벨을_한_건도_지우지_않고_조회조차_하지_않는다) |
| TC-PORTAL-109 (신규) | `myLabelExpiresAt` 은 저장 컬럼이 아니라 조회 시점 파생값 | 보존기간 7→14일로 변경 후 재조회 | GET /v1/portal/datamart/videos | 만료 예정 시각이 다음 조회부터 즉시 갱신(마이그레이션·백필 없음). 본인 저장분이 없으면 null, 보존기간 설정이 없으면 그 필드만 null(목록 전체는 정상 200) | unit | Med | PortalLabelService.java(listDatamartVideos), PortalDatamartVideosServiceTest.java(포털_데이터마트_목록_보존기간을_7에서_14로_바꾸고_재조회하면_만료예정시각이_갱신된다_AC033 · 포털_데이터마트_목록_보존기간_설정이_없으면_만료예정시각만_null이고_목록은_정상_반환) |

### F-12c. 보존기간 만료 자동 삭제 — 축 B: 업로드 자산 (READY·FAILED 독립 판정)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-088 (신규) | READY 자산 만료 시 파일 먼저 → DB 나중 순서로 삭제 | READY, 보존기간(기본 7일) 경과 | 스윕 실행 | 파일 삭제 성공 후에만 DB 행 삭제. 순서가 뒤집히면 DB 가 먼저 사라져 어느 파일을 지워야 하는지 알 수 없어진다 | integration | High | PortalRetentionSweepJob.java(sweepExpiredUploads), PortalRetentionSweepJobTest.java(파일을_먼저_지운_뒤에_DB행을_지운다) |
| TC-PORTALUP-089 (신규) | FAILED(기본 1일)와 READY(기본 7일)는 서로 다른 보존기간으로 독립 판정된다 | 같은 시각 등록, 한쪽은 FAILED 한쪽은 READY | 스윕 실행 | FAILED 는 1일 경과로 먼저 삭제되고, READY 는 7일이 안 지나 남는다(AC-037) | integration | High | PortalRetentionSweepTxService.java(findExpiredUploads), PortalRetentionSweepIT.java(같은_시각_등록이어도_FAILED는_1일로_삭제되고_READY는_7일이라_남는다) |
| TC-PORTALUP-090 (신규) | PROCESSING·UPLOADED 자산은 삭제 대상이 될 수 없다(만료 없음) | PROCESSING, 등록일로부터 보존기간 경과 | 스윕 실행 | 후보 쿼리 자체가 READY/FAILED 상태 리터럴로 스코프돼 있어 DB 행·파일 모두 그대로 남는다(AC-036) | security | High | PortalRetentionSweepTxService.java(findExpiredUploads), PortalRetentionSweepIT.java(처리중_자산은_등록일로부터_보존기간이_지나도_DB행과_파일이_모두_남는다) |
| TC-PORTALUP-091 (신규) | READY 설정만 부재 시 READY 축만 skip, FAILED 축은 정상 동작(축 독립) | `portal.upload.retention-days` 부재, `portal.upload.failed-retention-days` 존재 | 스윕 실행 | READY 후보 0건(ERROR 로그) + FAILED 후보는 정상 조회·삭제 — 한쪽 설정 부재가 다른 축을 막지 않는다 | security | Med | PortalRetentionSweepTxService.java(findExpiredUploads), PortalRetentionSweepTxServiceTest.java(READY_설정만_없으면_READY축만_건너뛰고_FAILED축은_그대로_동작한다) |
| TC-PORTALUP-092 (신규) | 파일 경로 판정 실패(ESCAPED/UNRESOLVABLE) 시 DB 행 보존 — 다음 회차 재후보 | 저장 경로가 허용 서브트리 밖을 가리킴 | 스윕 실행 | 파일 삭제를 건너뛰고 DB 행도 지우지 않는다(fail-closed — 원본 삭제보다 고아 파일 존치가 안전). 그 자산의 실패가 나머지 자산 처리를 막지 않는다 | security | High | PortalRetentionSweepJob.java(deleteFiles), PortalRetentionSweepJobTest.java(경로_중간_디렉터리가_저장루트_밖을_가리키면_DB행을_지우지_않고_대상파일도_살아있다 · 저장루트_밖_절대경로가_적재돼_있으면_그_자산을_건너뛰고_DB행을_남긴다 · 한_자산의_경로_판정_실패가_다음_자산_처리를_막지_않는다) |
| TC-PORTALUP-093 (신규) | 파일이 이미 없어도(ABSENT) 멱등 성공으로 DB 행을 지운다 | 파일 부재(이미 삭제됨) | 스윕 실행 | ABSENT 는 실패가 아니라 "지울 것이 없다"로 통과 — DB 행 삭제까지 정상 완료(재시도 삭제가 영구 실패하지 않게) | unit | Med | PortalRetentionSweepJob.java(deleteFiles), PortalRetentionSweepJobTest.java(파일이_이미_없어도_정상_처리해_DB행을_지운다) |
| TC-PORTALUP-094 (신규) | 2노드 동시 스윕은 조건부 DELETE 로 한쪽만 삭제(멱등, 오류 없음) | 2노드 동시 실행 | deleteExpiredReady/deleteExpiredFailed | 같은 커트라인으로 재판정하는 조건부 UPDATE/DELETE — 먼저 처리한 노드만 1행, 다른 노드는 0행이고 예외가 나지 않는다 | integration | High | LsPortalUldRepository.java(deleteExpiredReady, deleteExpiredFailed), PortalRetentionSweepIT.java(같은_후보에_배치를_두_번_돌려도_두_번째는_0행이고_오류가_없다) |
| TC-PORTALUP-095 (신규) | `expiresAt` 노출 — READY/FAILED 기준점이 다르다 | READY(등록일·라벨 최종 저장일 중 늦은 쪽), FAILED(FAILED 전이 시각) | GET /portal/uploads, GET /portal/uploads/{uldSn} | 목록·상세 응답에 `expiresAt` 동봉, 두 상태가 같은 판정기(`PortalRetentionPolicy`)를 쓰되 기준점만 다르다. PROCESSING/UPLOADED 는 `expiresAt=null`(고지할 만료 자체가 없음) | unit | Med | PortalUploadService.java(listUploads, getUpload), PortalRetentionPolicyTest.java |
| TC-PORTALUP-096 (신규) | 보존일수 하한(1) — 0/음수 설정 저장은 거부된다 | 설정 변경 요청 | `portal.datamart.retention-days`=0 또는 음수 | 400(입력값 검증 실패), 저장 미수행. 0 은 "오늘 것까지 지운다", 음수는 미래 시각이 커트라인이 되어 전량이 대상이 된다 — 복구 수단이 없는 파괴적 배치라 값 자체를 입구에서 막는다(`NUMBER_RANGE` 하한 1, 상한 3650) | security | High | ConfigKeys.java(PORTAL_DATAMART_RETENTION_DAYS, NUMBER_RANGE), SystemConfigService.java |

### F-12d. 화면 배선 — 다운로드 버튼 · 만료 예정일 표기 (SCREEN-028 · SCREEN-033) — 2026-08-18 신설

> **무엇이 새로 생겼나.** 회차 6 은 서버 경로만 신설하고 «화면 배선은 이번 범위 밖» 으로 유보했다.
> 그 배선이 이번에 붙었다 — 포털 홈 영상 카드의 다운로드 버튼과 두 화면(홈·업로드 목록)의 만료 예정일 표기.
>
> ★ **다운로드는 인증을 요구해 직링크가 성립하지 않는다**(앵커에는 Authorization 헤더가 붙지 않아 401).
> 화면은 `apiClient` 로 응답을 받아 브라우저 다운로드를 트리거한다 — 형제 경로인 포털 업로드 자산
> 다운로드와 **같은 규약**이며 그 헬퍼를 공용 모듈(`lib/api/download.ts`)로 모아 재사용한다(세 번째 복제 방지).
>
> ★ **실패 판정은 상태코드로 한다.** 이 요청은 `responseType: 'blob'` 이라 실패 본문이 표준 응답 형태로
> 해석되지 않고, 그러면 클라이언트가 상태코드에서 코드를 추론하는데 **그 추론 표에 410·429 가 없어**
> 두 사유가 일반 오류로 뭉개진다. 코드 문자열에 기대면 안 되는 이유다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-110 (신규) | 영상 카드마다 작업 데이터 다운로드 버튼이 있다 — 구 V1.5 «미제공» 정책 폐기 | 데이터마트 영상 1건 | 포털 홈 렌더 | 카드마다 «{영상명} 작업 데이터 다운로드» 버튼이 있다. **구 가드가 단언하던 «다운로드 요소 0건» 은 폐기** | unit | High | PortalHomePage.tsx(DatamartVideoCard), PortalHomePage.test.tsx(영상_카드마다_작업_데이터_다운로드_버튼이_있다_V1_5_미제공_정책_폐기) |
| TC-PORTAL-111 (신규) | 만료 예정일은 날짜까지만 적는다 | `myLabelExpiresAt='2026-06-08T10:30:45'` | 포털 홈 렌더 | «만료: 2026-06-08» — 시각은 표기하지 않는다. 시간대 변환도 하지 않는다(서버 값은 오프셋 없는 로컬 일시라 `Date` 파싱 시 하루가 밀린다) | unit | High | expiry.ts(formatExpiryDate), PortalHomeDownload.test.tsx(만료_예정일은_날짜까지만_적는다), expiry.test.ts(시간대_변환을_하지_않는다) |
| TC-PORTAL-112 (신규) | 만료가 없으면 자리를 비운다 — 임의 문구를 지어내지 않는다 | `myLabelExpiresAt=null`(본인 저장 라벨 0건) | 포털 홈 렌더 | 만료 표기가 **빈 상태**. `-`·`없음` 을 쓰면 "만료가 정해졌는데 표기만 빈 것"으로 읽힌다 — 만료가 **없는 것**이지 미정이 아니다 | unit | High | PortalHomePage.tsx(DatamartVideoCard), PortalHomeDownload.test.tsx(만료가_없으면_자리를_비운다_임의_문구를_지어내지_않는다) |
| TC-PORTAL-113 (**반전 — 구 기대결과 폐기 2026-08-18**) | 만료가 없어도 다운로드 버튼을 막지 않는다 — «저장 라벨 없음» 으로 단정하지 않는다 | `myLabelExpiresAt=null` | 버튼 조회 + 클릭 | 버튼이 **활성**이고 `title` 이 없다. 눌러 보면 요청이 그대로 나가고 판정은 서버가 한다. ⚠ **구 기대결과 «`disabled` + `title="저장된 라벨이 없습니다"`» 는 폐기** — 만료가 비는 원인은 **둘**이고(①본인 저장 라벨 없음 ②보존기간 설정 부재·비정상값 → 만료 **판정 불가**) 목록 응답에 저장 라벨 유무 필드가 없어 화면은 둘을 가를 근거가 없다. ②에서는 서버가 정상 응답을 주므로 구 동작은 **정상 다운로드를 화면이 먼저 막고 거짓 사유까지 대는 것**이었다 | unit | High | PortalHomePage.tsx(DatamartVideoCard), PortalRetentionPolicy.java(datamartRetentionDays — "설정 부재·비정상값이면 empty"), PortalHomeDownload.test.tsx(만료가_없어도_다운로드_버튼을_막지_않는다_저장_라벨_없음으로_단정하지_않는다) |
| TC-PORTAL-113b (신규) | 저장 라벨 0건 판정은 **서버가** 하고 그 사유가 그대로 안내된다 | `myLabelExpiresAt=null` + 서버 410 | 다운로드 클릭 | 화면이 미리 단정하는 대신 요청을 보내고, 410 응답의 사유(«내려받을 작업 데이터가 없습니다…»)를 안내한다 — 서버는 이 사유를 이미 다른 상태코드로 **구분해** 돌려준다 | unit | High | PortalDatamartDownloadTxService.java(plan — 본인 저장 라벨 0건이면 GONE), downloadError.ts(datamartDownloadErrorMessage), PortalHomeDownload.test.tsx(저장_라벨_0건_판정은_서버가_하고_그_사유가_그대로_안내된다) |
| TC-PORTAL-114 (신규) | 다운로드 버튼은 카드 클릭 영역 **밖**이고 클릭이 라벨링으로 전파되지 않는다 | 저장 라벨 있는 영상 | 다운로드 버튼 클릭 | 카드(라벨링 진입)의 **자손이 아니고**(구조) 클릭해도 라벨링 화면으로 넘어가지 않는다(동작). 카드 안에 넣으면 버튼 안의 버튼이라 마크업도 성립하지 않는다. ⚠ 동작만 단언하면 중첩으로 되돌려도 통과하므로 **구조도 함께** 단언한다 | unit | High | PortalHomePage.tsx(DatamartVideoCard), PortalHomeDownload.test.tsx(다운로드_버튼은_카드_클릭_영역_밖에_있고_클릭이_라벨링으로_전파되지_않는다 · 카드_본문_클릭은_그대로_라벨링으로_진입한다) |
| TC-PORTAL-115 (신규) | 실패 4종이 **구분되어** 안내되고 서버 원문은 노출되지 않는다 | 429 / 412 / 410 / 403 응답 | 다운로드 클릭 | 요청량 초과 · 비식별 재처리 대기 · 저장 라벨 없음 · 권한 없음 문구가 **서로 다르게** 뜬다(`role="alert"`). 서버 메시지 원문은 노출하지 않는다(CWE-209). 한 문구로 합치면 사용자는 "다시 시도"만 반복하게 된다 | unit | High | downloadError.ts(datamartDownloadErrorMessage), PortalHomeDownload.test.tsx(실패_사유_*_는_구분되어_안내된다 · 네_가지_실패_문구는_서로_다르다) |
| TC-PORTAL-116 (신규) | 만료 임박 강조는 두지 않는다 | 오늘 만료되는 영상 | 포털 홈 렌더 | 날짜만 적고 «임박»·«곧 삭제»·«D-n» 같은 **사양에 없는 강조를 더하지 않는다** | unit | Med | PortalHomePage.tsx(DatamartVideoCard), PortalHomeDownload.test.tsx(만료_임박_강조는_두지_않는다) |
| TC-PORTAL-117 (신규) | 포털 레이아웃은 자식 화면의 다운로드 UI 를 가리지 않되 GNB 에 전역 다운로드 메뉴를 두지 않는다 | 다운로드 버튼을 가진 자식 화면 | 레이아웃 렌더 | 자식의 다운로드 버튼이 그대로 보이고, `header`(GNB) 텍스트에는 «다운로드» 가 없다 — 다운로드는 **영상 1건 단위 행위**라 대상 없이 누르는 전역 메뉴가 성립하지 않는다. **구 가드(문서 전체에 «다운로드» 0건)는 자식 화면의 정상 버튼까지 실패로 만들어 폐기** | unit | Med | PortalLayout.tsx, PortalLayout.test.tsx(자식_화면의_다운로드_UI를_가리지_않되_GNB에_전역_다운로드_메뉴는_두지_않는다_V1_5_미제공_정책_폐기) |
| TC-PORTALUP-097 (신규) | 업로드 목록에 만료 예정일 병기 — 만료 없는 자산은 표기 생략 | READY(만료 있음) + PROCESSING/UPLOADED(만료 없음) 혼재 | 포털 업로드 목록 렌더 | READY 는 «만료: yyyy-MM-dd»(날짜까지만), 처리 중 자산은 표기 자체가 없다. 두 화면(홈·업로드)이 **같은 판정기**(`formatExpiryDate`)를 쓴다 — 복제하면 한쪽만 갱신돼 어긋난다 | unit | High | PortalUploadPage.tsx(UploadItem), expiry.ts(formatExpiryDate), PortalUploadExpiry.test.tsx(자산마다_만료_예정일을_날짜까지만_병기한다 · 처리_중_자산처럼_만료가_없으면_자리를_비운다 · 만료가_있는_자산과_없는_자산이_한_목록에_섞여도_각각_맞게_그린다) |

> **mutation 실증(6건, 전부 원복 확인)**: ①다운로드 버튼을 숨기니 `TC-PORTAL-110` 포함 8건 실패
> ②비활성 조건에서 «저장 라벨 있음» 을 빼니 `TC-PORTAL-113` 실패 ③만료 없음에 `-` 를 넣으니
> `TC-PORTAL-112` 실패 ④업로드 목록에 «없음» 을 넣으니 `TC-PORTALUP-097` 2건 실패 ⑤다운로드 버튼을
> 카드 클릭 영역 안으로 중첩하니 `TC-PORTAL-114` 실패 ⑥실패 판정을 일반 문구로 뭉개니 `TC-PORTAL-115`
> 4건 실패. ⚠ 핸들러의 `stopPropagation` 제거만으로는 **아무 테스트도 실패하지 않는다** — 버튼이 카드의
> 형제라 애초에 전파 경로가 없기 때문이며(그 호출은 감싸는 컨테이너가 생겼을 때를 위한 이중 방어다),
> 그래서 `TC-PORTAL-114` 가 **구조 단언**을 함께 갖는다.
>
> ⚠ **회차 8 정정**: 위 ②(`TC-PORTAL-113`)가 고정하던 동작은 **폐기·반전**됐다. 그 케이스는 이제 반대를
> 단언하며, mutation ② 도 «비활성 조건에 저장 라벨 있음을 **되돌려 넣으니** 실패» 로 방향이 바뀌었다.

### F-12e. 대용량 전송 — 요청 제한시간 · 전송 중단 안내 · 진행 표시 (SCREEN-028 · API-203) — 2026-08-18 신설

> **무엇이 문제였나.** 회차 7 이 붙인 화면 배선은 공용 API 클라이언트의 **기본 제한시간 30초**를 그대로
> 쓰고 있었다. 그런데 서버는 같은 응답을 자기 주석에 *"영상이 포함되면 응답이 GB 급"* 이라 적고 **그래서
> 메모리에 담지 않고 스트리밍으로** 내려보낸다. 브라우저 XHR 의 `timeout` 은 **응답이 끝날 때까지의 총
> 경과 시간**이라 전송 시간이 그대로 잡히므로, 회선이 아무리 좋아도 GB 급은 30초 안에 끝나지 않는다 —
> **구조적으로 끊긴다.** 게다가 끊긴 시점엔 서버가 이미 전량을 읽어 흘려보낸 뒤라, 사용자가 «잠시 후 다시
> 시도» 안내를 따라 재시도할수록 **서버는 같은 GB 급 전송을 반복하고 버린다.**
>
> ★ **제한시간을 무제한(0)으로 두지 않는다.** 무제한이면 연결이 조용히 멈췄을 때 버튼이 '내려받는 중…'
> 에 영구히 갇혀 새로고침 말고는 빠져나올 길이 없다 — **유한한 상한이 그 상태를 끝내 주는 최후 장치**다.
>
> ⚠ **구 근거 폐기(회차 9)**: *"이 화면에는 진행 중인 다운로드를 취소할 수단이 없다(사양에 취소·진행률
> UI 가 없다)"* 는 **더 이상 사실이 아니다** — 취소 조작이 생겼다(아래 F-12f). 그렇다고 **상한을 걷어내지
> 말 것**: 취소는 **사용자가 화면을 보고 있을 때만** 동작하는 수동 장치라, 자리를 비운 사이 멈춘 전송을
> 끝내 주지는 못한다. 두 장치는 서로를 대체하지 않는다.
>
> ★ **실패 원인을 axios 코드로 더 잘게 가르지 않는다.** 공용 클라이언트가 이미 `ApiError` 로 감싸며
> `ECONNABORTED`·`ERR_NETWORK`·`ERR_CANCELED` 를 남기지 않고, 남는 것은 버전마다 달라지는 영문
> 메시지뿐이라 문자열 매칭은 조용히 깨진다. 세 원인 모두 사용자가 할 일이 같아 한 문구로 둔다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-118 (신규) | 다운로드 요청에 **공용 기본값이 아닌 전용 제한시간**이 실린다 | 데이터마트 다운로드 호출 | 나가는 요청 설정 관찰 | `timeout` 이 `DATAMART_DOWNLOAD_TIMEOUT_MS` 이고 `apiClient.defaults.timeout`(30초)과 **다르다**. 기본값이면 GB 급 전송이 구조적으로 끊긴다 | unit | High | api.ts(downloadDatamartVideoData, DATAMART_DOWNLOAD_TIMEOUT_MS), PortalDatamartDownloadService.java(download — "영상이 포함되면 응답이 GB 급"), datamartDownload.test.ts(요청에_공용_기본값이_아닌_전용_제한시간이_실린다) |
| TC-PORTAL-119 (신규) | 제한시간은 **유한**하고 대용량 전송에 쓸 만큼 길다 | 상수 조회 | — | `0`(무제한)이 아니고 유한하며 10분 이상. ⚠ «전용 값을 실었다» 만 보면 30초→31초 변경도 통과하므로 **값의 범위까지** 단언한다. 무제한이면 연결이 조용히 멈췄을 때 '내려받는 중…' 영구 고착이 된다. ⚠ **회차 9 근거 갱신** — 구 기술 «취소 수단이 없는 화면이라» 는 폐기(취소 조작이 생겼다). 그래도 상한은 유지한다: 취소는 사용자가 화면을 보고 있을 때만 동작하는 수동 장치라 대체가 아니다 | unit | High | api.ts(DATAMART_DOWNLOAD_TIMEOUT_MS), datamartDownload.test.ts(제한시간은_유한하고_대용량_전송에_쓸_만큼_길다) |
| TC-PORTAL-120 (신규) | 응답이 오지 않은 실패(제한시간 초과·네트워크 단절)는 서버 4종과 **구분되어** 안내된다 | axios timeout / network error | 다운로드 클릭 | 상태코드가 없어 `status=0` 인 `ApiError` 가 올라오고, 전용 문구(«…전송이 끊겼습니다 … 연결이 안정적인 환경에서…»)가 뜬다. 그 문구는 **«잠시 후» 재시도를 권하지 않는다**(기다려도 달라지지 않고 재시도마다 GB 급 전송을 다시 유발한다). 서버 사유 4종·일반 실패와 **모두 다르다**(6문구 전부 상이). axios 영문 원문은 노출하지 않는다(CWE-209). ⚠ 서버 응답에서 비롯되지 않은 오류(브라우저 다운로드 트리거 실패 등)는 «전송이 끊겼다» 로 오인되지 않고 일반 문구로 간다 | unit | High | downloadError.ts(datamartDownloadErrorMessage, DOWNLOAD_ERROR_INTERRUPTED), datamartDownload.test.ts(제한시간을_넘기면_상태코드_없는_실패로_올라와_전송_중단으로_안내된다 · 네트워크_단절도_같은_경로로_전송_중단으로_안내된다 · 전송_중단_문구는_다른_모든_문구와_다르다_일반_실패와도_같지_않다 · 전송_중단_안내는_잠시_후_재시도를_권하지_않는다 · 서버_응답에서_비롯되지_않은_실패는_전송_중단으로_오인되지_않는다), PortalHomeDownload.test.tsx(전송이_끊긴_실패는_일반_실패로_뭉개지지_않고_따로_안내된다) |
| TC-PORTAL-121 (신규) | 내려받는 동안 진행 중임이 보이고 **보조기술에도** 전달되며, 끝나면 원래대로 돌아온다 | 응답이 끝나지 않은 상태 | 다운로드 클릭 | 버튼 본문이 '내려받는 중…' 이 되고 `aria-busy="true"` + `disabled`. ⚠ 버튼 이름을 `aria-label` 이 정하므로 **바뀐 본문은 보조기술에 읽히지 않는다** — 최대 30분까지 걸릴 수 있는 요청이라 진행 사실만은 전달해야 한다. 응답이 끝나면 `aria-busy` 가 사라지고 다시 활성(영구 고착 없음). 별도 진행률 UI 는 사양에 없으므로 두지 않는다 | unit | Med | PortalHomePage.tsx(DatamartVideoCard), PortalHomeDownload.test.tsx(내려받는_동안_진행_중임이_보이고_보조기술에도_전달된다) |

> **mutation 실증(8건, 전부 원복 확인 — 각 1회 실행)**: ①요청에서 `timeout` 옵션 제거 → `TC-PORTAL-118` 1건 실패
> ②제한시간을 30초로 되돌림 → 118·119 2건 실패 ③제한시간 `0`(무제한) → `TC-PORTAL-119` 1건 실패
> ④`status 0` 분기 제거(일반 실패로 뭉갬) → `TC-PORTAL-120` 3건 실패 ⑤전송중단 문구를 일반 실패 문구와
> 동일하게 → 120 계열 3건 실패 ⑥`ApiError` 아닌 오류의 조기 반환 제거 → 120 계열 1건 실패
> ⑦만료 없음 비활성(구 정책) 되돌림 → `TC-PORTAL-113`·`113b` 2건 실패 ⑧`aria-busy` 제거 →
> `TC-PORTAL-121` 1건 실패.
> ⚠ ⑤는 **구별 가드를 강화한 뒤에야** 잡혔다 — 처음에는 서버 4종만 세는 집합이라 «전송중단 = 일반 실패»
> 를 통과시켰다(일반 실패가 집합에 없었다). 지금은 일반 실패까지 포함해 6문구를 센다.

### F-12f. 대용량 다운로드 취소 — 두 경로 · 「취소는 오류가 아니다」 (SCREEN-028 · SCREEN-034) — 2026-08-18 신설

> **무엇이 문제였나.** ①대용량 전송을 시작하면 **끝날 때까지 멈출 수 없었다** — 회차 8 이 넣은 유한
> 제한시간은 «영구 고착» 만 막아 줄 뿐, 잘못 눌렀거나 지금 필요 없어진 GB 급 전송을 사용자가 끊을 방법이
> 없었다. ②**포털 업로드 원본 파일(최대 5GB)은 여전히 공용 기본값 30초** 를 쓰고 있었다 — 회차 8 이
> 데이터마트 경로에서 고친 것과 **똑같은 결함이 형제 경로에 그대로** 남아 있었다.
>
> ★ **취소는 대용량 두 경로에만 둔다** — 포털 작업 데이터 묶음(GB 급) · 포털 업로드 원본 파일(최대 5GB).
> 라벨 내보내기(JSON)·공지 첨부·통계 리포트에는 두지 않는다. 작아서 **취소 버튼이 뜨기 전에 끝나고**,
> 두면 «작아서 두지 않는다» 는 판단이 코드에서 지워진다.
>
> ★★ **사용자 취소는 오류가 아니라 정상 종료다.** 중단하면 응답이 오지 않아 `status=0` 인 `ApiError` 로
> 올라오는데, 그 자리는 회차 8 이 만든 «전송이 끊겼습니다 … 연결이 안정적인 환경에서 다시» 분기다.
> 갈라 놓지 않으면 **스스로 멈춘 사용자에게 회선을 탓하는 거짓 안내**가 뜨고 «다시» 권유까지 붙는다.
>
> ★ **회차 8 의 «응답 없는 실패» 통합은 뒤집지 않는다.** 중단·네트워크 단절·제한시간 초과를 한 문구로
> 묶은 것은 «사용자가 할 일이 같다» 는 의도된 결정이다. **사용자 취소만** 그 판정보다 **앞에서** 갈라낸다.
> 판정 근거는 **오류 객체가 아니라 화면이 쥔 중단 신호**(`controller.signal.aborted`)다 — 공용 클라이언트가
> `ApiError` 로 감싸며 취소 표식을 남기지 않아 오류만 봐서는 취소와 회선 단절이 구분되지 않고, 공용 오류
> 타입을 넓히면 **이 경로와 무관한 모든 호출부**가 영향을 받는다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-122 (신규) | 취소 신호가 **실제 요청에** 실린다 | 데이터마트 다운로드 호출 | `AbortSignal` 전달 | 나가는 요청 설정의 `signal` 이 넘긴 신호와 동일하고 전용 제한시간도 그대로 실린다. ⚠ 신호를 받아만 두고 요청에 싣지 않으면 '취소' 는 버튼만 있고 GB 급 전송은 계속되는 **거짓 조작**이다. 신호를 넘기지 않는 기존 호출(인자 1개)은 그대로 동작한다(하위호환) | unit | High | api.ts(downloadDatamartVideoData), datamartDownloadCancel.test.ts(취소_신호가_실제_요청에_실린다 · 취소_신호를_넘기지_않아도_그대로_동작한다) |
| TC-PORTAL-123 (신규) | 취소 조작은 **내려받는 동안에만** 보인다 | 데이터마트 영상 카드 | 다운로드 클릭 전/후 | 클릭 전에는 취소 요소가 없다(멈출 것이 없는데 떠 있으면 무엇을 멈추는지 알 수 없다). 진행 중에는 나타나고 **비활성이 아니다**(진행 중 상호 비활성 대상에서 제외 — 취소는 눌러야 동작한다). 접근 이름에 영상명과 «취소» 가 함께 들어간다 | unit | High | PortalHomePage.tsx(DatamartVideoCard), PortalHomeDownloadCancel.test.tsx(내려받기_전에는_취소_조작이_없다 · 내려받는_동안에만_취소_조작이_보인다) |
| TC-PORTAL-124 (신규) | ★**취소하면 오류 안내를 띄우지 않는다**(정상 종료) | 진행 중인 다운로드 | 취소 클릭 → `status=0` 실패로 올라옴 | 오류 영역(`role="alert"`)이 **생기지 않는다**. 특히 전송 중단 문구(«…전송이 끊겼습니다 … 연결이 안정적인 환경에서…»)가 뜨면 안 된다 — 사용자가 스스로 한 일을 장애로 뒤집어씌우고 회선을 의심하게 만든다 | unit | High | PortalHomePage.tsx(onDownload — `controller.signal.aborted` 조기 반환), PortalHomeDownloadCancel.test.tsx(취소하면_오류_안내를_띄우지_않는다_정상_종료다) |
| TC-PORTAL-125 (신규) | 취소 후 **다시 받을 수 있는 상태**로 돌아온다 | 취소 직후 | 버튼 상태 관찰 → 재클릭 | 진행 표시(`aria-busy`)·취소 조작이 걷히고 버튼 본문이 '다운로드' 로 복귀하며 활성이다(영구 고착 없음). 다시 누르면 같은 영상으로 요청이 다시 나간다 | unit | High | PortalHomePage.tsx(onDownload finally), PortalHomeDownloadCancel.test.tsx(취소하면_다시_받을_수_있는_상태로_돌아온다) |
| TC-PORTAL-126 (신규) | 취소 버튼은 **카드 클릭 영역 밖**이고 클릭이 라벨링으로 전파되지 않는다 | 진행 중인 다운로드 | 취소 클릭 | 구조: 카드 클릭 영역(`datamart-video-item`)의 자손이 **아니다**(카드 안에 넣으면 버튼 안의 버튼이 되고 클릭이 위로 전파된다). 동작: 눌러도 라벨링 화면으로 이동하지 않는다 — **멈추려던 사용자가 화면을 떠나면 안 된다**. 핸들러의 `stopPropagation` 은 감싸는 컨테이너가 생겼을 때를 위한 이중 방어 | unit | High | PortalHomePage.tsx(DatamartVideoCard), PortalHomeDownloadCancel.test.tsx(취소_버튼은_카드_클릭_영역_밖에_있고_라벨링으로_전파되지_않는다) |
| TC-PORTAL-127 (신규) | 취소하지 **않은** 전송 중단은 여전히 안내된다(통합을 뒤집지 않는다) | 사용자가 아무것도 누르지 않음 | 회선 단절·제한시간 초과(`status=0`) | 회차 8 의 전송 중단 문구가 그대로 뜬다. ⚠ 취소 분기를 «`status=0` 이면 삼킨다» 로 넓히면 **진짜 장애가 아무 안내 없이 사라진다** — 이 케이스가 그 과잉 확대를 막는다 | unit | High | PortalHomePage.tsx(onDownload catch), PortalHomeDownloadCancel.test.tsx(취소하지_않은_전송_중단은_여전히_안내된다_통합을_뒤집지_않는다) |
| TC-PORTALUP-098 (신규) | 원본 파일 다운로드에 **공용 기본값이 아닌 전용 제한시간**이 실리고, 그 값이 형제 경로보다 짧지 않다 | 업로드 원본 다운로드 호출 | 나가는 요청 설정 관찰 + 상수 조회 | `timeout` 이 `UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS` 이고 `apiClient.defaults.timeout`(30초)과 다르다. 값은 유한하고 `0` 이 아니며 **`DATAMART_DOWNLOAD_TIMEOUT_MS` 이상**이다(이 경로의 상한 5GB 가 데이터마트 묶음이 상정한 약 1.1GB 보다 크므로 더 짧을 수 없다). ⚠ **경로 중 가장 작은 상한이 실제 상한**이라 화면 값만 늘려도 서버의 비동기 스트리밍 절대 제한시간(기본 30초, 데이터를 계속 써도 리셋되지 않음)이 더 짧으면 여전히 잘린다 — 그쪽은 백엔드 담당 축이다 | unit | High | uploads/api.ts(UPLOAD_FILE_DOWNLOAD_TIMEOUT_MS, downloadUploadFile), uploadFileDownload.test.ts(원본_다운로드_요청에_공용_기본값이_아닌_전용_제한시간이_실린다 · 제한시간은_유한하고_5GB_전송에_쓸_만큼_길다) |
| TC-PORTALUP-099 (신규) | 취소 신호가 **실제 요청에** 실린다(원본 파일) | 업로드 원본 다운로드 호출 | `AbortSignal` 전달 | 요청 설정의 `signal` 이 넘긴 신호와 동일하다. 신호를 넘기지 않는 기존 호출(인자 2개)은 그대로 동작한다(하위호환) | unit | High | uploads/api.ts(downloadUploadFile), uploadFileDownload.test.ts(취소_신호가_실제_요청에_실린다 · 취소_신호를_넘기지_않아도_그대로_동작한다) |
| TC-PORTALUP-100 (신규) | 취소 조작은 **원본을 내려받는 동안에만** 보이고, 진행 사실이 보조기술에 전달된다 | 업로드 라벨링 화면(SCREEN-034) | 원본 다운로드 클릭 전/후 | 클릭 전에는 «원본 다운로드 취소» 버튼이 없다. 진행 중에는 나타나고 **비활성이 아니다**. 원본 다운로드 버튼에 `aria-busy="true"` 가 붙는다(최대 5GB 라 오래 걸릴 수 있어 «눌렸는데 아무 일도 없다» 로 보이면 안 된다 — 형제 화면 SCREEN-028 과 같은 관례) | unit | High | PortalUploadLabelingPage.tsx(헤더 다운로드 버튼군), PortalUploadDownloadCancel.test.tsx(내려받기_전에는_취소_조작이_없다 · 원본을_내려받는_동안에만_취소_조작이_보인다) |
| TC-PORTALUP-101 (신규) | ★**취소하면 실패 토스트를 띄우지 않는다**(정상 종료) | 진행 중인 원본 다운로드 | 취소 클릭 → 응답 없는 실패로 올라옴 | `error` 변형 토스트가 **0건**이다. 갈라 놓지 않으면 스스로 멈춘 사용자에게 «원본 다운로드에 실패했습니다» 가 뜬다 | unit | High | PortalUploadLabelingPage.tsx(handleDownloadFile — `controller.signal.aborted` 조기 반환), PortalUploadDownloadCancel.test.tsx(취소하면_실패_토스트를_띄우지_않는다_정상_종료다) |
| TC-PORTALUP-102 (신규) | 취소 후 **다시 받을 수 있는 상태**로 돌아온다 | 취소 직후 | 버튼 상태 관찰 → 재클릭 | 취소 버튼이 사라지고 «원본 다운로드»·«내보내기(JSON)» 두 버튼이 함께 활성으로 복귀한다(상호 비활성 해제). 다시 누르면 요청이 다시 나간다 | unit | High | PortalUploadLabelingPage.tsx(handleDownloadFile finally), PortalUploadDownloadCancel.test.tsx(취소하면_다시_받을_수_있는_상태로_돌아온다) |
| TC-PORTALUP-103 (신규) | 취소하지 **않은** 실패는 여전히 안내된다(통합을 뒤집지 않는다) | 사용자가 아무것도 누르지 않음 | 다운로드 실패 | `error` 토스트가 1건 뜬다 — 삼키면 진짜 장애가 아무 표시 없이 사라진다 | unit | High | PortalUploadLabelingPage.tsx(handleDownloadFile catch), PortalUploadDownloadCancel.test.tsx(취소하지_않은_실패는_여전히_안내된다_통합을_뒤집지_않는다) |
| TC-PORTALUP-104 (신규) | **라벨 내보내기(JSON)는 취소·전용 제한시간 대상이 아니다** | 업로드 라벨링 화면 | 내보내기 클릭(응답 붙잡음) | 진행 중이어도 취소 조작이 생기지 않는다(원본용 취소 버튼도 뜨지 않는다). 요청은 **공용 기본 제한시간을 그대로** 쓴다. ⚠ 이 단언을 «전용 값과 다르다» 로 쓰면 전용 값이 우연히 기본값과 같아지는 순간 조용히 참이 되어 아무것도 지키지 않는다(뮤테이션 중 실제로 그 상태가 나왔다) — 기본값과 **같다**로 단언한다 | unit | Med | uploads/api.ts(downloadUploadExport), PortalUploadLabelingPage.tsx(취소 렌더 조건 `downloading === 'file'`), uploadFileDownload.test.ts(라벨_내보내기_JSON_은_전용_제한시간_대상이_아니다), PortalUploadDownloadCancel.test.tsx(라벨_내보내기_JSON_에는_취소를_두지_않는다) |

> **mutation 실증(10건, 전부 원복 확인 — 각 1회 실행)**: ①포털 홈 취소 분기(`signal.aborted` 조기 반환) 제거
> → `TC-PORTAL-124` 1건 실패(오류 영역이 실제로 떠서 죽었다) ②취소 분기를 «`status=0` 이면 삼킴» 으로 과잉
> 확대 → `TC-PORTAL-127` + 회차 8 의 `TC-PORTAL-120` **2건** 실패 ③데이터마트 요청에서 `signal` 제거 →
> `TC-PORTAL-122` 1건 실패 ④취소 버튼을 항상 렌더(`downloading` 조건 제거) → `TC-PORTAL-123`·`125` 2건 실패
> ⑤취소 버튼을 **카드 클릭 영역 안으로** 이동 + `stopPropagation` 제거 → `TC-PORTAL-126`·`124`·`125` 3건 실패
> ⑥업로드 원본 요청에서 `timeout` 제거 → `TC-PORTALUP-098` 1건 실패 ⑦원본 제한시간을 30초로 되돌림 →
> `TC-PORTALUP-098` 2건 실패 ⑧원본 요청에서 `signal` 제거 → `TC-PORTALUP-099` 1건 실패 ⑨업로드 화면 취소
> 분기 제거 → `TC-PORTALUP-101` 1건 실패 ⑩취소를 내보내기(JSON)에도 노출(`downloading !== null`) →
> `TC-PORTALUP-104` 1건 실패. 추가로 `aria-busy` 제거 → `TC-PORTALUP-100` 1건 실패, 전용 제한시간을
> 내보내기에도 부여 → `TC-PORTALUP-104` 1건 실패.
> ⚠ ⑦은 **처음에 귀속이 틀렸다** — 내보내기 케이스가 «전용 값과 다르다» 로 단언하고 있어, 전용 값이
> 기본값(30초)과 같아지자 **무관한 케이스까지 3건이 죽었다**. 단언을 «기본값과 같다» 로 바꿔 귀속을
> 바로잡았다(위 TC-PORTALUP-104 주의 참조).

> **알려진 한계(인지·수용)**: 취소는 **클라이언트 중단**이다 — 이미 서버가 읽어 흘려보내기 시작한 응답의
> 서버측 처리를 되돌리지는 않는다(형제 축인 라벨링 진행 오버레이의 취소 시맨틱과 동일). 또 화면을 떠나
> 다른 경로로 이동하는 것은 취소가 아니다(SPA 라 요청이 살아 있고 완료되면 브라우저 다운로드가 트리거된다)
> — 이 동작은 «받던 것이 끝까지 받아진다» 는 뜻이라 의도적으로 유지한다.

### F-12g. 방치된 업로드 자산의 실패 전이 — 무갱신 경과 판정 · 폴백 없는 설정 가드 (2026-08-18 신설, 같은 회차 내 정정)

> **무엇이 새로 문서화됐나.** 「업로드됨·처리중」(`UPLOADED`/`PROCESSING`) 자산이 **최종 변경 일시
> (`MDFCN_DT`) 가 갱신되지 않은 채** 설정된 시간(`portal.upload.stuck-timeout-minutes`, 기본 30분)이
> 지나면 `PortalUploadSweepJob(failStuckUploads)` 가 `FAILED` 로 강제 전이시키고, 그러면 위 F-12c 절의
> 실패 보존기간(기본 1일, TC-PORTALUP-089)이 그 자산을 넘겨받아 지운다. **판정 축은 「총 처리 시간」이
> 아니라 「MDFCN_DT 무갱신 경과」다** — 프레임 추출 러너의 하트비트(`touchProcessing`)가 진행 중 계속
> 그 값을 갱신하므로, 정상 진행 중인 추출은 방치로 판정되지 않는다.

> ⚠ **구 서술 폐기(같은 회차 내 검증 정정)** — 이 절이 처음 문서화됐을 때 *"코드는 이미 이 규칙대로
> 동작하고 있었다"* 고 적었으나 **그 시점엔 거짓이었다.** 실측: 하트비트가 **프레임 50장마다** 한 번뿐이고
> **프레임 1장의 추출 비용은 영상 내 위치에 선형 비례**해 그 간격에 **상한이 없었다** — 그래서 정상 추출
> 중인 자산이 방치로 오판되어 FAILED 로 죽고, 하루 뒤(실패 보존기간 경과)에는 원본까지 삭제될 수 있었다.
> 검증 직후 같은 회차 안에서 다음으로 고쳐졌다: ①하트비트를 **프레임 개수가 아니라 경과 시간** 기준으로
> 전환(`PortalFrameExtractRunner` — 마지막 갱신 이후 경과가 간격을 넘으면 친다) ②그 간격을 **상수가
> 아니라 방치 커트라인에서 파생**(1/4, 5초~60초로 clamp — `heartbeatIntervalSec`, 상수로 고정하면
> 운영자가 커트라인만 줄였을 때 하트비트가 그보다 뜸해져 같은 결함이 되살아난다) ③**프레임 추출
> 프로세스 자체에 대기 상한 신설**(`authoring.ffmpeg.frame-timeout-sec`, 기본 600초 — 전에는 프로세스가
> 끝날 때까지 무한 대기했다). 이 셋이 함께 `무갱신 최대 경과 ≤ 하트비트 간격 + 프레임 대기 상한 <
> 방치 커트라인` 을 성립시켜 「장시간 추출이 방치로 판정되지 않는다」를 실제로 보장한다 — 하트비트가
> 「있다」는 사실만으로는 보장되지 않았다. **대가**: 프레임 한 장이 대기 상한을 넘기면 그 추출은
> **실패로 끝난다.** 파괴적 결말이 사라진 게 아니라 조용한 오판(방치 오인 → 원본 삭제)이 진단 가능한
> 실패로 바뀐 것이다 — 사유는 로그에 남고 대기 상한은 설정으로 늘릴 수 있으나, 늘릴 때는 위 부등식이
> 계속 성립하는지 함께 봐야 한다(무작정 늘리면 유계가 깨진다). 프레임 한 장의 비용이 위치에 비례하는
> 성질 자체는 바꾸지 않았다(프레임 번호 의미 변경 위험이 있어 보류) — 총 추출 시간은 여전히 길 수
> 있지만, 이제 그 길이만으로 방치 판정을 받지 않는다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-105 (2026-08-18 정정 — 판정 근거를 시간 기준 하트비트 + 부등식으로 교체) | 판정 축은 총 처리 시간이 아니라 `MDFCN_DT` 무갱신 경과이며, 그 경과가 커트라인을 넘지 않도록 유계가 성립한다 | PROCESSING, 60초 영상(프레임 1장에 5분 소요 — 총 추출은 커트라인의 2배) | 러너 실행(하트비트는 경과 시간 기준으로 침) | 총 경과는 커트라인을 넘어도 **무갱신 최대 경과는 커트라인 미만**이라 방치로 판정되지 않고 정상 완료된다 — `findStuck` 의 `mdfcnDt < cutoff` 조건에 걸리지 않는다. ⚠ **구 기대결과("N 프레임마다 하트비트라 방치 판정되지 않는다")는 폐기** — 그 근거는 프레임 개수 기준이라 프레임 비용이 위치에 비례하는 조건에서 상한이 없었다(→ 위 F-12g 정정 안내) | integration | High | PortalFrameExtractRunner.java(heartbeatIntervalSec), PortalFrameExtractHeartbeatTest.java(프레임_한장이_오래_걸려도_무갱신_경과가_고착_커트라인을_넘지_않는다), LsPortalUldRepository.java(findStuck) |
| TC-PORTALUP-106 (신규) | `stuck-timeout-minutes` 가 0 이면 그 회차의 고착 자산 마감을 건너뛴다 | `stuck-timeout-minutes=0` | `failStuckUploads` 실행 | 전이 0건, 기본값으로 대체하지 않고 WARN 로그만 남긴다 — 0 을 그대로 적용하면 "0분간 갱신 없으면 실패"가 되어 정상 처리 중인 자산 전량이 즉시 대상이 된다(이 전이는 삭제의 예고라 잘못된 설정으로 사용자 데이터를 죽이면 안 된다) | security | High | PortalUploadSweepJob.java(failStuckUploads), PortalUploadSweepJobTest.java(무갱신경과_설정이_0이면_그_회차_전이를_건너뛴다) |
| TC-PORTALUP-107 (신규) | `stuck-timeout-minutes` 가 음수면 마찬가지로 건너뛴다(기본값 폴백 없음) | `stuck-timeout-minutes=-5` | `failStuckUploads` 실행 | 전이 0건. 음수면 커트라인이 미래 시각이 되어 전량이 대상이 되는데, 이 경우도 임의 기본값으로 조용히 메우지 않고 그 회차를 건너뛴다 | security | High | PortalUploadSweepJob.java(failStuckUploads), PortalUploadSweepJobTest.java(무갱신경과_설정이_음수면_기본값으로_대체하지_않고_건너뛴다) |
| TC-PORTALUP-108 (신규·회귀) | 설정이 유효하면 그 값 그대로 전이 판정에 쓰인다 | `stuck-timeout-minutes` 가 양수 | `failStuckUploads` 실행 | 그 값을 커트라인 계산에 그대로 사용 — 위 두 건너뛰기 조건이 정상 값까지 과잉 차단하지 않음을 확인하는 회귀 | unit | Med | PortalUploadSweepJob.java(failStuckUploads), PortalUploadSweepJobTest.java(무갱신경과_설정이_유효하면_그_값_그대로_전이_판정에_쓴다) |
| TC-PORTALUP-109 (신규) | FAILED 전이된 자산은 실패 보존기간 스윕(F-12c)이 이어받는다 | 무갱신 경과로 FAILED 전이 성공 | 다음 `PortalRetentionSweepJob` 회차(실패 보존기간 경과 후) | 그 자산이 F-12c 축(TC-PORTALUP-089, 기본 1일)의 삭제 후보가 된다 — 「PROCESSING·UPLOADED 는 만료 없음」(F-12c 표)이 "영원히 안 지워진다"는 뜻은 아니며 두 스윕이 이어진다 | integration | Med | PortalUploadSweepJob.java(failStuckUploads), PortalRetentionSweepTxService.java(findExpiredUploads — FAILED 축) |
| TC-PORTALUP-112 (신규) | 하트비트 간격은 상수가 아니라 방치 커트라인에서 파생하며 5초~60초로 clamp 된다 | 커트라인 30분/2분/0분/음수 | `heartbeatIntervalSec(stuckTimeoutMinutes)` 호출 | 30분→60초(상한) · 2분→30초(1/4) · 0분·음수→5초(하한, `HEARTBEAT_MIN_INTERVAL_SEC`). 상수로 고정하면 운영자가 커트라인만 줄였을 때 하트비트가 그보다 뜸해져 정상 추출이 방치로 오판되므로 파생 방식이어야 한다 | unit | High | PortalFrameExtractRunner.java(heartbeatIntervalSec), PortalFrameExtractHeartbeatTest.java(하트비트_간격은_고착_커트라인에서_파생돼_커트라인을_줄여도_따라_줄어든다) |
| TC-PORTALUP-113 (신규) | 하트비트 간격 + 프레임 대기 상한의 합이 방치 커트라인보다 작다(유계 고정) | 기본값 조합(커트라인 30분, `authoring.ffmpeg.frame-timeout-sec` 기본 600초) | 정적 계산(`heartbeatIntervalSec + DEFAULT_FRAME_TIMEOUT_SEC`) | 합이 커트라인(30분=1800초) 미만 — 이 부등식이 깨지면 「남은 창」(프레임 1장이 두 값의 합보다 오래 걸리는 경우)에서 다시 방치 오판이 샌다. 대기 상한을 늘릴 때 반드시 함께 확인해야 하는 회귀 | unit | High | PortalFrameExtractRunner.java, BrampFfmpegFrameWriter.java(DEFAULT_FRAME_TIMEOUT_SEC), PortalFrameExtractHeartbeatTest.java(하트비트_간격과_프레임_대기_상한의_합이_고착_커트라인보다_작다) |
| TC-PORTALUP-114 (신규) | 프레임 1장이 대기 상한을 넘기면 무한 대기가 아니라 실패로 끝난다 | `authoring.ffmpeg.frame-timeout-sec` 이내에 ffmpeg 프로세스가 끝나지 않음 | 단일 프레임 추출 호출 | 프로세스를 강제 종료하고 그 프레임 추출을 실패 처리 — 「조용한 방치 오판」이 아니라 「사유가 로그에 남는 진단 가능한 실패」로 귀결된다. 이 값이 0 이하·해석 불가면 기본값(600초)으로 대체하고 WARN(프레임 대기는 삭제를 직접 트리거하지 않는 축이라 방치 커트라인의 「폴백 금지」와는 다른 관례) | unit | Med | BrampFfmpegFrameWriter.java(frameTimeoutSec, awaitExit) |
| TC-PORTALUP-115 (신규) | `stuck-timeout-minutes` 가 숫자가 아니거나 비어 있으면 그 회차를 건너뛰는 게 아니라 **기동 자체가 실패한다** | `portal.upload.stuck-timeout-minutes=abc` 또는 `=""` | 애플리케이션 컨텍스트 기동 | 컨텍스트 기동 실패(`ConfigurationPropertiesBindException`) — `stuckTimeoutMinutes` 는 타입이 있는 설정값이라 바인딩 시점에 걸러진다. ⚠ **TC-PORTALUP-106·107(값이 0/음수)과는 다른 갈래다** — 그 둘은 "기동엔 성공했지만 런타임에 그 회차만 건너뛴다"이고, 이 케이스는 "기동조차 못 한다"이다. 위 F-12g 신설 당시 문서가 이 둘을 "설정이 비었거나 0 이하·해석 불가면 건너뛴다"로 뭉뚱그렸던 것은 부정확했다 | security | High | PortalUploadProperties.java(stuckTimeoutMinutes), PortalStuckTimeoutBindingTest.java(nonNumericValueFailsStartup, emptyValueFailsStartup, zeroAndNegativeBindAndAreHandledByTheJob) |

### F-12h. 정리 스윕과 보존기간 삭제 스윕의 스케줄링 자기 토글 분리 (2026-08-18 신설)

> **무엇이 문제였나.** 두 스윕 잡(`PortalUploadSweepJob`·`PortalRetentionSweepJob`) 모두 자기
> `@EnableScheduling` 이 없어, 관제 통지·작업락 스윕처럼 **포털과 아무 상관 없는 기능**이 켜 둔
> 스케줄링에 얹혀서 돌고 있었다. 그래서 그 무관한 기능을 끄면 만료 TUS 세션 정리·고착 자산 마감·
> 보존기간 삭제가 **소리 없이 멈췄다** — 예외도 로그도 실패하는 테스트도 남지 않는, "켜져 있어야 할
> 것이 안 도는" 성질의 결함이라 재발해도 아무도 모르는 축이었다. 코드는 이미 이 반전(자기 토글로
> 분리)이 반영돼 있었고, 이번 회차는 그 사실을 카탈로그에 처음 반영한다(코드 변경 없음).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-110 (신규) | 업로드 정리 스윕은 자기 전용 토글로 스케줄링되어 무관 기능을 꺼도 계속 동작한다 | `authoring.control-notify.enabled=false` + `authoring.work-lock.sweep.enabled=false`, `portal.upload.sweep.enabled=true` | 앱 기동 | `PortalUploadSweepJob` 이 스케줄에 실제로 등록된다(`ScheduledTaskHolder` 로 확인 — 빈 존재만으로는 부족, `@EnableScheduling` 부재면 `@Scheduled` 자체가 무시된다) | integration | High | PortalUploadSweepSchedulingConfig.java, PortalSweepSchedulingIT.java(관제통지와_작업락_스윕을_모두_꺼도_포털_스윕_두_잡이_스케줄에_등록된다) |
| TC-PORTALUP-111 (신규) | 보존기간 삭제 스윕은 업로드 정리 스윕과 **별개 토글**로 독립적으로 켜고 끌 수 있다 | 동일 조건, `portal.retention.sweep.enabled=true` | 앱 기동 | `PortalRetentionSweepJob` 이 스케줄에 실제로 등록된다. 두 토글이 별개인 것은 의도 — 보존기간 삭제는 사용자 데이터를 비가역으로 지우므로 정리 성격의 형제 잡과 같은 스위치에 묶지 않는다(한쪽을 끄려다 다른 쪽까지 끄는 사고 방지) | integration | High | PortalRetentionSweepSchedulingConfig.java, PortalSweepSchedulingIT.java(관제통지와_작업락_스윕을_모두_꺼도_포털_스윕_두_잡이_스케줄에_등록된다) |

### F-12i. 비동기 스트리밍 응답의 서버측 절대 제한시간 (2026-08-18 신설)

> **무엇이 문제였나.** `spring.mvc.async.request-timeout` 이 **어느 프로파일에도 없으면** 컨테이너
> 기본값 **30초**가 적용된다. 이 값은 "다음 쓰기까지의 공백"이 아니라 **비동기 처리 시작 이후의
> 절대 경과시간**이라 서버가 쉬지 않고 데이터를 쓰고 있어도 리셋되지 않는다(격리 재현: 2초 간격
> 20청크를 연속으로 쓰는 중 t=30.1s 에 강제 종료 — 클라이언트는 16개만 수신). 그래서 F-12e 절이
> 고친 **화면 쪽** 제한시간(`DATAMART_DOWNLOAD_TIMEOUT_MS`)을 아무리 늘려도, 유일한
> `StreamingResponseBody` 경로인 포털 작업 데이터 내려받기는 **서버가 스스로 30초에 끊고 있었다.**
> 공통 `application.yml` 에 `spring.mvc.async.request-timeout: 1800000`(30분 — 화면 쪽 상한과 근거·
> 값이 같다)을 **전 프로파일 공통**으로 명시해 해소했다. 이 절은 F-12e·TC-PORTALUP-098 이 "그쪽은
> 백엔드 담당 축이다"로 미뤄 두었던 서버측 절반을 닫는다. 코드는 이미 반영돼 있었고(`AsyncRequestTimeoutConfigGuardTest`),
> 이번 회차는 그 사실을 카탈로그에 처음 반영한다(코드 변경 없음).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-128 (신규) | 비동기 요청 제한시간이 공통 yml 에 선언되어 전 프로파일에 적용된다(30분) | - | `application.yml` 의 `spring.mvc.async.request-timeout` 조회 | 키가 존재하고 값은 1,800,000(ms, 30분) — 이 키가 없으면 컨테이너 기본값 30초가 적용돼 대용량 내려받기가 전부 잘린다 | unit | High | application.yml, AsyncRequestTimeoutConfigGuardTest.java(asyncRequestTimeoutIsDeclaredInCommonYaml) |
| TC-PORTAL-129 (신규) | 어떤 프로파일도 이 값을 덮어쓰지 않는다 | - | `application-{local,dev,stg,prd}.yml` 각각의 키 목록 조회 | `spring.mvc.async.request-timeout` 키가 4개 프로파일 파일 어디에도 없다 — 한 곳이라도 덮어쓰면 그 환경만 조용히 30초로 되돌아가 그 환경에서만 재현되는 결함이 된다(발견이 가장 늦은 형태) | unit | High | application-local.yml, application-dev.yml, application-stg.yml, application-prd.yml, AsyncRequestTimeoutConfigGuardTest.java(noProfileOverridesAsyncRequestTimeout) |

> **알려진 한계(인지·수용)**: per-user 속도 제한(TC-PORTAL-102)은 형제 제한기(`portalUpload`·`portalUserLabel`)와
> 동일하게 **노드별 in-memory** 라 2노드 Active-Active 배포에서는 실질 한도가 분당 3회의 2배다 — 분산
> 제한기를 새로 도입하지 않는 것은 확정 사항([UNCERTAINTIES.md #12](UNCERTAINTIES.md)의 형제 축과 동일한 성질).
> 보존기간 만료 삭제는 **비가역**이며, 삭제된 라벨·업로드 자산을 되살리는 API·배치는 없다.

> **불확실 항목**: #1 포털 SAM2(문서=미제공, 확정됨 — 07-30 재확인: 여전히 정책 위반, 다만 전송 픽셀은 비식별본으로 교체되고 신고 게이트가 배선됨) · #5 frame-interval **확정: 5초**(07-30 해소, DB 시드=코드 폴백=5) · #11 다운로드 기간 제한 **확정: 해소**(2026-08-17, 위 F-12 절 참조) · #12 데이터마트/이미지 서빙 rate limit 부재(미확정 유지 — user-labels·datamart 목록·`PortalLabelService#serveFrameImage`에는 여전히 rate limiter 없음, 업로드/SAM2/TUS/**다운로드(TC-PORTAL-102)** 에만 있음) → [UNCERTAINTIES.md](UNCERTAINTIES.md)
