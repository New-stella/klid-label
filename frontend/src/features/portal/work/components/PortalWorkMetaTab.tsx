// 포털 라벨링 화면 우측 패널 '메타' 탭의 본문 — 메타 목록 + 이벤트 어노테이션.
//
// @design SCREEN-029
//
// <h3>배치 순서는 사양 고정이다</h3>
// 촬영환경 → 영상축 개인정보 → 프레임 설명 → 프레임축 개인정보 → 시계열 메타 → 이벤트 어노테이션.
// 앞의 다섯은 메타 창구 하나가 함께 내려주므로 {@link PortalMetaPanel} 안에서 그 순서로 서고,
// 이벤트 어노테이션은 창구가 달라(영상 단위) 그 뒤에 별도 패널로 선다.
//
// <h3>★ 이 탭은 자산 출처를 가리지 않는다</h3>
// 데이터마트에서 불러온 영상과 본인이 올린 영상 모두에서 같은 순서로 서고 같은 방식으로 고친다.
// 저장처는 화면이 가르지 않고 <b>서버가 자산 출처로 판정</b>한다.
//
// <h3>★★ 이 탭은 관제 산출물에도 CSS 를 끌고 온다 — 알고 둔 상태다 (2026-09-16)</h3>
// 이 탭은 <b>관제·포털이 함께 쓰는</b> 라벨링 화면(`pages/label/LabelingPage`)이 <b>정적으로</b>
// import 한다. 그래서 두 패널이 끌어오는 포털 부품 킷 CSS 가 **관제 채널 산출물에도 실린다** —
// 관제 라벨링 청크에 CSS 약 57KB(빌드 산출물 실측).
//
// ★ <b>관제 화면의 모습은 바뀌지 않는다</b> — 실린 규칙이 전부 클래스로 좁혀져 있다
//   (`:root` 토큰 정의 **0건** · 태그만으로 된 선택자 **0건**, 산출물 실측). 관제 마크업에는
//   그 클래스가 없어 아무것도 걸리지 않는다. 무게만 늘 뿐이다.
//
// ⚠⚠ <b>지연 로드로 덜어내려다 되돌렸다 — 다시 시도하지 말 것.</b> 두 패널을 `lazy()` 로 바꿨더니
//   묶음이 갈리는 것이 아니라 **공유 청크(`api-*`)로 옮겨 갔고**, 그 청크는 진입점이 정적으로
//   부르므로 **관제 전 화면이 내려받게 되어 오히려 나빠졌다**(두 형상 모두 산출물로 실측).
//   지금 자리에서는 관제 <b>라벨링 경로에서만</b> 실린다.
// ⇒ 제대로 덜어내려면 <b>관제 화면이 이 탭을 지연 로드</b>해야 하는데 관제 코드는 고치지
//   않는다(사용자 확정, 구속). 그 결정이 바뀌면 그때 한 줄로 해결된다.

import { PortalEventAnnotationPanel } from './PortalEventAnnotationPanel';
import { PortalMetaPanel } from './PortalMetaPanel';
import './PortalWorkMetaTab.css';

export interface PortalWorkMetaTabProps {
  /** 프레임 PK — 메타 창구의 대상. */
  srcSn: number | undefined;
  /** 영상 PK — 이벤트 어노테이션 창구의 대상(영상 단위라 프레임을 옮겨도 같은 값이 선다). */
  rawSn: number | undefined;
}

export function PortalWorkMetaTab({ srcSn, rawSn }: PortalWorkMetaTabProps) {
  return (
    /*
     * 짜임은 포털 시안의 옆 칸 본문 그대로다 — 묶음 사이 20(`klid-labeling-panel-body`).
     *
     * ★`klid-labeling` 은 **저장 안내 글 위에 선을 긋는** 규칙(`.klid-labeling .klid-tool-panel-note`)
     *   의 조상 고리다. 시안에서는 편집기 뼈대(`EditorLayout className="klid-labeling"`)가 그 자리에
     *   서는데 그 뼈대가 관제 화면 소유라, 탭이 스스로 고리를 건다. 같은 이름으로 시작하는 다른
     *   규칙(`klid-labeling-*`)은 전부 <b>별개 클래스</b>라 이 고리에 딸려오지 않는다.
     */
    <div
      data-testid="portal-work-meta-tab"
      className="klid-labeling klid-portal-meta-tab klid-labeling-panel-body"
    >
      <PortalMetaPanel srcSn={srcSn} />
      <PortalEventAnnotationPanel rawSn={rawSn} />
    </div>
  );
}
