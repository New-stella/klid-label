import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { BADGE_ONLY_STAGE_CODES, StageBadge } from '../StageBadge';
import { STAGE_LABEL_CODES, stageLabel } from '../BatchStageIndicator';

// ── UI-017 회귀 가드: 장식 아이콘 재유입 방지 ────────────────────────────────
// 2026-08-10 확정으로 단계 배지의 아이콘을 걷어냈다. 색상 톤은 그대로 남아 있으므로,
// 텍스트가 사라지거나 아이콘이 되살아나면 그 배지는 다시 "색 + 글리프"로 상태를 말하게 된다
// (색 단독 구분 금지 · 같은 의미가 두 표현으로 갈리는 드리프트).
// StatusBadge 에는 같은 축의 가드가 있었는데 이 컴포넌트에는 없어 사각이었다.
describe('StageBadge', () => {
  it('★단계_배지는_한글_라벨_텍스트만_렌더하고_아이콘은_폐지됐다', () => {
    const cases: { stage: string; status?: string; label: string }[] = [
      { stage: 'COMPLETED', status: 'COMPLETED', label: '완료' },
      { stage: 'FAILED', status: 'FAILED', label: '실패' },
      { stage: 'FRAME_EXTRACT', status: 'IN_PROGRESS', label: '프레임추출' },
      // ★ 구 기대값 '비식별화' → 폐기. 이 배지가 자기 표를 들고 있어 단계 표(UI-018 '비식별')와
      //   갈려 있던 것을 합쳤다 — 한글명을 규정한 사양은 UI-018 하나뿐이라 그쪽이 정본이다.
      { stage: 'DEIDENTIFY', label: '비식별' },
      // ★ 구 기대값 'VLM' → 폐기. 기술 모델명 노출 금지(UI-017)로 「시계열」로 표시한다.
      { stage: 'VLM', label: '시계열' },
      { stage: 'VLM_VERIFY', label: '시계열' },
    ];

    cases.forEach(({ stage, status, label }) => {
      const { container, unmount } = render(<StageBadge stage={stage} status={status} />);
      const badge = screen.getByText(label);

      // 텍스트 라벨 존재 — 정보 전달의 단독 축
      expect((badge.textContent ?? '').trim()).toBe(label);
      // 장식 아이콘 부재
      expect(container.querySelector('svg')).toBeNull();
      // 색상 톤은 유지 (텍스트 위에 얹힌 보조 축)
      // ★ 구 기대값 purple-100 톤 → 폐기(2026-08-26). 시계열 단계만 범주 구분색(보라)으로
      //   빼내 강조하던 분기를 없애고 다른 단계와 같은 status 규칙에 흡수했다 — 이 배지는
      //   상태 축이라 범주 팔레트를 쓰지 않는다(DS-001). 이제 톤은 semantic 4종뿐이다.
      expect(badge.className).toMatch(/bg-(success|danger|info|warning)\/10/);
      unmount();
    });
  });

  it('매핑에_없는_단계는_원문을_라벨로_보여준다_빈_배지가_되지_않는다', () => {
    const { container } = render(<StageBadge stage="SOME_NEW_BE_STAGE" />);
    const badge = screen.getByText('SOME_NEW_BE_STAGE');
    expect(badge).toBeInTheDocument();
    // 아이콘이 없는 지금은 텍스트가 유일한 정보 전달 축이라 비면 안 된다.
    expect((badge.textContent ?? '').trim().length).toBeGreaterThan(0);
    expect(container.querySelector('svg')).toBeNull();
  });

  it('기술_모델명은_노출하지_않는다', () => {
    const { container, unmount } = render(<StageBadge stage="YOLO" />);
    expect(screen.getByText('AI 탐지')).toBeInTheDocument();
    expect(container.textContent).not.toContain('YOLO');
    unmount();

    const sam2 = render(<StageBadge stage="SAM2" />);
    expect(screen.getByText('AI 분할')).toBeInTheDocument();
    expect(sam2.container.textContent).not.toContain('SAM2');
    sam2.unmount();

    // ★ VLM 도 같은 축이다 — 코드는 유지하되 화면에는 「시계열」만 나간다.
    const vlm = render(<StageBadge stage="VLM" />);
    expect(screen.getByText('시계열')).toBeInTheDocument();
    expect(vlm.container.textContent).not.toContain('VLM');
    vlm.unmount();

    const verify = render(<StageBadge stage="VLM_VERIFY" />);
    expect(screen.getByText('시계열')).toBeInTheDocument();
    expect(verify.container.textContent).not.toContain('VLM');
  });

  // ── 회귀 가드: 단계 표시명의 정의처는 하나다 ─────────────────────────────────
  // ★ 이 배지가 자기 `STAGE_LABEL` 표를 들고 있었고, 그 표가 단계 표(BatchStageIndicator)와
  //   실제로 갈려 있었다 — `DEIDENTIFY` 가 '비식별화'(배지) / '비식별'(스테퍼)로 한 앱에서
  //   두 이름이었다. 표를 하나로 합쳤으므로, 다시 갈라지면 여기서 실패해야 한다.
  //
  // ⚠ 코드 목록을 손으로 나열하지 않고 `STAGE_LABEL_CODES` 로 **전수** 돈다 — 나열하면 표에
  //   단계가 추가돼도 가드가 따라 늘지 않아 새 단계가 사각으로 남는다.
  it('★공유_단계_코드는_배지와_스테퍼가_같은_라벨을_돌려준다_표_분기_금지', () => {
    expect(STAGE_LABEL_CODES.length).toBeGreaterThan(0);

    STAGE_LABEL_CODES.forEach((code) => {
      const { unmount } = render(<StageBadge stage={code} />);
      const expected = stageLabel(code);
      // 배지가 그리는 실제 텍스트로 비교한다(순수 함수 동치만 보면 렌더 경로가 갈려도 통과한다).
      expect(screen.getByText(expected)).toBeInTheDocument();
      // 라벨이 원문 코드로 새지 않았는지 — 폴백으로 떨어지면 표가 갈라진 것이다.
      expect(expected).not.toBe(code);
      unmount();
    });
  });

  // ★★ 위 가드는 **값이 갈릴 때만** 문다 — 공유 표와 **같은 문자열**로 재복제하면 렌더 결과가
  //    같아 통과해 버리고, 표는 다시 둘이 된 채로 남는다(그리고 나중에 한쪽만 갱신되는 순간
  //    `DEIDENTIFY` 가 '비식별'/'비식별화' 로 갈렸던 그 상태로 돌아간다). 이 저장소는 이미
  //    같은 실패 모드를 알고 있다 — `BatchStageIndicator.test.tsx` 의 리터럴 카운트 가드가
  //    *"런타임 동치만으로는 못 잡는다: 두 곳에 같은 문자열을 적어도 통과하기 때문"* 이라고 적었다.
  //
  // ⚠ 그 리터럴 카운트 방식을 '시계열'·'비식별' 로 확장하는 길은 택하지 않았다:
  //    ① `BatchStageIndicator.tsx` 주석이 "시계열" 을 겹따옴표로 쓰고 그 정규식은 주석을
  //       구분하지 못해 즉시 오탐이 난다 ② '비식별' 은 `DeidentHistoryPanel`·`FrameGrid12` 등
  //       단계 라벨과 무관한 정당한 용례가 여럿이라 "소스에 한 번만" 규칙이 성립하지 않는다.
  //
  //    대신 **불변식을 직접 단언**한다 — 배지 전용 표는 공유 표가 이름을 규정하는 단계 코드를
  //    재정의하지 않는다. 값을 보지 않고 **키의 존재**를 보므로 같은 값 재복제도 잡는다.
  it('★배지_전용_표는_공유_단계_코드를_재정의하지_않는다_같은_값_재복제도_금지', () => {
    // 두 집합이 비어 있으면 교집합이 공허하게 공집합이라 가드가 무력해진다 — 먼저 못 박는다.
    expect(BADGE_ONLY_STAGE_CODES.length).toBeGreaterThan(0);
    expect(STAGE_LABEL_CODES.length).toBeGreaterThan(0);

    const shared = new Set(STAGE_LABEL_CODES);
    const redefined = BADGE_ONLY_STAGE_CODES.filter((code) => shared.has(code));

    // 실패 시 어느 코드가 재복제됐는지 이름까지 나오도록 배열로 비교한다.
    expect(redefined).toEqual([]);
  });

  // ⚠ 두 컴포넌트의 **폴백은 서로 다른 것이 사양**이다(배지=원문 코드 UI-017 /
  //   스테퍼=`처리중` UI-018). 위 가드가 "같아야 한다"고 말하는 축은 **표에 있는 코드**뿐이며,
  //   표 밖 코드까지 통일하면 배지가 빈 정보를 주거나 스테퍼가 기술 코드명을 노출한다.
  it('★표_밖_코드의_폴백은_배지와_스테퍼가_서로_다르다_통일_금지', () => {
    const unknown = 'SOME_NEW_BE_STAGE';
    render(<StageBadge stage={unknown} />);

    expect(screen.getByText(unknown)).toBeInTheDocument(); // 배지 = 원문 코드
    expect(stageLabel(unknown)).toBe('처리중'); // 스테퍼 = 한글 폴백
    expect(stageLabel(unknown)).not.toBe(unknown);
  });
});
