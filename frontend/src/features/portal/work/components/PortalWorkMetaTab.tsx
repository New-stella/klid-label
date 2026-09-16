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
