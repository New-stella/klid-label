// 확인 창 첫 줄의 «대상 묶음» 칩 행. [@design SCREEN-009]
//
// ★ 왜 공유 컴포넌트인가: 시안은 **건너뛰기 확인 창과 재수행 확인 창 양쪽**에 같은 `.dlg-target`
//   줄을 둔다(`#dialog-skip-vlm` · `#dialog-skip-auto` · `#dialog-rerun-vlm` · `#dialog-rerun-auto`).
//   같은 조각을 두 파일이 각자 그리면 칩 배경·모서리·간격이 한쪽만 갱신돼 두 창이 서로 다르게
//   보인다 — 이 저장소가 반복해 겪은 결함이라 **모양의 소유자를 한 곳**으로 둔다.
//
// ⚠ 이 컴포넌트는 «어느 묶음인가»를 **판정하지 않는다** — 표시명과 부제를 문자열로 받는다.
//   묶음 코드→이름 표(`bundleLabel`)와 부제 표(`BUNDLE_SUBTITLE` · `bundleMemberSubtitle`)는
//   호출부가 소유하며, 여기서 코드를 해석하면 그 표가 둘이 된다.
//   ★ 두 창의 부제가 **같은 규칙이 아니다**(의도) — 건너뛰기는 «멤버 나열»이라 멤버가 하나뿐인
//   시계열에는 부제가 없고, 재수행은 «그 묶음이 무엇을 만드는가»라 시계열에도 부제가 있다.
//   그래서 규칙을 안으로 들이지 않고 호출부가 값을 고른다.

export interface BundleTargetRowProps {
  /** 대상 묶음의 사용자 노출명(예: 오토라벨링). 기술 코드는 화면에 넣지 않는다. */
  label: string;
  /** 부제 — 없으면 줄 자체가 없다(빈 자리를 남기지 않는다). */
  subtitle?: string | undefined;
}

/** @design SCREEN-009 */
export function BundleTargetRow({ label, subtitle }: BundleTargetRowProps) {
  return (
    // 무엇이 대상인지 제목 문자열에만 두지 않는다 — 확인 직전에 대상이 눈에 남아야 한다.
    // ⚠ 바깥 여백을 갖지 않는다 — 이 줄과 다음 요소 사이 간격은 **놓이는 자리**가 정한다
    //    (건너뛰기 모달은 폼의 `gap`, 확인 창은 본문 슬롯의 아래 여백). 여기서 margin 을 들면
    //    두 자리의 간격이 서로 달라진다.
    <div className="flex flex-wrap items-center gap-2" data-testid="bundle-target-row">
      <span className="text-label text-gray-600">대상 묶음</span>
      <span
        className="inline-flex items-center rounded-sm bg-gray-100 px-2.5 py-0.5 text-label text-gray-800"
        data-testid="bundle-target-chip"
      >
        {label}
      </span>
      {subtitle && (
        <span className="text-caption text-gray-600" data-testid="bundle-target-subtitle">
          {subtitle}
        </span>
      )}
    </div>
  );
}

export default BundleTargetRow;
