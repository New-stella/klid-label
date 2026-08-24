| 항목 | 값 |
|---|---|
| CO 번호 | CO-006 |
| 제목 | dev 업로드 back-fill 이 비트레이트(`BIT`)를 채운다 — R3 정책의 `BIT` 부분 반전 |
| 대상 도메인 | DOMAIN-003(영상·프레임 수집) — `video/` + `upload/` |
| 구현 상태 | 📝 작성 |
| LogiCraft 설계반영 | ⏳ 대기 |
| 생성일 | 2026-08-24 |

---

## §1 배경

CO-005(머지 `4b35acb8`)가 `LS_DATA_INGEST.BIT` 의 의미를 **색심도 → 비트레이트(bps 정수)** 로 재정의했다. 그 결과 R3 정책의 근거가 `BIT` 에 한해 무너졌다.

**R3 정책 원문**: *"`PXL`(화소)·`BIT`(색심도)는 back-fill 로 채우지 않는다 — 표기 규약이 정의돼 있지 않아 무엇을 넣든 지어낸 값이 되기 때문(프로젝트 '값을 지어내지 않는다' 원칙)."*

- **`PXL` 에는 여전히 유효**하다 — 화소 표기(`4K` 등)의 등급 규약이 정의돼 있지 않다.
- **`BIT` 에는 더 이상 성립하지 않는다** — 비트레이트는 bps 정수라 표기가 모호하지 않고 ffprobe 가 직접 산출한다. 지어내는 값이 아니라 **측정하는 값**이다.

CO-005 회수 시 이 반전을 "기능 영향이 없어 별건"으로 남겼으나(소비 경로의 ffprobe 폴백이 `video.bit_rate` 를 이미 채우므로 산출물은 정상), 사용자가 2026-08-24 **"bit도 채워"** 로 진행을 확정했다.

**지금 무엇이 비어 있나**: dev 업로드로 올린 영상의 `LS_DATA_INGEST.BIT` 컬럼이 **영구 NULL** 이다. 관제 인입분은 관제가 채우지만 dev 업로드분은 채우는 주체가 없다. 산출물(`video.bit_rate`)은 소비 시점 ffprobe 폴백으로 정상이라 **눈에 띄지 않는 결손**이었다.

## §2 변경 요지

dev 업로드(관리 화면 TUS) back-fill 이 ffprobe 로 **비트레이트를 측정해 `BIT` 컬럼에 채운다**. 기존 8컬럼 → **9컬럼**. `PXL` 은 그대로 제외한다.

⚠ 측정 포트에 비트레이트 필드가 **아예 없어** 포트부터 넓혀야 한다(값을 못 구하는 게 아니라 측정을 안 하고 있었다).

## §3 도메인별 변경 상세

### DOMAIN-003 (영상·프레임 수집) — `klid-d003-implementer`

> code_root 가 `video/` + `upload/` 둘 다인 도메인이라 한 에이전트가 전 구간을 맡는다.

#### 3-1. 측정 포트 확장 — `upload/service/UploadMediaProbe.MediaMeta`

- **대상**: `UploadMediaProbe.java` 의 `record MediaMeta(int width, int height, String codecName, Double fps, Long durationMs, Long nbFrames, String displayAspectRatio)`
- **변경**: `Long bitRate` 필드 **추가**(맨 끝). Javadoc 에 다른 필드와 같은 규약을 명시 — *"비트레이트(bps). `format.bit_rate` 우선, 없으면 비디오 스트림 값. **양수만** 유효하며 그 외(0·음수·비유한·파싱불가)는 null."*
- **불변**: 기존 7필드의 이름·타입·순서·시맨틱 무변경(추가만). 이 포트는 "아무것도 거르지 않는다 — 검증은 Resolver 책임"이라는 규약을 그대로 따른다(포트에서 범위 판정하지 말 것, 단 `Double.isFinite` 류 파싱 안전은 구현 쪽 기존 패턴대로).
- ⚠ **`VideoProbe.VideoMeta` 를 건드리지 말 것** — 그 레코드는 13개 호출부가 시그니처에 묶여 있어 이 포트를 따로 둔 것이 애초 설계다(`UploadMediaProbe` Javadoc 이 그 이유를 명시). 두 포트를 합치려 하지 마라.

#### 3-2. ffprobe 구현 — `upload/service/UploadMediaProbeFfprobe`

- **변경**: `bit_rate` 를 추출해 `MediaMeta.bitRate` 로 반출. **`format.bit_rate` 우선, 없으면 비디오 스트림의 `bit_rate`** (기존 `durationMs` 가 `format.duration` 우선인 것과 같은 패턴).
- 파싱 실패·미제공·0 이하는 예외 없이 `null`(기존 필드들과 동일 fail-safe).

#### 3-3. 해석·채택 — `upload/service/InternalUploadMetaResolver`

- **변경**: 측정된 `bitRate` 를 검증해 `ResolvedIngestMeta.bit` 로 넘긴다.
- **검증 규칙**: **양수만** 채택. 문자열로 변환한 결과가 **`BIT VARCHAR(20)` 길이를 넘으면 미채택**(DDL 길이 방어 — 이 Resolver 의 기존 책임). 검증 실패는 예외가 아니라 **그 컬럼만 미채택**(기존 규약).
- **표기**: bps **정수 문자열**(예 `"2050627"`). 단위 접미사(`kbps` 등)를 붙이지 않는다 — 관제가 보내는 값과 같은 표기여야 한 컬럼에 두 표기가 섞이지 않는다. **이것이 이 CO 의 핵심 제약이다.**

#### 3-4. DTO — `video/dto/ResolvedIngestMeta`

- **변경**: `String bit` 필드 추가. `isEmpty()` 에 포함. `@param` Javadoc 추가.
- **★ Javadoc 정정 필수**: 현재 이 레코드의 클래스 Javadoc 에 *"화소(`PXL`)·색심도(`BIT`) 필드가 **없는 것이 곧 R3 의 구현체**다 … 여기에 그 필드를 추가하는 것은 정책 위반"* 이라고 적혀 있다. 이 서술을 **`PXL` 한정으로 좁히고**, `BIT` 은 CO-005 의 의미 재정의로 측정 가능한 값이 됐음을 명시한다. 서술을 남겨두면 다음 사람이 이 변경을 결함으로 되돌린다.

#### 3-5. back-fill SQL — `video/repository/InternalUploadIngestWriter`

- **변경**: `BACKFILL_SQL` SET 절에 `BIT` 추가. **VARCHAR 계열이므로 기존 4종과 동일하게** `COALESCE(NULLIF(BTRIM(BIT, ' ' || CHR(9) || CHR(10) || CHR(13) || CHR(160)), ''), ?)` 형태로 쓴다(공백만 든 값도 "미입력"으로 취급하는 기존 규약 — 이 인자를 빠뜨리면 회귀가 죽는다).
- `bindBackfill` 바인딩 순서를 SET 절과 1:1 로 유지.
- **★ 주석 정정**: 같은 파일의 *"`PXL`·`BIT` 는 여기에 **없다**(R3). 구조 가드가 이 부재를 단언하므로 추가하면 테스트가 죽는다"* 를 `PXL` 한정으로 좁힌다.
- **불변**: `WHERE RCPTN_SN = ? AND PRCS_STTS_CD = 'PENDING' AND RAW_SN IS NULL` 술어와 `COALESCE`(사용자 입력 보존, R4)는 **절대 무변경**.

#### 3-6. 구조 가드 — `architecture/LsDataIngestWriteGuardTest`

- `BACKFILL_FORBIDDEN_COLUMNS`: `List.of("PXL", "BIT", "VRFC_EVNT_TYPE_CD")` → **`BIT` 제거**(`PXL`·`VRFC_EVNT_TYPE_CD` 유지).
- `BACKFILL_ALLOWED_COLUMNS`: 8컬럼 → **`"BIT"` 추가해 9컬럼**.
- **★ allowlist 성격을 유지하라** — 이 가드가 denylist 가 아니라 allowlist 인 이유(관제 소유 컬럼·신뢰 판별자 `RAW_FILE_PATH_NM` 오염 차단, CWE-915)를 Javadoc 이 명시한다. 목록만 갱신하고 **판정 방식을 느슨하게 바꾸지 마라.**
- 관련 Javadoc 의 "8컬럼" 표기를 9로, R3 서술을 `PXL` 한정으로 정정.

#### 3-7. 테스트

- `InternalUploadMetaResolver` 테스트: 정상 bps 채택 · null 미채택 · 0/음수 미채택 · **20자 초과 미채택**.
- `UploadMediaProbeFfprobe` 테스트가 있으면 `format.bit_rate` 우선 / 스트림 폴백 케이스 추가.
- back-fill 통합(`InternalUploadIngestFlowIT`): 측정 비트레이트가 `BIT` 컬럼에 실제로 들어가는지 + **사용자 입력값이 있으면 덮지 않는지**(R4 보존) 확인.
- 기존 "BIT 은 채우지 않는다"를 고정하던 단언이 있으면 **새 동작으로 반전**(`PXL` 단언은 유지).

### 공유기반 — 해당 없음

마이그레이션 불요(`BIT` 컬럼은 이미 존재). 뷰 변경 없음. 온프렘 schema.sql 재생성 불요.

## §4 영향·리스크

- **하위호환**: 기존 NULL 이던 컬럼이 채워지는 것뿐. 읽는 쪽(`VideoMetaService.loadIngestMeta`)은 CO-005 에서 이미 인입 `BIT` 을 파싱해 쓰도록 돼 있어 **추가 배선 없이 곧바로 소비**된다.
- **부수 효과(의도)**: dev 업로드 영상도 인입이 6키를 다 채우게 되어 `needsProbe=false` 가 되고 **소비 시점 ffprobe 가 생략**된다(CO-005 에서 수용한 정책). 즉 측정이 업로드 시점 1회로 앞당겨진다.
- **리스크**: 업로드 시점 ffprobe 가 비트레이트를 못 주는 컨테이너면 `BIT` 이 계속 NULL — 이때는 종전대로 소비 시점 폴백이 채운다(결손 없음).
- **되돌리기**: 코드 되돌리기로 충분(스키마 변경 없음).

## §5 검증

- 위 3-7 테스트 + 영향 클래스 SCOPED.
- **FULL 회귀 1회**(메인) — 가드 반전이 다른 곳을 깨지 않는지.
- ⚠ FULL 은 메모리 압박에 취약하다(CO-005 에서 OOM 실측) — **개발 스택을 내린 상태에서** 돌린다.

## §6 관련 설계 ITEM  ★구현 전에 먼저 확정한다

| ITEM | 타입 | 무엇을 어떻게 | 근거 |
|---|---|---|---|
| — | — | **변경 대상 없음** | 아래 조사 결과 |

**조사 결과(2026-08-24)**: R3 정책("PXL·BIT 미채움")을 서술하는 LogiCraft ITEM 은 **없다**. 그 정책은 로컬 `@req R3` 태그와 코드 주석·구조 가드로만 존재한다(DOMAIN-003 acceptance 는 `AC-055` 하나이며 시계열 건너뛰기 축이라 무관). `ERD-012 v36` 은 이미 `BIT` = 비트레이트로 확정돼 있어 **이번 반전과 설계가 이미 정합**이다 — 오히려 지금 코드가 그 설계를 절반만 따르는 상태(관제 인입은 채워지는데 dev 업로드는 안 채워짐)를 메우는 작업이다.

> ERD-012 는 컬럼 정의(BIT=비트레이트)를 이미 CO-005 v36 에서 확정했다 — **이 CO 로 추가 변경 없음**. 이번 반전은 "누가 그 컬럼을 채우는가"의 배선 정책이라 ERD 축이 아니다. LogiCraft 에 해당 정책 ITEM 이 없으면 설계 반영 대상은 **로컬 문서(위키·테스트케이스)뿐**이다.

**확정: 설계 변경 없음** — ERD-012 v36 이 이미 진실원이며 이 CO 는 그 설계를 코드가 온전히 따르게 하는 배선 작업이다.

## §7 구현 로그

| 일시 | 도메인 | 에이전트 | 결과 | QA | 커밋 |
|---|---|---|---|---|---|
| 2026-08-24 | DOMAIN-003 | `klid-d003-implementer` | 배선 6단(포트·ffprobe·Resolver·DTO·SQL·가드) + 테스트 | SCOPED 185 / 광역 1021, 0 fail | (커밋 대기) |
| 2026-08-24 | — | 메인 직접 | 위키 §5.2·§5.5.2 정정 · test-cases 회차 63 · **FE 폼 라벨 "BIT (색심도)"→"BIT (비트레이트)"** · 요청 DTO 문구 | FE 463파일 3,719건 0 fail + 빌드 통과 | (커밋 대기) |
| 2026-08-24 | — | `klid-qa-verifier` | **pass_with_notes / issues 0** — mutation 5회로 가드 발화 확인, 실 PG16 으로 R4 보존 재현, 바이트 복원 증명 | — | — |
| 2026-08-24 | — | 메인 판정 | QA 지적 1건(위키의 probe 생략 서술이 `filesize` 조건을 빠뜨림) 정정. blind spot 중 **FE 축은 `npm ci` 후 전량 실행으로 해소** | FULL backend 7,422 / 0 fail (9m15s) | — |

**미반영·보류 항목**: `PXL`(화소)은 표기 규약이 여전히 없어 **채우지 않는다** — R3 의 그 부분은 유효하다.
