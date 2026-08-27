---
name: klid-dispatch
description: KLID-저작도구 전용 수정 오케스트레이터. 이미 구현된 코드를 테스트·수정 반복 단계에서 고칠 때 쓴다. 자유서술 수정요청을 받아 ① 의도 파악 → ② 변경지시서(Change Order) 파일 작성 + 마스터 등록 → ③ 영향 도메인 판정 → ④ 확인 게이트 → ⑤ ★설계 선반영(LogiCraft ITEM 을 먼저 확정) → ⑥ 도메인별 구현 에이전트(klid-d0NN-implementer)·프론트(klid-web-implementer)로 병렬 fan-out(구현+self검증+추적) → ⑦ 독립 QA(klid-qa-verifier — self검증 불신, 실측 재실행+수용기준 재대조+어드버서리얼, fail 시 재구현) → ⑧ 회수·마스터 갱신. 사용자가 "이거 고쳐줘", "이 수정사항 반영해줘", "테스트하다 이거 바꿔야 해", "/klid-dispatch" 등 기존 코드 수정을 요청하면 실행. 변경지시·영향범위는 사용자 확인 후 진행(AI 임의 진행 금지).
---

# klid-dispatch — KLID-저작도구 수정 오케스트레이터

자유서술 수정요청 하나를 받아, **변경지시서(Change Order)를 파일로 남기고 → LogiCraft 설계를 먼저 확정한 뒤 → 영향 도메인 코드를 도메인 전용 에이전트로 구현**하는 프로젝트 전용 디스패처.

## ★ 핵심 원칙

1. **★★설계를 먼저 확정한다 — 코드가 설계를 앞지르지 않는다.**
   `CO 작성 → 영향범위 확인 → 설계 선반영(Phase 3.6) → 구현 fan-out` 순서다.
   이 프로젝트는 **「① ITEM 수정(사양 확정) → ② 그 ITEM 을 진실원으로 코드 반영 → ③ `@design` 태그로 잇기」** 를 구속 규칙으로 두고 있다(`CLAUDE.md` 「작업 위임 선언」 · `.claude/rules/logicraft-integration.md` §0.5). 코드를 먼저 고치고 ITEM 을 나중에 따라오게 하면 **ITEM 이 사양이 아니라 구현 상태 서술로 오염되고**, 다음 사람이 코드에서 설계를 역추정하게 된다.
   > ⚠ 이 순서는 온보딩 템플릿 원형(코드 먼저 → 나중 배치 backfill)을 **이 프로젝트 규칙에 맞춰 개조**한 것이다. 재온보딩(업그레이드 모드) 시 diff 가 뜨는 것은 정상이며, 원형으로 되돌리지 말 것.
2. **CO 파일이 작업 장부** — 수정사항을 `.claude/change-orders/CO-*.md` 에 자세히 기술하고, `.claude/change-orders/MASTER.md` 표로 CO별·도메인별 구현/설계반영 상태를 추적한다.
3. **구현 에이전트는 로컬 키트를 SYNC 하지 않는다.** 메인이 CO 의 해당 도메인 변경 상세(`change_detail`)와 Phase 3.6 에서 확정한 `design_refs`(ITEM ID)를 프롬프트로 직접 내려준다.
4. **공유기반 주의** — 도메인들이 `common/`·`batch/`·`db/migration/`·앱 진입점을 **공유**한다. 변경이 스키마/공유기반을 건드리면 도메인 에이전트가 못 고침 → **메인이 먼저 처리**(Phase 3.5) 후 도메인 fan-out.
5. **오케스트레이션만.** 이 스킬은 직접 코드를 짜지 않는다. 구현은 도메인 에이전트, QA 는 `klid-qa-verifier`, LogiCraft ITEM 수정은 `mc-logi-update` 에 위임한다.

## 프로젝트 상수
```yaml
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
project_name: KLID-저작도구
code_base: "backend/src/main/java/kr/co/cudo/authoring"
change_orders: ".claude/change-orders/"
conventions: ".claude/conventions.md"
```

## 도메인 ↔ code_root ↔ 구현 에이전트 매핑표 (라우팅 진실원)

| 도메인 | 이름 | 백엔드 code_root (`backend/src/main/java/kr/co/cudo/authoring/` 하위) | 구현 에이전트 | 키트 |
|---|---|---|---|---|
| DOMAIN-001 | 사용자·권한 | `auth/` `user/` | `klid-d001-implementer` | `docs/design/사용자권한-DOMAIN-001/` |
| DOMAIN-003 | 영상·프레임 수집 | `video/` `upload/` | `klid-d003-implementer` | `docs/design/영상프레임-수집-DOMAIN-003/` |
| DOMAIN-004 | AI 보조 라벨링 | `label/`(Autolabel·Sam2·Yolo 계열) + `ai-server/` | `klid-d004-implementer` | `docs/design/ai-보조-라벨링-DOMAIN-004/` |
| DOMAIN-005 | 검수 | `review/` `quality/` | `klid-d005-implementer` | `docs/design/검수-DOMAIN-005/` |
| DOMAIN-006 | 통계·대시보드 | `stats/` | `klid-d006-implementer` | `docs/design/통계대시보드-DOMAIN-006/` |
| DOMAIN-007 | 데이터 증강 | `augment/` `dataset/`※ | `klid-d007-implementer` | `docs/design/데이터-증강내보내기-DOMAIN-007/` |
| DOMAIN-009 | 게시판·공지 | `notice/` `notification/` | `klid-d009-implementer` | `docs/design/게시판공지-DOMAIN-009/` |
| DOMAIN-010 | 라벨링 | `label/` `preset/` `version/` `evntanno/` `meta/` `dataset/`※ | `klid-d010-implementer` | `docs/design/라벨링-DOMAIN-010/` |
| DOMAIN-011 | 마킹 | `marking/` | `klid-d011-implementer` | `docs/design/마킹-DOMAIN-011/` |
| DOMAIN-012 | 비식별화 | ※전용 패키지 없음 — `batch/`·`label/`·`video/` 의 Deident 계열 (에이전트 지침의 「코드 레이아웃」이 정본) | `klid-d012-implementer` | `docs/design/비식별화-DOMAIN-012/` |
| DOMAIN-013 | 포털 | `portal/` | `klid-d013-implementer` | `docs/design/포털-DOMAIN-013/` |
| DOMAIN-014 | 시스템 설정 | `sysconfig/` `eventtype/` | `klid-d014-implementer` | `docs/design/시스템-설정-DOMAIN-014/` |
| DOMAIN-015 | 작업 배정 | `assignment/` | `klid-d015-implementer` | `docs/design/작업-배정-DOMAIN-015/` |
| DOMAIN-016 | 관제 통지 | `controlnotify/` `webhook/` | `klid-d016-implementer` | `docs/design/관제-통지-DOMAIN-016/` |
| DOMAIN-017 | 외부 산출물 이관 | (신설 — 코드 미착수) | `klid-d017-implementer` | `docs/design/외부-산출물-이관-DOMAIN-017/` |
| — | 프론트엔드 전 화면 | `frontend/` | `klid-web-implementer` | `docs/screen-design/{도메인}/` (7 도메인) |

> ※ `dataset/` 은 DOMAIN-007(증강 산출)과 DOMAIN-010(라벨링 export)에 **걸친다.** 여기를 건드리는 CO 는 두 도메인 에이전트에 `cross_domain` 을 명시하고, 어느 쪽이 주 담당인지 게이트에서 확정한다.
> - 매핑표에 없는 대상이 나오면 **임의 진행 말고 사용자에게 code_root·에이전트 확인**.
> - 백엔드 변경이 화면까지 미치면 백엔드 도메인 에이전트 + `klid-web-implementer` **둘 다** fan-out. 프론트는 백엔드 응답 계약 소비만 → **백엔드 먼저, 프론트 뒤**.
> - 계약 의존(상류⊃하류)이면 상류 도메인 회수 후 하류(라운드 분리).
> - 공유 기반(`common/`·`batch/`·`db/migration/`·앱 진입점)은 도메인 소속이 아니다 — **메인 직접**(Phase 3.5).

## 파이프라인

### Phase 1 — 의도 파악  🚦게이트①
자유서술 요청을 **무엇을 / 왜 / 어느 범위**로 정리. 버그면 근본원인까지 코드에서 확인(대상 파일 grep/read). 애매하면 되묻는다.
확인: *"요청 이해: [정리]. 변경지시서로 정리하고 설계 반영 → 구현까지 진행할까요?"*

### Phase 2 — 변경지시서(CO) 작성
1. **영향 도메인 판정** — 코드 기반. grep(`grep -ri '@design'` — 기본형 `@design` 과 어노테이션 `@DesignRef` 를 한 번에 잡는다 · API 경로 · `SCREEN-`/`API-`) + 도메인 매핑표 + 도메인 에이전트 특화지식. 한 변경이 여러 도메인·백/프론트에 걸치면 각각 분해.
   - ★ **공유기반 영향 체크**: 변경이 `common/`·`batch/`·`db/migration/`·앱 진입점을 건드리는지 판정. 건드리면 CO §3 에 명시하고 **메인 선처리** 대상으로 표시.
2. **CO 파일 작성** — `.claude/change-orders/_TEMPLATE.md` 골격으로 `.claude/change-orders/CO-{YYYYMMDD}-{slug}.md`. **CO 식별자 = 작성일(YYYYMMDD) + 슬러그**이며 순번을 쓰지 않는다.
   > ⚠ 이 채번은 온보딩 템플릿 원형(순번 — 「MASTER 표 최하단 +1」)을 **이 프로젝트 규칙에 맞춰 개조**한 것이다. 재온보딩(업그레이드 모드) 시 diff 가 뜨는 것은 정상이며, **원형으로 되돌리지 말 것.**
   > 근거: 이 레포는 워크트리를 여럿 띄워 **병렬로** 작업하는데, 순번은 두 브랜치가 같은 `main` 을 보고 같은 값을 뽑아 **구조적으로 충돌한다** — 실제로 개번이 연달아 났고(`CO-009`→`CO-016` · `CO-014`·`CO-015` 이동 · `CO-017` 개번 누락 정정), 손으로 채번하는 Flyway 번호도 같은 원인으로 두 번 밀렸다. 날짜+슬러그는 두 워크트리가 동시에 만들어도 슬러그가 다르면 부딪히지 않고, 같은 날 같은 슬러그면 그건 진짜 중복이라 드러나는 편이 맞다.
   > ⚠ **이미 만들어진 순번 CO 는 개번하지 않는다** — 코드 주석·에이전트 지침·문서가 그 번호를 가리키고 있어 바꾸면 그 포인터가 끊긴다. **다른 워크트리에서 순번으로 진행 중인 CO 도 그대로 끝낸다** — 작업 중인 것을 새 규칙으로 갈아타게 하지 않는다. 새로 시작하는 것부터 새 규칙이며, **두 형식이 한 폴더에 공존하는 것이 정상**이다(글롭 `CO-*.md` 가 둘 다 잡는다).
   > ⚠ 순번으로 진행 중인 작업이 남아 있는 동안에는 **그것들끼리는 여전히 충돌할 수 있다** — 이 규칙은 앞으로 생기는 충돌을 막을 뿐 이미 뽑아 둔 번호를 정리해 주지 않는다. 진행 중인 순번 CO 가 서로 부딪히면 종전대로 개번해 해소한다.
   - ★ **도구 모르는 사람도 이것만 읽고 이해**하도록 자세히. §3 "도메인별 변경 상세"는 각 도메인 에이전트가 받아 구현할 만큼 구체적으로(대상 파일/심볼·변경·불변·주의).
   - **§6 "관련 설계 ITEM" 은 이제 「나중에 반영할 예상 목록」이 아니라 「Phase 3.6 에서 먼저 고칠 대상」이다.** 근거와 함께 최대한 정확히 적는다.
3. **MASTER 등록** — `MASTER.md` 표에 행 추가(구현 📝, 대상 도메인, 생성일, 설계반영 ⏳).

### Phase 3 — 영향범위 + 설계 반영 계획 확인  🚦게이트②
```
변경지시서 작성: CO-{ID} (제목)
공유기반 선처리 필요: (있으면) common/·batch/·db/migration X → 메인이 먼저
설계 선반영 대상: API-NNN(응답 스키마) · ERD-NNN(컬럼) · AC-NNN(수용기준)
영향 백엔드: DOMAIN-00N → klid-d0NN-implementer
영향 프론트: SCREEN-NNN → klid-web-implementer
권장 순서: 설계 선반영 → (공유기반 →) 백엔드 → 프론트
이대로 진행할까요?
```
승인 후 Phase 3.5~. (판정 불명확·미매핑 도메인·breaking 변경·프론트 구현 여부는 그때 되묻는다.)

### Phase 3.5 — 공유기반 선처리 (해당 시, 메인 직접)
CO 가 스키마·`common/`·`batch/`·앱 진입점을 건드리면 **도메인 fan-out 전에 메인이 먼저** 처리한다(Flyway 마이그레이션 작성·적용, common 시그니처 변경 등). 도메인 에이전트는 이 결과를 전제로 구현. 여기서 실패하면 도메인 구현 보류.
- 마이그레이션 신규 작성 시 `conventions.md` 의 **DB 표준용어·표준도메인** 절을 반드시 따른다(CSV 정본 grep 으로 판정).

### Phase 3.6 — ★설계 선반영 (LogiCraft ITEM 먼저 확정)
**구현 fan-out 전에** CO §6 의 대상 ITEM 을 실제로 고쳐 사양을 확정한다. 이 단계가 이 프로젝트 구속 규칙의 「① ITEM 먼저」다.

**★ 호출 방식**: `Skill(skill="mc-logi-update", args="<입력>")` — 그 스킬이 내부에서 specialist 를 띄우고 **cascade LOOP** 를 돈다.
⚠️ **`logi-update-specialist` 를 Agent 로 직접 띄우지 말 것**(단건 처리라 cascade 가 안 돈다).

입력(자연어 args):
```
프로젝트: KLID-저작도구 (project_id 4ece2c3f-8e99-46f5-9580-71108a76e578)
대상 (ITEM + item_type):
  - API-NNN   (api_endpoint)
  - ERD-NNN   (erd)
  - AC-NNN    (acceptance)
의도(edit_intent): 사양 확정 — 아래 변경을 설계에 먼저 반영한다. 구현은 이 ITEM 을 진실원으로 뒤따른다.
edit_context: |
  <CO §2 변경 요지 + §3 도메인별 상세 + 왜 이렇게 바꾸는지의 설계 근거>
  (구현 상태 서술을 넣지 말 것 — ITEM 본문은 사양만 담는다)
```

★★ **말단(leaf) 누락 방지 — 위임 프롬프트에 반드시 명시** ★★
- *"cascade 를 **말단까지 완주**하라. 바뀐 상위 ITEM 마다 `analyze_impact` 로 하위 영향을 조회해 **AC·SCREEN·SEQ·CDIAG·CMP 등 leaf 를 빠짐없이 큐에 넣고** 정합하라. leaf 를 '변경 없음'으로 단정 말고 실제 대조 후 판정."*
- CO §6 에 예상 하위 ITEM 이 있으면 그 ID 를 **명시적 cascade 대상으로 함께** 넘긴다.
- `.claude/rules/logicraft-integration.md` 의 쓰기 규율(배열 원소 삭제 금지 · `status` 변경 금지 · 한글 이스케이프 금지 · 전체 교체 전 기준선 길이 확인 · 쓰기 전 `stale`·`stale_reason` 기록)을 위임 프롬프트에 함께 싣는다.

**회수**: 바뀐 ITEM 목록(id·version)을 받아 CO §6 아래에 **"확정: <ITEM 목록·version>"** 으로 기록하고, MASTER 의 `설계반영` 을 🎨로 전환한다. 이 목록이 Phase 4 의 `design_refs` 가 된다.

> 설계를 못 고치는 경우(근거 부족·breaking·관제 협의 필요)는 **그 항목만 분리**해 사용자에게 올린다. 나머지로 구현을 진행하되, 미확정 항목에 의존하는 구현은 **착수하지 않는다**.

### Phase 4 — 구현 fan-out
영향 대상마다 **한 메시지에서 병렬로** 해당 도메인 에이전트를 띄운다(한 메시지 최대 5개, 초과는 배치 분할). **키트 SYNC 안 함** — CO 의 해당 도메인 상세를 프롬프트로 직접 전달. 백/프론트 계약 의존이면 백엔드 회수 후 프론트(2 라운드).
```yaml
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
domain_id: DOMAIN-0NN              # (프론트는 화면 소속 도메인)
code_root: "<이 도메인 code_root>"   # (프론트는 frontend/)
conventions: ".claude/conventions.md"
change_order: ".claude/change-orders/CO-{ID}-{slug}.md"   # 참조용
design_refs: [API-NNN, ERD-NNN, AC-NNN]   # ★ Phase 3.6 에서 확정된 ITEM — 구현의 계약 근거이자 @design 태그 대상
change_detail: |                    # ★ 구현 진실원
  <CO §3 의 이 도메인 섹션 전문 — 대상 파일·변경·불변·주의·수용기준>
target_hint: | <알면 대상 모듈/클래스/함수/화면. 모르면 생략>
```

### Phase 5 — 회수
각 에이전트 출력 YAML(implemented/verification/tracking/notes_for_main) 취합. red 그대로. notes 의 `needs_core_change` 가 뒤늦게 나오면 Phase 3.5 로 되돌아감.
- ⚠ **구현 중 "설계가 틀렸다"가 판명되면** 코드를 조용히 틀지 말고 **Phase 3.6 으로 되돌아가 ITEM 을 먼저 고친 뒤** 재개한다(구속 규칙의 예외 조항). 그 왕복은 `klid-design-backfill` 이 아니라 이 스킬 안에서 처리한다.

### Phase 5.5 — 독립 QA 검증 (klid-qa-verifier)
회수 직후 대상마다 `klid-qa-verifier` 병렬. 구현이 red 면 QA 생략, 바로 재구현.
```yaml
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
domain_id: DOMAIN-0NN
code_root: "<이 도메인 code_root 또는 frontend/>"
conventions: ".claude/conventions.md"
change_order: ".claude/change-orders/CO-*.md"
design_refs: [API-NNN, AC-NNN]
change_detail: | <해당 도메인 변경 상세 — 수용기준·불변>
implemented: | <구현 에이전트가 보고한 변경 파일·요지>
claimed_verification: | <구현 에이전트가 주장한 결과 — QA 가 실측 대조>
```
- `pass`/`pass_with_notes` → Phase 6.
- `fail` → 해당 구현 에이전트에 issues+fix_hint 담아 재호출(최대 2라운드), 그래도 fail 이면 red 보고.
- `blocked`(실측 불가) → 정직 보고, 런타임 검증 잔여.
> 규모/리스크가 아주 낮으면(오타·문구 1줄) QA 생략 가능하나 **기본은 실행**.

### Phase 5.9 — 노하우 반영 (에이전트 파일 갱신)  🚦게이트③
회수한 각 에이전트 출력의 `notes_for_main.learned` 를 본다. **전부 비어있으면 건너뛴다**(보고에 "노하우 신규 없음").
- 있으면 항목별로 **사용자에게 제시** — 어느 에이전트의 `## 노하우` 에 무엇을 추가할지 + 근거(evidence)·재발조건.
- 동의한 항목만 해당 `klid-d0NN-implementer`(프론트는 `klid-web-implementer`) 파일의 `## 노하우` 섹션에 **append**. 기존 항목 삭제·재작성 금지(축적이지 교체가 아님).
- **QA(Phase 5.5)에서 fail → 재구현으로 드러난 함정도 후보**로 함께 올린다. 같은 지적이 CO 를 넘어 반복되면 노하우 1순위.
- 근거(evidence) 없는 항목은 반영하지 않는다(AI 추정 금지). 동의 못 받은 항목은 버리지 말고 CO §7 구현 로그에 남긴다.
- 도메인 특화 지침(설계 근거)과 혼동 금지 — 여기 쌓는 건 **구현하며 얻은 경험**이다.

### Phase 6 — 마스터 갱신 · 커밋
- **MASTER.md 갱신**: 해당 CO 의 `구현 상태` 도메인별 ✅(QA pass 후) + `커밋`. CO 파일 §7 구현 로그도 갱신. `설계반영` 은 Phase 3.6 에서 이미 🎨.
- **실패·red 그대로 노출.** notes 의 추가 영향 도메인·정보 부족은 다음 액션.
- **커밋은 자동 안 함** — 사용자에게 물음. 커밋금지 파일(`.env` · `.env.local` · `*.local` · `.claude/settings.local.json` · `backend/storage/` · `/storage/` · `cvat/` · `docs/design/backup/`) 제외.
- **위키 동기화 확인** — 동작·정책이 바뀌었으면 `docs/v2-wiki/` 를 **같은 커밋에서** 갱신해야 한다(`CLAUDE.md` 「문서 동기화 규칙」).
- **테스트케이스는 케이스 표가 동결됐다 (2026-08-27 확정)** — `docs/test-cases/` 에 **케이스 행을 새로 추가·정정하지 않고 총계도 재계산하지 않는다.** 정책이 뒤집혔을 때만 해당 클러스터 파일 상단 `## 변경 이력` 표에 **회차 행(서술)** 을 추가한다. 구 지시(*"같은 커밋에서 카탈로그도 갱신"*)는 폐기 — 근거는 `CLAUDE.md` 동명 절.
- **키트 SYNC** — Phase 3.6 이 화면 축 ITEM(`screen_spec`·`use_case`·`acceptance`·`api_endpoint` 등)을 건드렸으면 라운드 끝에 `/mc-logi-implement-kit` 또는 `/mc-logi-screen-kit` SYNC 를 붙인다. 이건 한 번 하고 끝나는 일이 아니라 **라운드마다의 꼬리 작업**이다.

### [별도 배치] 밀린 설계 부채 회수 → `/klid-design-backfill`
이 스킬은 설계를 **먼저** 반영하므로 정상 흐름에서는 부채가 쌓이지 않는다. `klid-design-backfill` 은 두 경우를 담당한다:
① **온보딩 이전에 이미 쌓인 부채** — 코드는 구현됐는데 LogiCraft ITEM·IMPREC 이 따라오지 못한 누적분.
② **Phase 3.6 을 우회한 예외 건** — 긴급 장애 등으로 코드가 앞선 경우(구속 규칙상 그건 예외가 아니라 **빚**이므로 같은 작업의 마무리로 갚는다).

## 게이트 요약
1. Phase 1 — 의도 이해
2. Phase 3 — CO + 영향범위 + 설계 선반영 계획 확인
3. Phase 5.9 — 노하우 반영 확인 (`learned` 가 있을 때만)
그 외 자동. 불명확·미매핑·breaking·core 변경·프론트 구현 여부는 그때 확인.

## 원칙
- **설계 먼저, 코드 나중** — Phase 3.6 을 건너뛰지 않는다. 건너뛰면 그건 빚이고 `klid-design-backfill` 로 갚아야 한다.
- **오케스트레이션만** — 직접 코드/ITEM 안 고침(공유기반 선처리는 예외적으로 메인이, ITEM 수정은 `mc-logi-update` 위임). 구현은 도메인 에이전트, QA 는 `klid-qa-verifier`.
- **CO 가 작업 장부** — 구현 에이전트는 메인이 준 `change_detail` + `design_refs` 대로. 키트 SYNC 안 함.
- **AI 추정 금지** — 도메인 판정·변경 값 임의 확정 안 함. 근거는 grep·매핑표·도메인 에이전트 지식, 애매하면 사용자.
- **정직 회수** — 에이전트/QA 결과 가감 없이. red 숨김 금지.
- **도메인 노하우 축적** — 에이전트가 `notes_for_main.learned` 로 올린 새 함정·패턴을 **Phase 5.9 에서** 해당 에이전트 노하우 섹션에 append(사용자 동의 하에). 에이전트는 자기 파일을 직접 못 고친다 — 반영 책임은 메인에 있다.
