# 재개 문서 — LogiCraft 화면정의서 정합 라운드

> 세션이 압축·종료돼도 이 문서 + `PLAN-통합수정계획.md` + auto-memory 만 읽으면 이어서 진행할 수 있다.
> **작성 시점: 2026-08-07**

---

## 0. 이 라운드가 무엇이었나

**목표**: LogiCraft 화면정의서를 *"이 저장소를 모르는 제3자가 그것만 보고 정확히 구현할 수 있는"* 수준으로 만든다.
**계기**: 화면정의서가 실제 구현과 어긋나 있었고(1차 감사에서 결함 125건), 특히 **입력 검증 3% · 시나리오 연결 0% · 권한 20%** 로 구현 불가 상태였다.

---

## 1. 구속 규칙 (auto-memory 에도 있음 — 반드시 지킬 것)

### P0 — LogiCraft 는 설계만 담는다
판정 한 줄: **"이 문장이 아무나(이 저장소를 모르는 제3자)에게도 참인가?"**
- 예 → LogiCraft · 아니오(지금 작업하는 사람에게만 의미) → **로컬 파일**

| 층 | 담는 것 |
|---|---|
| LogiCraft ITEM 본문 | 동작·상태분기·검증규칙·권한 등 **사양** |
| `change_summary` | 무엇을→무엇으로 + 설계 근거 (리포트명·finding 코드 금지) |
| 로컬 `reports/**` | 작업 맥락·감사 이력·근거 파일명·**구현상태**·미확인 목록 |

**금지(본문)**: 레포명 · 코드 파일명/심볼명 · 커밋 해시 · finding 코드 · 구현상태("미구현"·"코드에서 제거") · 감사 이력.
**`implementation` 필드는 건드리지 않는다.**

### P2 — 기능은 합집합, 구조만 납품 FE 기준
- **기능·동작** = 기존 정의서 ∪ 납품 FE(`klid-label-frontend`) ∪ 테스트베드(`upload-ui/frontend`). 어느 한 곳에만 있어도 포함
- **구조·명칭·동선** = 납품 FE 기준 (같은 기능의 표현이 갈릴 때만)

### P8 — 배열 원소를 삭제하지 않는다
폐기 항목은 `[폐기] ` 접두 + **"두지 않는다"(설계 결정)** 형태로 남긴다. "없어졌다"(구현상태) ❌
⚠️ **컴포넌트 개수도 줄면 안 된다** — 섹션만 세다가 컴포넌트가 줄어 안전 사양이 사라진 사고가 있었다.

### 한글 — 유니코드 이스케이프 금지
`\uXXXX` 로 한글을 쓰지 말고 그대로 입력한다. 이번 라운드에서 `스텝→스템`·`플레이스홀더→플레이스홍더`·`펼치다→파치다` 류 손상이 **여러 배치에서 반복 발생**했다. 깨진 글자가 아니라 **읽히는 다른 한글**이라 육안으로 안 잡힌다.

---

## 2. 완료된 것

| 항목 | 결과 |
|---|---|
| 화면 | 25 → **28** (`SCREEN-008` 재활성 + `SCREEN-036·037` 공지 작성·수정 신규) |
| 컴포넌트 | 451 → **619** (감소 0건) |
| UC / AC | 21/21 → **25/25** (신규 `UC-029~032` · `AC-025~028`) |
| UC 커버리지 | 6/25 → **15/25** |
| 입력 검증 | 2/59 → 관리·증강 화면 전면 명세 |
| `required_roles` | 5/25 → 관리 3화면 보강 (`ROLE-001`) |
| `DS-001` | KRDS 정본 팔레트·폰트로 교체 (v3→v4) |
| 와이어프레임 | stale 11·없음 5 → **28건 결정론적 생성·업로드·전건 대조** |
| 본문 오염 | **0건** (아래 §5 잔여 1건 제외) |
| 한글 손상 | **0건** (음절 83,448 전수) |
| 폐기 API 참조 | `API-033`·`API-077` 정리 |

---

## 3. 도구 (다시 짜지 말 것)

> ## ⚠️ 2026-08-07 확인 — **이 도구와 이 문서는 아직 git 에 없다**
>
> | 대상 | 상태 |
> |---|---|
> | `docs/screen-design/**` (도구·기준선·와이어프레임) | **미추적** — ignore 대상은 아니고 그냥 커밋이 안 됐다 |
> | `reports/**` (이 문서·`PLAN-ui-catalog.md`·`IMPL-STATUS-ledger.md`) | **`.gitignore` 43행이 제외** — 커밋하려면 예외 규칙이 필요하다 |
>
> **여기는 워크트리다**(`orca/workspaces/klid-label/upload-ui`, 메인은 `Documents/workspace/klid/klid-label`).
> 위 파일은 **이 워크트리 안에만 있어 워크트리를 지우면 함께 사라진다.**
>
> 파일이 없어졌다면 다시 짜기 전에 **먼저 메인 워크트리와 다른 워크트리를 확인**할 것.
> 용량은 안전하다 — 1MB 넘는 파일 0건, 전부 텍스트(json·md·html·css·py) 약 1.4MB(staging 캐시 제외).
> 커밋 시 `.staging*` 는 재생성 가능한 다운로드 캐시라 제외한다.
> ⚠ `git add -A docs` 는 쓰지 말 것(대용량 바이너리를 끌어들인 사고 이력) — 경로를 좁혀 추가한다.

```
docs/screen-design/klid-authoring-screens/
├── bin/
│   ├── generate-wireframes.py   # sections → 와이어프레임 HTML (결정론적, LLM 0)
│   ├── verify-items.py          # 검증 4종: loss / pollute / hangul / wf
│   └── README.md                # 생성기 사용법·주의사항
├── baseline/
│   ├── screens.tsv · sections.tsv · components.tsv   # 무손실 대조 기준선(착수 전)
│   ├── before_raw/              # 착수 전 원본 JSON 25건
│   └── krds-canon-tokens.json   # KRDS 정본 토큰(팔레트 9계열·pc 타이포 49·radius 15)
├── wireframes-generated/        # 생성된 와이어프레임 HTML 28건
└── .staging/                    # 최신 ITEM 원본(다운로더 산출)
```

### 자주 쓰는 명령

```bash
KIT=docs/screen-design/klid-authoring-screens
DL=~/.claude/plugins/cache/logicraft/mc-logi-screen-kit/1.2.0/skills/mc-logi-screen-kit/bin/download-kit.mjs
KEY=$(python3 -c "import json;d=json.load(open('$HOME/.claude.json',encoding='utf-8'));print(d['projects']['$HOME/Documents/workspace/klid/klid-label']['mcpServers']['logicraft']['headers']['Authorization'].split()[1])")
PROJ=4ece2c3f-8e99-46f5-9580-71108a76e578
BASE=https://logicraft.cudo.co.kr:10000/api

# 최신 ITEM 내려받기
LOGICRAFT_API_KEY=$KEY LOGICRAFT_API_BASE=$BASE node $DL --project $PROJ --out $KIT/.staging --ids "SCREEN-005,..."

# 검증
python3 $KIT/bin/verify-items.py loss    $KIT/.staging/screen_spec/_raw $KIT/baseline/before_raw
python3 $KIT/bin/verify-items.py pollute $KIT/.staging
python3 $KIT/bin/verify-items.py hangul  $KIT/.staging
python3 $KIT/bin/verify-items.py wf      $KIT/wireframes-generated $KIT/.verify/screen_spec

# 와이어프레임 재생성
python3 $KIT/bin/generate-wireframes.py $KIT/.staging/screen_spec/_raw $KIT/wireframes-generated
```

⚠️ **와이어프레임 업로드는 MCP `upload_static_render` 로만** 된다. REST 엔드포인트는 세션 인증만 받아 API 키로는 **401**. `sections` 파라미터는 보내지 않는다(page 렌더는 ITEM-level 참조).
⚠️ `patch` selector 는 이름에 대괄호(`[폐기]`)가 있으면 깨진다 → **인덱스 selector**(`sections[4].description`) 사용.

---

## 4. ★다음 할 일 (우선순위 순)

> ## ★★ 2026-08-07 갱신 — 4-1·4-2 는 **완료**됐다
>
> 그 라운드의 계약서·결과·미결 판정은 **`PLAN-ui-catalog.md`** 에 있다. **재개 시 그 문서를 먼저 읽을 것.**
>
> | 항목 | 결과 |
> |---|---|
> | 4-1 `ui_component` 전수 정합 | ✅ 97 → **98건**(`FileInput` 신설 `UI-098`) · 다크 2건 해소 · 감소 0 · 오염 0 · status 전건 `draft` |
> | 4-2 `app_shell` 신설 | ✅ **0 → 2건** (`SHELL-001` 내부 채널 22화면 · `SHELL-002` 포털 채널) |
> | 4-3 `docs/test-cases` | ❌ **남아 있음** — 아래 참조 |
> | 부수 | 비-UI 오염 5건 정리 · `DS-001` 팔레트 자기모순 정정 · 한글 손상 16곳 정정 |
>
> **이 라운드에서 확정된 구속 규칙 3가지** (상세 `PLAN-ui-catalog.md` §7):
> 1. **`status` 를 `deprecated` 로 바꾸지 않는다** — 키트 export 에서 영구 소거되고 `--ids` 로도 못 받는다.
>    폐기는 제목 `[폐기] ` 접두 + 본문 "두지 않는다" 로만. (⚠ `screen_spec` 은 기존 관례대로 `deprecated` 사용 — 두 타입의 관례가 다르다. 사용자 확인 대기)
> 2. **화면 사양(SCREEN)이 컴포넌트 사양보다 앞선다** — 구현 코드·코드 주석이 확정 사양보다 낡을 수 있다.
> 3. **책임을 이관하면 수신처를 확인한다** — "A 에 두지 않는다"만 적고 B 를 안 고치면 기능이 증발한다.
>
> **미결 판정 11건**(`PLAN-ui-catalog.md` §8) — 특히 `Q1`·`Q11` 은 두 구현의 컴포넌트 아키텍처가
> **조합형 vs 내장형**으로 갈리는 같은 축이라 통일하려면 별도 라운드 규모다.

### 4-1. `ui_component` 97건 정합 — ✅ 완료 (2026-08-07) · 아래는 착수 시점 기록
가장 시급하다. 화면정의서는 "다크 아님"이라 말하는데 컴포넌트 카탈로그는 다크를 말한다.

| ITEM | 문제 |
|---|---|
| `UI-055` | *"풀스크린 라벨링 화면 **다크** 헤더(56px)"* — 다크 폐지(2026-08-06)됨. **이미 제거된 헤더 저장 버튼**도 기술하고 있을 가능성 |
| `UI-060` | *"검수 화면 상단 **다크** 헤더"* — 동상 |

`tags` 에 `"dark"` 잔존 여부도 확인할 것. 97건 전수 정합은 별도 라운드가 필요하나 **다크 2건은 즉시 처리 대상**이다.
⚠️ `ui_component` 수정 시 **`merge` 모드로만** 써야 기존 `description`/`tags`/`referenced_by_screen_ids` 가 보존된다.

### 4-2. `app_shell` 신설 — ✅ 완료 (2026-08-07)
`SHELL-001`(내부 채널 · 22화면) · `SHELL-002`(포털 채널). 메뉴 항목 구성은 셸에 적지 않고
내비게이션 정의를 따르게 했다 — 두 곳에 같은 목록을 적으면 한쪽만 갱신돼 어긋난다.
등록 시 **서버가 `sidenav.collapsible`·`sidenav.enabled` 기본값 `true` 를 주입**해 본문 서술과
모순됐다. 등록 직후 저장된 `data` 를 반드시 확인하고 정정할 것.

### 4-3. `docs/test-cases` 카탈로그 동기화 — **남아 있음 (다음 재개 지점)**
화면 사양·컴포넌트 사양이 대폭 바뀌어 CLAUDE.md 문서 동기화 규칙상 갱신 대상.
2026-08-07 라운드는 **코드 동작을 바꾸지 않았다**(사양을 코드에 맞춘 것) — 따라서 테스트 기대결과가
바뀐 건이 아니라 **근거·명칭 드리프트**가 주 대상이다. 특히 개명 8건이 카탈로그 이름을 바꿨다:
`MarkingList→MarkingPanel` · `LabelSidebar→LabelPickerPopover` · `AugmentTypeCard→ProcessKindCard` ·
`TimeseriesTextPanel→TimeseriesSidePanel` · `MyTaskCard→MyTasksTable` ·
`ConfidenceDistribution→ConfidenceDistributionChart` · `VideoStatusStepper→BatchStageSteps` ·
`ReviewLabelCanvas`·`ReviewFrameTimeline` 재정의. 폐기 5건: `UI-015`·`UI-059`·`UI-061`·`UI-068`·`UI-075`·`UI-096`.

### 4-4. 그 밖 미검토
- `code_module` 23건 — 존재하나 화면과 **연결 0건**
- `api_endpoint` 162건 — 폐기 링크 2건만 정리, **본문 미검토**
- `permission_role` 3건 — 미검토 (`constant` 2건·`navigation_tree` 2건은 2026-08-07 오염 정리 완료)
- **`NAV-001` stale** — `관리` 그룹에 `라벨 관리`(`SCREEN-035`)가 빠져 있다(범위 밖이라 미수정)
- 포털 화면 4건(`SCREEN-028/029/033/034`) — 사용자 확정으로 범위 밖. 그 탓에 `UC-024`/`UC-027` 화면 연결 확신도 low

---

## 5. 미해결·인지된 잔여

| # | 내용 |
|---|---|
| `TEST-001` | `notes` 의 *"D4 IF번호 미할당"* 1건 — 코드가 아니라 **연동명세서에 IF 번호가 없다는 설계 사실**이라 남겼다. 엄격히 보면 진행 상태이므로 **사용자 판단 대기** |
| `FE-BUG-01` | 납품 FE 증강 결과 **죽은 링크**(`jobId` placeholder 로 이동 → 항상 빈 화면). 사양은 옳고 **코드가 틀린** 상태 → FE 팀 전달. 상세 `PLAN §5.1` |
| 구현 갭 3건 | 관제 통지 카운트 0 고정 · 프레임↔변경종류 매칭 손실 · 연동명세 II-005/006 정정 필요. 상세 `VERIFY §8` |
| `SCREEN-006` v10~v12 | `change_summary` 에 정정 이전 문구 잔존. LogiCraft 는 **과거 리비전 로그를 소급 수정할 수 없다**. 현재 리비전은 정상 |
| 테스트베드 팔레트 | `DS-001` 이 정본으로 바뀌어 `upload-ui/frontend` 가 ITEM 을 따르지 않는 상태(구 `#0F4C97`). 목업 재구성 시 함께 처리 예정 |

---

## 6. 검증에서 배운 것 (다음 라운드에도 적용)

1. **에이전트 자기보고를 게이트로 쓰지 마라.** "감소 없음" 보고가 섹션만 센 것이었고 실제로 안전 사양이 사라졌다.
2. **구조 지표만으로는 문자 치환을 못 잡는다.** 카드·모달 수와 한글 음절 수가 같아도 `추가·삭제할 → 추가/삭제할` 같은 오전사는 통과한다. **정규화 후 문자 단위 비교**가 필요하다.
3. **한글 손상은 반복 발생하고 자체 복구가 완전하지 않다.** 매 라운드 희귀 음절 분석을 붙인다.
4. **"삭제 금지" 지시만으로는 부족하다.** 지시를 받은 에이전트도 폐기 판단이 서면 지웠다. 검증이 짝으로 있어야 성립한다.
5. **지시의 레이어를 틀리지 마라.** 프로세스 문제(정합 기준 흔들림)를 산출물 내용으로 해결하려다 두 번 오염을 만들었다(P10 철회 · P5 오적용).
