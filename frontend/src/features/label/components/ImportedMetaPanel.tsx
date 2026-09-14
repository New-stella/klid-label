// 우측 '메타' 탭 — 이관 원문 정보 패널(읽기 전용).
//
// 외부 산출물 이관이 저작도구 스키마에 착지할 컬럼이 없어 원문 그대로 보관한 값을 참고용으로
// 보여준다(좌표·위치·카메라 설치 높이/방위/관리번호·데이터 출처·이벤트 기록·이벤트 상위 계층
// 이름·외부 영상 식별자·원천 축 개인정보 판정).
//
// ★라벨링 화면(SCREEN-005)과 검수 화면(SCREEN-019)이 <b>같은 컴포넌트를 재사용</b>한다 —
//   검수 전용 표현을 따로 만들면 같은 값이 두 벌로 갈려 한쪽만 갱신되는 이 저장소의 반복 결함이
//   재발한다. 두 화면 모두 메타 탭의 여섯 패널 뒤 '참고 정보' 자리에 둔다.
//
// ★분류의 소유자는 서버다 — 이 패널은 응답의 importedMeta 목록을 그대로 그리고, 열쇠 접두
//   (import.)를 파싱해 시계열 메타와 스스로 가르지 않는다.
//
// ★읽기 전용이다 — 편집·저장 대상이 아니며 BE 가 이 열쇠의 수정 요청을 400 으로 거부한다.
//   입력 칸·저장 버튼을 두지 않는다('영상 기술 정보'와 같은 결).
//
// 보안(저장형 XSS 방어): 값은 React 텍스트 노드로만 렌더 — 자동 escape.
//   dangerouslySetInnerHTML 미사용.
// a11y: 라벨-값 구조. 읽기 전용이라 인터랙션 요소는 섹션 접기 토글뿐이다.
//
// @design SCREEN-005
// @design SCREEN-019
// @design API-066

import { useMeta } from '@/features/auto/hooks/useMeta';
import { importedMetaLabel } from '@/features/auto/metaKeys';

import { MetaSection } from './MetaSection';

export interface ImportedMetaPanelProps {
  /** 현재 프레임 SRC_SN — 메타 조회 키(영상 단위 K/V 를 프레임 경로로 조회한다). */
  srcSn: number | undefined;
  /**
   * 검수 화면(SCREEN-019)인가 — <b>구역 이름만</b> 가른다.
   *
   * ★이 패널은 두 화면 모두 읽기 전용이라 이 값이 동작을 바꾸지 않는다. 그래도 형제 네 패널
   * (촬영환경·개인정보 두 축·프레임 설명)과 <b>같은 이름의 prop</b>을 쓰는 이유는, 검수 경로가
   * 다섯 패널에 같은 말을 걸어야 하나만 빠뜨리는 일이 생기지 않기 때문이다(실제로 이 패널이
   * 빠져 검수 화면에서 혼자 라벨링 이름으로 보였다).
   */
  readOnly?: boolean;
}

const LABEL_CLASS = 'block text-[11px] text-gray-500';
const VALUE_CLASS = 'whitespace-pre-wrap break-words text-body-md text-gray-700';

/**
 * 이관 원문 정보 패널.
 *
 * <p>이관으로 들어오지 않은 영상에서는 목록이 <b>빈 배열인 것이 정상</b>이라 오류로 안내하지
 * 않고 패널 자체를 노출하지 않는다(대다수 영상이 여기 해당한다).
 *
 * <p>라벨은 사람이 읽는 이름으로 표시하며, 이름을 정하지 못한 열쇠는 <b>버리지 않고 원문 열쇠
 * 그대로</b> 표시한다 — 이관이 열쇠를 늘려도 그 값이 화면에서 사라지지 않아야 한다.
 */
export function ImportedMetaPanel({ srcSn, readOnly = false }: ImportedMetaPanelProps) {
  const { data } = useMeta(srcSn);
  const items = data?.importedMeta ?? [];

  if (items.length === 0) {
    return null;
  }

  // ★검수 화면의 구역 이름은 라벨링과 **일부러 다르다** — 검수는 「…검토」로 끝난다
  //   (SCREEN-019). 두 이름을 같게 「통일」하면 확정된 사양을 되돌리는 것이다.
  return (
    <MetaSection title={readOnly ? '이관 원문 정보 검토' : '이관 원문 정보'}>
      <div className="space-y-2" data-testid="imported-meta-panel">
        <p className="text-[11px] leading-snug text-gray-500">
          외부에서 이관해 온 원문 정보입니다. 참고용이며 수정할 수 없습니다.
        </p>
        {items.map((item) => (
          <div
            key={item.metaSn}
            className="rounded border border-gray-200 p-2"
            data-testid={`imported-meta-row-${item.metaSn}`}
          >
            <span className={LABEL_CLASS}>{importedMetaLabel(item.metaKey)}</span>
            {/* 값은 BE 원문 그대로 표시한다 — 단위 변환·포맷팅은 하지 않는다. */}
            <p className={VALUE_CLASS}>{item.metaVal}</p>
          </div>
        ))}
      </div>
    </MetaSection>
  );
}
