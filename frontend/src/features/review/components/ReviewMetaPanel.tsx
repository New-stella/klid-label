// SCREEN-019 — 검수 화면 우측 '메타' 탭(읽기 전용).
//
// 근본원인 대응: 검수자가 실제 진입하는 ReviewPage(/review/:id)에 이벤트 어노테이션·
//   영상 분석 설명 표시가 전혀 없어 검수자가 메타를 확인할 수 없었다. 이 패널은 등록값을
//   읽기 전용으로만 렌더한다 — 편집 인풋·저장·승인/반려 버튼 없음(확정은 영상 승인 시
//   자동 동결에 위임).
//
// ★2026-08-27 — 작업자 화면과 같은 구성으로 맞춘다 [design: SCREEN-019]
//   구 구성은 시계열 메타·이벤트 어노테이션·영상 정보 3개뿐이고 순서도 작업자와 역순이라,
//   작업자가 입력한 촬영환경·개인정보 판정(영상축·프레임축)·프레임 설명을 검수자가 확인할
//   경로가 <b>아예 없었다</b>. 개인정보 3필드는 학습데이터 산출물의 원천이라 검수 없이
//   확정되면 안 되는 값이다.
//   섹션 순서는 라벨링 화면(SCREEN-005)의 메타 탭과 <b>같다</b> — 두 화면을 오가는 검수자가
//   같은 자리에서 같은 것을 보아야 하며, 순서가 갈리면 무엇을 보고 있는지 대응시키지 못한다.
//
// ★2026-09-14 — 영상 분석 설명·이벤트 어노테이션은 <b>요약 카드 + 큰 창</b>이다
//   두 축의 전문(서술 500자대 · 캡션 후보 수백 자)을 폭 360px 탭에서 읽는 것이 불가능해,
//   이 자리에는 요약 카드만 두고 전문은 「크게 보기」로 여는 읽기 전용 창에서 확인한다.
//   ★구 읽기 전용 렌더(CaptionReadonly · EvidenceReadonly · MetaItemReadonly)는 창으로
//     옮겨갔다(EventAnnotationReadOnly · TimeseriesSidePanel 의 readOnly) — 여기에 다시
//     만들지 말 것. 같은 값이 두 벌로 갈리면 한쪽만 갱신된다.
//
// 재사용: 작업자 화면의 4개 패널(촬영환경·영상축 개인정보·프레임 설명·프레임축 개인정보)을
//   <b>읽기 전용 모드로 그대로 재사용</b>한다. 검수 전용 표현을 새로 만들면 같은 값이 두 벌로
//   갈려 한쪽만 갱신되는 이 저장소의 반복 결함이 재발한다.
//
// 보안(저장형 XSS 방어): 모든 값은 React 텍스트 노드로만 렌더 — 자동 escape.
//   dangerouslySetInnerHTML 미사용.
// a11y: 각 섹션에 aria-label, 라벨-값 구조. 읽기 전용이라 편집 인터랙션 요소가 없다.
// UI 문구: 기술 모델명 미노출. 이 자리에서는 「영상 분석 설명」이라고 쓴다(「시계열」 아님).

import { Alert } from '@/components/common/Alert';
import { useEventAnnotation } from '@/features/label/hooks/useEventAnnotation';
import { useMeta } from '@/features/auto/hooks/useMeta';
import { MetaHelpProvider, MetaHelpToggleButton } from '@/features/label/components/metaHelp';
import { useMetaHelpPreference } from '@/features/label/components/metaHelpPreference';
import { eventTypeNameOf } from '@/features/label/annotationSummary';
import { EnvironmentMetaPanel } from '@/features/label/components/EnvironmentMetaPanel';
import { FrameDescriptionPanel } from '@/features/label/components/FrameDescriptionPanel';
import { FramePrivacyMetaPanel } from '@/features/label/components/FramePrivacyMetaPanel';
import { ImportedMetaPanel } from '@/features/label/components/ImportedMetaPanel';
import { TimeseriesAnnotationSummaryCard } from '@/features/label/components/TimeseriesAnnotationSummaryCard';
import {
  technicalRows,
  VideoTechnicalMetaPanel,
} from '@/features/label/components/VideoTechnicalMetaPanel';
import { VideoPrivacyMetaPanel } from '@/features/label/components/VideoPrivacyMetaPanel';
import { useAnnotationSummary } from '@/features/label/hooks/useAnnotationSummary';
import type { AnnotationWindowState } from '@/features/label/hooks/useAnnotationWindow';
import type { VrfcEvntType } from '@/features/video/types';

export interface ReviewMetaPanelProps {
  /** 영상(rawSn) — 이벤트 어노테이션 조회 키. */
  rawSn: number | undefined;
  /** 현재 프레임 SRC_SN — 영상 분석 설명(메타) 조회 키. */
  srcSn: number | undefined;
  /** 「영상 분석 설명 · 이벤트 어노테이션」 창의 상태 — 요약 카드 버튼 문구를 정한다. */
  windowState: AnnotationWindowState;
  /** 요약 카드 버튼 — 닫혀 있으면 열고, 떠 있으면 앞으로 가져오고, 접혀 있으면 펼친다. */
  onOpenWindow: () => void;
  /** 이벤트 분류 이름 조달 — 영상 상세 응답의 전체 검증 이벤트 유형 목록(API-043). */
  allVrfcEvntTypes?: VrfcEvntType[];
}

/**
 * 검수 화면 우측 '메타' 탭 — 읽기 전용 표시 패널.
 *
 * <p>작업자 라벨링 화면(SCREEN-005)과 <b>같은 순서·같은 제목</b>으로 나열한다:
 * 촬영환경 → 개인정보(영상) → 프레임 설명 → 개인정보(프레임) →
 * 영상 분석 설명 · 이벤트 어노테이션 요약 카드 → [이관 원문 정보] → [영상 기술 정보].
 * ★이 순서는 사양이다 — 임의로 바꾸지 말 것.
 *
 * <p>★모든 항목이 <b>읽기 전용</b>이다. 값의 수정은 라벨링 화면이 담당하며 검수 승인 시점에
 * 동결된다 — 편집 지점을 두 화면에 두면 확정 경로가 갈라진다.
 *
 * <p>표시할 것이 하나도 없으면 빈 상태 안내를 노출하고 크래시하지 않는다.
 * ★이관 원문만 있는 영상은 빈 상태가 아니다 — 그 목록을 빈 상태 판정에서 빼면 보여줄 값이
 * 있는데도 "표시할 값이 없습니다"가 떠 거짓말이 된다.
 *
 * <p>★★빈 상태 안내는 <b>자기가 말하는 범위를 이름으로 밝힌다</b>. 앞의 네 섹션(촬영환경·
 * 개인정보 두 축·프레임 설명)은 <b>각 패널이 자기 훅으로 따로 조회</b>하므로 이 판정식에 들어올
 * 수 없는데, 그 패널들은 판정과 무관하게 <b>항상 렌더</b>된다. 그래서 범위를 밝히지 않는 구 문구를
 * 패널 맨 위에 두면, 개인정보 판정만 채워진 프레임에서 <b>안내 바로 아래에 실제 판정값이 나란히
 * 뜨는</b> 자기모순이 생긴다. ⇒ 안내를 <b>그 네 섹션 뒤</b>로 내리고 문구를 자기가 실제로 세는
 * 섹션의 이름으로 좁힌다.
 *
 * @design SCREEN-019
 * @design API-066
 * @design UI-157
 */
export function ReviewMetaPanel({
  rawSn,
  srcSn,
  windowState,
  onOpenWindow,
  allVrfcEvntTypes,
}: ReviewMetaPanelProps) {
  const { data: ea } = useEventAnnotation(rawSn);
  const { data: meta, isError: metaError } = useMeta(srcSn);
  const summary = useAnnotationSummary(rawSn, srcSn);
  const [helpVisible, toggleHelp] = useMetaHelpPreference();

  const payload = ea?.payload;
  const hasEventClass = !!payload && (payload.event_class ?? '').trim() !== '';
  const captionEntries = payload?.caption ? Object.entries(payload.caption) : [];
  const evidenceEntries = payload?.evidence ? Object.entries(payload.evidence) : [];
  const hasEventAnnotation =
    hasEventClass ||
    (payload?.question ?? '').trim() !== '' ||
    (payload?.answer ?? '').trim() !== '' ||
    captionEntries.length > 0 ||
    evidenceEntries.length > 0;

  const hasMeta = (meta?.items ?? []).length > 0;
  // 영상 기술메타(video.*) — 영상 분석 설명이 아니라 ffprobe/관제 인입이 채운 영상 기술 정보다.
  // 같은 테이블(LS_DATA_META)에 저장돼 한동안 섞여 표시됐다(2026-08-03 분리).
  //
  // ★세는 기준은 <b>패널이 실제로 그리는 행</b>이다 — 응답 건수로 세면, 화면에 두지 않는 키
  //   (파일 크기·비트레이트)만 있는 영상에서 「값이 있다」고 판정해 <b>안내도 값도 없는 빈 탭</b>이
  //   된다. 이 파일이 경고하는 자기모순의 반대 방향이다.
  const technicalRowCount = technicalRows(meta?.technicalMeta ?? []).length;
  const hasTechnical = technicalRowCount > 0;
  // 이관 원문(import.*) — 2026-08-27 분리. 이것도 영상 분석 설명이 아니며 서버가 갈라 내려준다.
  //   빈 상태 판정에 포함해야 "이관 원문만 있는 영상"에서 거짓 안내가 뜨지 않는다.
  const hasImported = (meta?.importedMeta ?? []).length > 0;

  // ★이 판정이 세는 것은 아래 네 축뿐이다(영상 분석 설명 · 이벤트 어노테이션 · 이관 원문 정보 ·
  //   영상 기술 정보). 앞의 네 패널은 자기 훅으로 따로 조회하므로 여기 합류시킬 수 없고, 그래서
  //   안내 문구가 자기 범위를 이름으로 밝힌다 — 위 클래스 주석 참조.
  const isEmpty = !hasEventAnnotation && !hasMeta && !hasTechnical && !hasImported;

  return (
    <section
      className="border-t border-gray-200"
      aria-label="메타 정보"
      data-testid="review-meta-panel"
    >
      {/* 패널 머리 — 제목 + 도움말 토글 하나. ★토글을 구역마다 두지 않고 여기 하나만 두어
          전 구역의 설명문을 한꺼번에 여닫는다(근거는 {@code metaHelp.tsx}). */}
      <div className="flex items-center justify-between gap-2 border-b border-gray-100 px-4 py-2">
        <h2 className="text-title-sm text-gray-900">메타 정보</h2>
        <MetaHelpToggleButton visible={helpVisible} onToggle={toggleHelp} />
      </div>

      {/* ★메타 조회가 <b>실패</b>했을 때는 아래 빈 상태 안내를 쓰지 않는다 — 두 상태는 다르고,
          실패를 「표시할 값이 없습니다」로 말하면 화면이 거짓말을 한다(사양 SCREEN-019 가 두
          안내를 한 문구로 합치지 말라고 못박는다). 자리는 빈 상태 안내와 같은 범위다 — 앞의 네
          패널은 각자 자기 훅으로 조회하므로 이 실패에 영향받지 않는다. */}
      {metaError && (
        <Alert
          variant="error"
          className="m-3"
          data-testid="review-meta-error"
          title="메타 정보를 불러오지 못했습니다. 새로고침 후 다시 시도해 주세요."
        />
      )}

      <MetaHelpProvider visible={helpVisible}>
      {/* ★1~4 — 작업자 화면의 패널을 읽기 전용으로 재사용한다(검수 전용 표현을 만들지 않는다).
          순서는 사양 고정: 촬영환경 → 영상축 개인정보 → 프레임 설명 → 프레임축 개인정보.
          ⚠ 이 넷은 각자 자기 훅으로 조회하므로 아래 빈 상태 판정이 세는 대상이 아니다. */}
      <EnvironmentMetaPanel rawSn={rawSn} readOnly />
      <VideoPrivacyMetaPanel rawSn={rawSn} readOnly />
      <FrameDescriptionPanel srcSn={srcSn} readOnly />
      <FramePrivacyMetaPanel srcSn={srcSn} readOnly />

      {/* 5 — 영상 분석 설명 · 이벤트 어노테이션 요약. 전문은 「크게 보기」로 여는 읽기 전용 창이다.
          ★검수 화면이라 검토 상태 행을 두지 않는다 — 검토 상태 확정은 영상 검수 승인 시 자동이라
            검수자가 이 자리에서 판단할 것이 없다. */}
      <TimeseriesAnnotationSummaryCard
        mode="readOnly"
        windowState={windowState}
        eventTypeCd={summary.eventTypeCd}
        eventTypeName={eventTypeNameOf(allVrfcEvntTypes, summary.eventTypeCd)}
        descriptionFirstLine={summary.descriptionFirstLine}
        onOpenWindow={onOpenWindow}
      />

      {/* ★빈 상태 안내 — 자리와 문구가 모두 «요약 카드가 가리키는 두 축 + 아래 두 섹션»으로
          한정된다. 위의 네 패널보다 앞에 두거나 범위를 밝히지 않는 문구를 쓰면, 그 패널들이 값을
          그리는 동안 "메타 정보가 없다"고 말하게 되어 안내와 값이 한 화면에 함께 뜬다. */}
      {/* ★조회 실패일 때는 위의 실패 안내가 대신한다 — 「값이 없다」와 「못 불러왔다」를 같은
          문구로 합치지 않는다(합치면 새로고침하면 되는 상황을 값 없음으로 읽게 된다). */}
      {isEmpty && !metaError && (
        <p
          className="px-1 py-4 text-center text-caption text-gray-500"
          data-testid="review-meta-empty"
        >
          영상 분석 설명 · 이벤트 어노테이션 · 이관 원문 정보 · 영상 기술 정보에는 표시할 값이 없습니다.
        </p>
      )}

      {/* 참고 — 이관 원문 정보. 라벨링 화면과 같은 컴포넌트를 쓰며 이관 영상에서만 스스로 렌더한다. */}
      <ImportedMetaPanel srcSn={srcSn} readOnly />

      {/* 참고 — 영상 기술 정보. ★라벨링 화면과 <b>같은 부품</b>을 쓴다(문구가 글자 단위로 같다).
          구 렌더는 서버 K/V 를 날것으로 늘어놓아 내부 저장 키가 라벨로 보이고 길이가 밀리초로
          보였다 — 되돌리지 말 것. */}
      <VideoTechnicalMetaPanel srcSn={srcSn} />
      </MetaHelpProvider>
    </section>
  );
}
