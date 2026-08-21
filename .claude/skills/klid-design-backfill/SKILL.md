---
name: klid-design-backfill
description: KLID-저작도구 설계 부채 회수 스킬. klid-dispatch 는 설계를 먼저 반영하므로(Phase 3.6) 정상 흐름에서는 부채가 쌓이지 않는다. 이 스킬은 두 종류의 잔여를 갚는다 — ① 온보딩 이전에 이미 쌓인 누적분(코드는 구현됐는데 LogiCraft ITEM·IMPREC 이 따라오지 못한 것), ② 긴급 장애 등으로 Phase 3.6 을 우회해 코드가 앞선 예외 건. .claude/change-orders/MASTER.md 에서 설계반영 대기(⏳)인 CO 를 모으거나 특정 CO·도메인을 지정받아, CO §6·변경 내용·실제 커밋된 코드를 근거로 LogiCraft ITEM 을 retro-align 하고 IMPREC 추적을 채운다. 실제 ITEM 수정·cascade 는 mc-logi-update 에 위임하고, 이 스킬은 입력 변환 + 게이트 + MASTER 상태(🎨) 갱신만 담당. 사용자가 "설계 반영해줘", "backfill 해줘", "IMPREC 채워줘", "밀린 설계 정합", "/klid-design-backfill" 이라고 하면 실행. AI 추정 금지 — CO·코드에 근거 없는 값은 넣지 않는다.
---

# klid-design-backfill — 설계 부채 회수

`klid-dispatch` 는 **설계를 먼저 확정**(Phase 3.6)하므로 정상 흐름에서 부채가 생기지 않는다. 이 스킬이 갚는 것은 **그 흐름 밖에서 생긴 잔여**다.

| 모드 | 대상 | 언제 |
|---|---|---|
| **A. CO 부채** | `MASTER.md` 의 `설계반영 ⏳` CO | Phase 3.6 을 우회한 예외 건(긴급 장애 등) |
| **B. 누적 부채** | 온보딩 이전에 구현됐으나 ITEM 이 따라오지 못한 것 | 초기 1회성 대청소 |
| **C. IMPREC 갭** | 구현은 됐는데 `implementation_record` 가 비어 있는 ITEM | B 와 함께, 또는 단독 |

> ★ **모드 C 가 이 프로젝트의 현재 최대 부채다.** 2026-08-16 전수 점검 기준 IMPREC `records` 가 **404건 중 7건**만 채워져 있고 그중 6건이 `screen_spec` 이라, `domain_feature`·`use_case`·`erd`·`acceptance`·`nfr`·`domain_event`·`permission_role` 은 전부 0 이다.
> ⚠ 그 원인은 **"기록 단계가 없어서"가 아니라 "동기화를 그동안 안 돌려서"** 다(사용자 확정, 2026-08-17). 따라서 조치는 **프로세스 신설이 아니라 밀린 동기화·백필**이다 — 이 스킬이 바로 그 도구이며, 새 게이트를 만들지 말 것.
> 부수 효과: `records` 가 비어 「구현 시점 버전 ↔ 키트 버전」 대조가 불가능하다. 즉 *"코드가 옛 계약을 구현한 것인지"* 를 아직 아무도 답하지 못했다. 모드 C 를 채우면 그 질문이 답 가능해진다.

## ★ 핵심 원칙

1. **여기서는 코드가 진실원(retro-align)** — 이 시점엔 코드가 이미 구현·커밋·QA 통과 상태다. 설계를 코드에 **맞춘다**.
   ⚠️ 이건 `klid-dispatch` 의 「설계 먼저」와 모순이 아니다 — **모드가 다르다.** 앞으로 바꿀 것은 dispatch Phase 3.6(설계→코드), 이미 어긋난 것을 사후에 메우는 것은 이 스킬(코드→설계)이다. 이 구분은 `.claude/rules/logicraft-integration.md` §0.5 가 정한 것이다.
   ⚠️ "retro-align"은 개념 이름일 뿐 `mc-logi-update` 의 형식 모드·파라미터가 아니다 — 코드 우선 정합은 **edit_intent/edit_context 문구로** 전달한다.
2. **실제 수정은 mc-logi-update 위임** — 이 스킬은 LogiCraft ITEM 을 직접 고치지 않는다. cascade·specialist·검증은 그 스킬이 처리한다.
3. **근거는 CO §6 + 본문 + 실제 코드** — 무엇을 어떻게 고칠지는 CO 파일의 §6·§2~3·§7(커밋)과, 필요하면 그 커밋의 실제 코드에서 확인. **AI 추정 금지**.
4. **MASTER 가 진척 진실원** — 모드 A 의 처리 대상·완료 상태는 `.claude/change-orders/MASTER.md` 의 `설계반영` 열(⏳ 대기 → 🎨 완료)로 추적.
5. **게이트** — 실제 `mc-logi-update` 실행 전에 계획(무엇을 어떻게)을 사용자에게 확인받는다.

## 프로젝트 상수
```yaml
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
project_name: KLID-저작도구
change_orders: ".claude/change-orders/"
```

## 파이프라인

### Phase 0 — 대상 선정
인자로 모드·범위가 지정되면 그것만. 없으면 **모드 A(⏳ 대기 CO 전체)** 가 기본.

**모드 A — CO 부채**
1. `MASTER.md` 에서 `설계반영` 열이 **⏳ 대기**인 CO 행을 모은다(🎨·— 제외).
2. 각 CO 파일에서 재료를 뽑는다: **§6 관련 설계 ITEM**(1차 입력) · **§2 변경 요지 · §3 도메인별 상세**(무엇이 어떻게) · **§7 커밋 해시**(§6 이 애매하면 그 커밋 코드를 실측).

**모드 B — 누적 부채**
1. 대상 도메인을 확정한다(인자 없으면 사용자에게 범위를 묻는다 — 15 도메인 전량은 한 라운드에 무리다).
2. 근거 수집: `grep -ri '@design'`(구현 seam 의 추적 태그) · 로컬 키트 `IMPLEMENTATION.md` · 실제 코드. 코드와 ITEM 이 갈리는 지점을 목록화한다.
3. ⚠ **인계 목록·감사 리포트를 그대로 믿지 마라** — 착수 전에 라이브 ITEM 을 재확인한다. 이 저장소에서 "잔여 7건"이 실측 결과 5건이 이미 해소돼 있던 전례가 있고, 믿고 고쳤으면 **명시된 설계 결정을 뒤집을 뻔했다**.

**모드 C — IMPREC 갭**
1. `mcp__logicraft__get_implementation_coverage` / `list_unimplemented` 로 현재 커버리지를 실측한다.
2. `grep -ri '@design'` 으로 코드가 주장하는 구현 대상을 모은다. **태그는 「주장」이지 「충족 증명」이 아니다** — 그 ITEM 을 실제로 구현했는지는 코드를 열어 확인한다.
3. 대상 ITEM 별로 `mark_implementation` / `create_implementation_record` 입력을 만든다(구현 파일·커밋·버전).

대상 0건이면 "부채 없음" 보고 후 종료.

### Phase 1 — 계획  🚦게이트
대상별로 **무엇을 어떻게 고칠지** 초안을 제시. 형식:
```
CO-NNN (제목) — 설계반영 대기
  · API-NNN: 응답 스키마를 flat → envelope 로 정정
    (근거: CO-NNN §6 + 커밋 abc1234 의 실제 코드. 현 설계 vN 은 코드와 drift)

[모드 C] DOMAIN-005 — IMPREC 미기록 12건
  · DFEAT-021 → review/service/ReviewService.java (근거: @design DFEAT-021 태그 + 커밋 def5678)
```
- **retro-align 명시**: "코드가 이미 이러하므로 설계를 이에 맞춘다"를 근거로.
- **불확실 항목 분리**: 근거 약한 것은 "확인 필요"로 빼서 사용자에게(추정 반영 금지).
- 여러 CO 가 같은 ITEM 을 건드리면 **최신 코드 상태로 한 번에** 정합.

**승인 후** Phase 2.

### Phase 2 — mc-logi-update 위임 (모드 A·B)
**★ 호출 방식**: `Skill(skill="mc-logi-update", args="<입력>")` — 그 스킬이 내부에서 specialist 를 띄우고 **cascade LOOP** 를 돈다.
⚠️ **`logi-update-specialist` 를 Agent 로 직접 띄우지 말 것**(단건 처리라 cascade 안 돎). backfill 은 cascade 가 핵심 → 반드시 오케스트레이터 경유.

입력(자연어 args):
```
프로젝트: KLID-저작도구 (project_id 4ece2c3f-8e99-46f5-9580-71108a76e578)
대상 (ITEM + item_type):
  - API-NNN   (api_endpoint)
  - ERD-NNN   (erd)
  - AC-NNN    (acceptance)
의도(edit_intent): 코드 우선 정합 — 코드가 이미 구현·커밋됨, 설계를 그 코드에 맞춤
edit_context: |
  <CO §2/§3 변경 요지 + §6 근거 + §7 커밋 해시. "코드 먼저 구현됨, 설계를 그 코드에 정합하라" 명시.>
```

★★ **말단(leaf) 누락 방지 — 반드시 위임 프롬프트에 명시** ★★
`mc-logi-update` 는 cascade 시 **말단 항목을 종종 빠뜨린다**(AC·SCREEN·SEQ·CDIAG·CMP 등 leaf 는 비가시). 명시할 것:
- *"cascade 를 **말단까지 완주**하라. 바뀐 상위 ITEM 마다 `analyze_impact` 로 하위 영향을 조회해 **AC·SCREEN·SEQ·CDIAG·CMP 등 leaf 를 빠짐없이 큐에 넣고** 정합하라. leaf 를 '변경 없음'으로 단정 말고 실제 대조 후 판정."*
- CO §6 에 예상 하위 ITEM 이 있으면 그 ID 를 **명시적 cascade 대상으로 함께** 넘긴다.
- `.claude/rules/logicraft-integration.md` 의 쓰기 규율을 함께 싣는다: 배열 원소 삭제 금지(폐기는 `[폐기]` 표기 — 단 `screen_spec`·`erd` 팬텀 컬럼·도해 타입은 예외) · `status` 임의 변경 금지 · **한글을 유니코드 이스케이프로 쓰지 말 것** · 전체 교체 전 기준선 길이 확인 · 쓰기 전 `stale`·`stale_reason` 을 읽어 보고에 기록(쓰기가 그것을 자동 해제한다) · 개수 표기(`N종`) 쓰지 말 것.

회수: 바뀐 ITEM 목록(id·version·요지) 회수. 실패·미처리는 그대로 노출.

### Phase 2-C — IMPREC 기록 (모드 C)
`mark_implementation` / `create_implementation_record` 를 직접 호출해 채운다(이건 ITEM 본문 수정이 아니라 추적 기록이라 `mc-logi-update` 위임 대상이 아니다).
- 기록에 **구현 시점의 ITEM 버전**을 함께 남긴다 — 그래야 다음 감사가 「구현 시점 버전 ↔ 현재 버전」을 대조할 수 있다.
- 근거 없는 ITEM 은 채우지 않는다. "코드가 있으니 아마 이것"은 추정이다.

### Phase 2.5 — 말단 반영 검증 (누락 잡기)
`mc-logi-update` 회수 후, **상위 ITEM 의 하위 leaf 가 실제 정합됐는지 직접 검증**(위임만 믿지 않음):
- 바뀐 상위 ITEM 마다 `analyze_impact`/`get_neighbors`(하위)로 연결된 **AC·SCREEN·SEQ·CDIAG·CMP** 나열 → 각각 이번 라운드에 정합됐는지 확인.
- CO §6 의 예상 하위 ITEM 이 회수 목록에 없으면 = **누락** → 그 ITEM 대상 재위임. 누락 없을 때까지 반복. 남으면 "leaf 미반영 N건"으로 정직 보고(🎨 대신 부분 상태).
- ⚠ **"0건"을 그대로 믿지 마라** — 이 저장소에서 검사기 시야가 좁아 네 번 뚫렸다. 보고할 때 **①어느 층에서 0건인지 ②어느 층을 보지 않았는지**를 함께 적는다.

### Phase 3 — MASTER · CO 상태 갱신
- **MASTER.md**: 성공한 CO 의 `설계반영` 열을 **🎨 (반영 ITEM 요약)**. 부분 반영이면 🎨/⏳ 혼합으로 정직히.
- **CO 파일**: 상단 표 🎨, §6 아래 "반영 완료: <ITEM 목록·version>" 추가.
- **키트 SYNC** — 화면 축 ITEM 을 건드렸으면 라운드 끝에 `/mc-logi-implement-kit` 또는 `/mc-logi-screen-kit` SYNC 를 붙인다(꼬리 작업).
- **보고**: 대상별 반영 ITEM(id·version)·cascade 건수·IMPREC 채운 건수·미처리/확인필요를 표로.

## 게이트 요약
1. Phase 1 — 계획 승인 (실제 설계 수정 전)
그 외는 `mc-logi-update` 정책(batch 자동)을 따름. 근거 약한 항목·breaking 변경은 그때 확인.

## 원칙
- **접착제 역할만** — 직접 LogiCraft ITEM 본문 안 고침(`mc-logi-update` 위임). IMPREC 추적 기록은 예외.
- **retro-align** — 이 스킬 안에서는 코드가 진실원. 단 이건 **사후 정합 모드 한정**이고, 앞으로 바꿀 것은 `klid-dispatch` Phase 3.6 이 담당한다.
- **★ 말단 leaf 빠뜨리지 않기** — cascade 는 leaf 를 자주 누락. 위임 시 "말단까지 완주" 명시(Phase 2), 회수 후 직접 대조(Phase 2.5). leaf 미반영으로 🎨 금지.
- **AI 추정 금지** — CO·코드 근거 없는 ITEM·값 반영 금지. 인계 목록도 라이브 재확인 후 착수.
- **MASTER 를 닫는다** — 처리 후 반드시 MASTER 갱신(leaf 까지 완주해야 🎨, 부분이면 정직).
- **CO 는 안 지운다** — backfill 후에도 CO 파일은 이력으로 보존(상태만 🎨).

## 에러·중단
| 상황 | 대응 |
|---|---|
| ⏳ 대기 CO 0건 (모드 A) | "backfill 대상 없음" 보고 후 종료 — 정상이다(dispatch 가 선반영하므로) |
| CO §6 근거 부족 | 그 항목은 "확인 필요"로 분리 → 사용자. 추정 반영 금지 |
| `mc-logi-update` 실패/보류 | 그대로 노출. 해당 CO 는 ⏳ 유지(부분 성공 정직 표기) |
| 여러 CO 가 같은 ITEM | 최신 코드 기준 한 번에 정합, 관련 CO 함께 근거·함께 🎨 |
| 모드 B 범위가 너무 넓음 | 도메인 단위로 쪼개 라운드 분리. 15 도메인 전량 한 번에 시도하지 않는다 |
| `@design` 태그가 0건인 도메인 | 그 도메인은 모드 C 의 근거가 없다 — 태그부터 심는 것이 선행(구현 시 `klid-d0NN-implementer` 가 심는다) |
