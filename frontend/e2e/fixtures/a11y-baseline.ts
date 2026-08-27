/**
 * 접근성 감사 허용목록(baseline) — **이미 인지·수용한 명도 대비 미달**만 통과시킨다.
 *
 * <p>이 파일은 통과용 스위치가 아니라 **재검토 장부**다. 항목마다 근거와 측정 수치를 남기고,
 * 여기에 없는 위반은 전부 실패시킨다({@link ../specs/a11y-contrast.spec.ts}).
 *
 * <h3>왜 색 조합(전경/배경 hex)으로 매칭하나</h3>
 * 선택자 문자열로 매칭하면 클래스명 리팩토링 한 번에 ①허용목록이 조용히 무력화되거나
 * ②반대로 무관한 신규 위반을 삼킨다. 대비 미달의 실체는 **색 조합**이므로 그것으로 고정한다.
 *
 * <h3>범위를 좁게 유지하는 장치</h3>
 * 색 조합이 같아도 **측정 대비보다 나빠지면 통과시키지 않는다**({@link RATIO_TOLERANCE}).
 * 팔레트가 바뀌어 같은 hex 가 더 나쁜 배경 위에 놓이는 변화를 신규 위반으로 잡기 위함이다.
 *
 * <h3>여기 없는 것</h3>
 * 대체 텍스트(alt) 허용목록은 **두지 않는다** — 현재 수용하기로 한 항목이 0건이다.
 * 빈 허용목록을 미리 만들어 두면 다음 사람이 "여기 넣으면 된다"로 읽는다.
 */

/**
 * 기록된 측정 대비와의 허용 오차. 렌더 반올림(정수 RGB → 소수 대비) 정도만 흡수한다.
 * 관측 대비가 `measured - RATIO_TOLERANCE` 보다 낮으면 **다른 조건**으로 보고 실패시킨다.
 */
export const RATIO_TOLERANCE = 0.05;

export interface ContrastAllowance {
  /** 전경색 — 소문자 `#rrggbb`. 알파가 있으면 배경과 합성한 실효 색이다. */
  fg: string;
  /** 배경색 — 소문자 `#rrggbb`. 반투명 조상을 합성한 실효 배경이다. */
  bg: string;
  /** 이 조합의 측정 대비. 관측값이 이보다 나빠지면 허용하지 않는다. */
  measured: number;
  /** 왜 허용하는가 — 한 줄 근거. 이 줄이 없으면 항목을 추가하지 않는다. */
  reason: string;
}

/**
 * KRDS 정본 팔레트 전환 때 인지·수용한 미달.
 *
 * <p>두 값 모두 DS-001 정본이 정한 색이라 **우리가 바꿀 수 없다**(프로젝트 지침: 디자인 토큰은
 * 구속 정책). 따라서 감사에서 실패시키지 않고 장부에 남긴다.
 *
 * <p>수치 산출: WCAG 2.x 상대휘도 공식으로 계산했고 `tailwind.config.js` 팔레트값과 대조했다.
 */
export const CONTRAST_ALLOWLIST: ContrastAllowance[] = [
  {
    fg: '#9e6a00',
    bg: '#fff3db',
    measured: 4.23,
    reason:
      'KRDS warn(warning-500 #9E6A00) 을 warning-50(#FFF3DB) 표면에 얹은 조합. 본문 기준 4.5:1 에 ' +
      '0.27 모자라나 두 값 모두 DS-001 정본이라 조정 불가 — 인지·수용한 미달.',
  },
  {
    fg: '#256ef4',
    bg: '#ecf2fe',
    measured: 4.05,
    reason:
      'KRDS primary(#256EF4) 를 primary-50(#ECF2FE) 표면에 얹은 조합. 본문 4.5:1 미달이나 ' +
      'UI 컴포넌트 기준 3:1 은 통과하며 두 값 모두 DS-001 정본이라 조정 불가 — 인지·수용한 미달.',
  },
];

/** 감사 결과 한 건이 허용목록에 해당하는지 판정한다. 해당하면 그 항목을, 아니면 undefined. */
export function findContrastAllowance(finding: {
  fg: string;
  bg: string;
  ratio: number;
}): ContrastAllowance | undefined {
  const fg = finding.fg.toLowerCase();
  const bg = finding.bg.toLowerCase();
  return CONTRAST_ALLOWLIST.find(
    (a) =>
      a.fg.toLowerCase() === fg &&
      a.bg.toLowerCase() === bg &&
      finding.ratio >= a.measured - RATIO_TOLERANCE,
  );
}
