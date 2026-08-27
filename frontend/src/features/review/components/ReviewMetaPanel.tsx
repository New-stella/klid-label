// SCREEN-019 — 검수 화면 우측 '메타' 탭(읽기 전용).
//
// 근본원인 대응: 검수자가 실제 진입하는 ReviewPage(/review/:id)에 event_annotation·
//   시계열 메타 표시가 전혀 없어 검수자가 메타를 확인할 수 없었다. 이 패널은 등록값을
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
// 재사용: 작업자 화면의 4개 패널(촬영환경·영상축 개인정보·프레임 설명·프레임축 개인정보)을
//   <b>읽기 전용 모드로 그대로 재사용</b>한다. 검수 전용 표현을 새로 만들면 같은 값이 두 벌로
//   갈려 한쪽만 갱신되는 이 저장소의 반복 결함이 재발한다. 접이식 섹션 래퍼(MetaSection)도
//   두 화면이 공유한다 — 검수 화면이 자기 헤더 스타일을 따로 갖지 않는다.
//   시계열 메타·이벤트 어노테이션은 편집·검토 로직이 얽힌 작업자 패널 대신 값만 렌더하는
//   소형 컴포넌트로 두어 읽기 전용을 구조적으로 보장한다.
//
// 보안(저장형 XSS 방어): 모든 값은 React 텍스트 노드로만 렌더 — 자동 escape.
//   dangerouslySetInnerHTML 미사용.
// a11y: 각 섹션에 aria-label, 라벨-값 구조. 읽기 전용이라 인터랙션 요소 없음.
// UI 문구: 기술 모델명(VLM/YOLO/SAM2) 미노출 — '이벤트 어노테이션'/'시계열 메타' 표기.

import { useEventAnnotation } from '@/features/label/hooks/useEventAnnotation';
import { useMeta } from '@/features/auto/hooks/useMeta';
import {
  normalizeCot,
  type CaptionCandidate,
  type EvidenceCandidate,
} from '@/features/label/api/eventAnnotation';
import { EnvironmentMetaPanel } from '@/features/label/components/EnvironmentMetaPanel';
import { FrameDescriptionPanel } from '@/features/label/components/FrameDescriptionPanel';
import { FramePrivacyMetaPanel } from '@/features/label/components/FramePrivacyMetaPanel';
import { ImportedMetaPanel } from '@/features/label/components/ImportedMetaPanel';
import { MetaSection } from '@/features/label/components/MetaSection';
import { VideoPrivacyMetaPanel } from '@/features/label/components/VideoPrivacyMetaPanel';
import type { MetaItem } from '@/features/auto/types';

export interface ReviewMetaPanelProps {
  /** 영상(rawSn) — event_annotation 조회 키. */
  rawSn: number | undefined;
  /** 현재 프레임 SRC_SN — 시계열 메타 조회 키. */
  srcSn: number | undefined;
}

/** 검토 상태 → 중립 한글 라벨(기술 모델명 노출 금지). */
const REVIEW_STATUS_LABEL: Record<string, string> = {
  AUTO_GENERATED: '검토 대기',
  PENDING: '검토 대기',
  APPROVED: '승인됨',
  REJECTED: '반려됨',
};

const LABEL_CLASS = 'text-[11px] text-gray-500';
const VALUE_CLASS = 'whitespace-pre-wrap break-words text-body-md text-gray-900';

function ReadonlyField({ label, value }: { label: string; value: string }) {
  return (
    <div className="mt-2 first:mt-0">
      <span className={LABEL_CLASS}>{label}</span>
      <p className={VALUE_CLASS}>{value}</p>
    </div>
  );
}

/** caption 후보(caption_text + CoT 단계)를 읽기 전용으로 렌더. */
function CaptionReadonly({ ck, cand }: { ck: string; cand: CaptionCandidate }) {
  const cot = normalizeCot(cand.cot).filter((s) => s.trim() !== '');
  return (
    <div className="mt-2 rounded border border-gray-200 p-2">
      <span className={LABEL_CLASS}>캡션 {ck}</span>
      {cand.caption_text != null && cand.caption_text.trim() !== '' && (
        <p className={VALUE_CLASS}>{cand.caption_text}</p>
      )}
      {cot.length > 0 && (
        <ol className="mt-1 list-decimal pl-4 text-body-md text-gray-700">
          {cot.map((step, i) => (
            <li key={i}>{step}</li>
          ))}
        </ol>
      )}
    </div>
  );
}

/** evidence 후보(evidence_text + obj_id/obj_label/obj_bbox/frame_id)를 읽기 전용으로 렌더. */
function EvidenceReadonly({ ck, cand }: { ck: string; cand: EvidenceCandidate }) {
  const objId = (cand.obj_id ?? []).join(', ');
  const objLabel = (cand.obj_label ?? []).join(', ');
  const frameId = (cand.frame_id ?? []).join(', ');
  const objBbox = (cand.obj_bbox ?? []).map((b) => b.join(',')).join(' / ');
  return (
    <div className="mt-2 rounded border border-gray-200 p-2">
      <span className={LABEL_CLASS}>근거 {ck}</span>
      {cand.evidence_text != null && cand.evidence_text.trim() !== '' && (
        <p className={VALUE_CLASS}>{cand.evidence_text}</p>
      )}
      {objId !== '' && (
        <p className="text-caption text-gray-500">
          객체 ID: <span className="text-gray-900">{objId}</span>
        </p>
      )}
      {objLabel !== '' && (
        <p className="text-caption text-gray-500">
          객체 라벨: <span className="text-gray-900">{objLabel}</span>
        </p>
      )}
      {objBbox !== '' && (
        <p className="text-caption text-gray-500">
          객체 좌표: <span className="text-gray-900">{objBbox}</span>
        </p>
      )}
      {frameId !== '' && (
        <p className="text-caption text-gray-500">
          프레임: <span className="text-gray-900">{frameId}</span>
        </p>
      )}
    </div>
  );
}

/** 시계열 메타 항목(metaVal + reviewStatus 배지)을 읽기 전용으로 렌더. */
function MetaItemReadonly({ item }: { item: MetaItem }) {
  const status = item.reviewStatus ?? null;
  return (
    <div className="mt-2 first:mt-0 rounded border border-gray-200 p-2">
      <div className="flex items-center justify-between gap-2">
        <span className={LABEL_CLASS}>{item.metaKey}</span>
        {status != null && status !== '' && (
          <span
            data-testid={`review-meta-ts-status-${item.metaSn}`}
            className="rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-700"
          >
            {REVIEW_STATUS_LABEL[status] ?? status}
          </span>
        )}
      </div>
      <p className={VALUE_CLASS}>{item.metaVal}</p>
    </div>
  );
}

/**
 * 검수 화면 우측 '메타' 탭 — 읽기 전용 표시 패널.
 *
 * <p>작업자 라벨링 화면(SCREEN-005)과 <b>같은 순서·같은 제목</b>의 여섯 섹션을 나열하고, 그 뒤에
 * 참고 정보로 이관 원문 정보(이관으로 들어온 영상에서만)와 영상 기술 정보를 둔다:
 * 촬영환경 → 개인정보(영상) → 프레임 설명 → 개인정보(프레임) → 시계열 메타 →
 * 이벤트 어노테이션 → [이관 원문 정보] → [영상 정보].
 * ★이 순서는 사양이다 — 임의로 바꾸지 말 것.
 *
 * <p>★모든 섹션이 <b>읽기 전용</b>이다. 값의 수정은 라벨링 화면의 메타 패널이 담당하며 검수
 * 승인 시점에 동결된다 — 편집 지점을 두 화면에 두면 확정 경로가 갈라진다. 앞의 네 섹션은
 * 작업자 화면의 패널을 {@code readOnly} 로 재사용하며, 그 플래그의 기본값은 편집 가능이라
 * 여기서만 명시적으로 켠다.
 *
 * <p>표시할 것이 하나도 없으면(0건/미생성) 빈 상태 안내를 노출하고 크래시하지 않는다.
 * ★이관 원문만 있는 영상은 빈 상태가 아니다 — 그 목록을 빈 상태 판정에서 빼면 보여줄 값이
 * 있는데도 "표시할 값이 없습니다"가 떠 거짓말이 된다.
 *
 * <p>★★빈 상태 안내는 <b>자기가 말하는 범위를 이름으로 밝힌다</b>. 앞의 네 섹션(촬영환경·
 * 개인정보 두 축·프레임 설명)은 <b>각 패널이 자기 훅으로 따로 조회</b>하므로 이 판정식에 들어올
 * 수 없는데, 그 패널들은 판정과 무관하게 <b>항상 렌더</b>된다. 그래서 구 문구 "표시할 메타
 * 정보가 없습니다"를 패널 맨 위에 두면, 개인정보 판정만 채워진 프레임에서 <b>안내 바로 아래에
 * 실제 판정값이 나란히 뜨는</b> 자기모순이 생긴다(개인정보 3필드는 적재 시점에 값이 채워지므로
 * 도달성이 낮지 않다). ⇒ 안내를 <b>그 네 섹션 뒤</b>로 내리고 문구를 자기가 실제로 세는 네
 * 섹션의 이름으로 좁힌다. 판정식을 바꾸지 않는 이유는 자식이 자기 훅을 갖는 현 구조를 그대로
 * 두기 위해서다.
 *
 * @design SCREEN-019
 * @design API-066
 */
export function ReviewMetaPanel({ rawSn, srcSn }: ReviewMetaPanelProps) {
  const { data: ea } = useEventAnnotation(rawSn);
  const { data: meta } = useMeta(srcSn);

  const payload = ea?.payload;
  const eaStatus = ea?.reviewStatus ?? null;
  const hasEventClass = !!payload && (payload.event_class ?? '').trim() !== '';
  const captionEntries = payload?.caption ? Object.entries(payload.caption) : [];
  const evidenceEntries = payload?.evidence ? Object.entries(payload.evidence) : [];
  const hasEventAnnotation =
    hasEventClass ||
    (payload?.question ?? '').trim() !== '' ||
    (payload?.answer ?? '').trim() !== '' ||
    captionEntries.length > 0 ||
    evidenceEntries.length > 0;

  const metaItems = meta?.items ?? [];
  const hasMeta = metaItems.length > 0;
  // 영상 기술메타(video.*) — 시계열 메타가 아니라 ffprobe/관제 인입이 채운 영상 기술 정보다.
  // 같은 테이블(LS_DATA_META)에 저장돼 한동안 '시계열 메타'로 섞여 표시됐다(2026-08-03 분리).
  const technicalItems = meta?.technicalMeta ?? [];
  const hasTechnical = technicalItems.length > 0;
  // 이관 원문(import.*) — 2026-08-27 분리. 이것도 시계열 메타가 아니며 서버가 갈라 내려준다.
  //   빈 상태 판정에 포함해야 "이관 원문만 있는 영상"에서 거짓 안내가 뜨지 않는다.
  //   ★표시는 ImportedMetaPanel 이 담당한다(라벨링 화면과 공유) — 여기서는 세기만 한다.
  const hasImported = (meta?.importedMeta ?? []).length > 0;
  // ★ BE readOnlyMeta(일치도 등)는 화면에 표시하지 않는다 — 판정 창구를 연동하지 않게 되면서
  //   그 값은 새로 생기지 않고 남은 것은 과거 위탁분뿐이라 참고 정보 섹션째 뺐다.
  //   빈 상태 판정에서도 빠진다: 그 값만 있는 영상은 "표시할 값이 없음"이 맞다.

  // ★이 판정이 세는 것은 아래 네 섹션뿐이다(시계열 메타 · 이벤트 어노테이션 · 이관 원문 정보 ·
  //   영상 정보). 앞의 네 패널은 자기 훅으로 따로 조회하므로 여기 합류시킬 수 없고, 그래서
  //   안내 문구가 자기 범위를 이름으로 밝힌다 — 위 클래스 주석 참조.
  const isEmpty = !hasEventAnnotation && !hasMeta && !hasTechnical && !hasImported;

  return (
    <section
      className="border-t border-gray-200"
      aria-label="메타 정보"
      data-testid="review-meta-panel"
    >
      <h2 className="px-3 pt-3 pb-2 text-label font-semibold uppercase tracking-wide text-gray-500">
        메타 정보
      </h2>

      {/* ★1~4 — 작업자 화면의 패널을 읽기 전용으로 재사용한다(검수 전용 표현을 만들지 않는다).
          순서는 사양 고정: 촬영환경 → 영상축 개인정보 → 프레임 설명 → 프레임축 개인정보.
          ⚠ 이 넷은 각자 자기 훅으로 조회하므로 아래 빈 상태 판정이 세는 대상이 아니다. */}
      <EnvironmentMetaPanel rawSn={rawSn} readOnly />
      <VideoPrivacyMetaPanel rawSn={rawSn} readOnly />
      <FrameDescriptionPanel srcSn={srcSn} readOnly />
      <FramePrivacyMetaPanel srcSn={srcSn} readOnly />

      {/* ★빈 상태 안내 — 자리와 문구가 모두 «이 아래 네 섹션»으로 한정된다.
          위의 네 패널보다 앞에 두거나 범위를 밝히지 않는 문구를 쓰면, 그 패널들이 값을 그리는
          동안 "메타 정보가 없다"고 말하게 되어 안내와 값이 한 화면에 함께 뜬다.
          문구의 섹션 이름은 아래 실제 섹션 제목과 <b>같은 낱말</b>이어야 한다 — 다르면 검수자가
          무엇이 비었다는 말인지 대응시키지 못한다. */}
      {isEmpty && (
        <p
          className="px-1 py-4 text-center text-caption text-gray-500"
          data-testid="review-meta-empty"
        >
          시계열 메타 · 이벤트 어노테이션 · 이관 원문 정보 · 영상 정보에는 표시할 값이 없습니다.
        </p>
      )}

      {/* 5 — 시계열 메타 읽기 표시 */}
      {hasMeta && (
        <MetaSection title="시계열 메타">
          <div aria-label="시계열 메타" data-testid="review-meta-timeseries">
            {metaItems.map((item) => (
              <MetaItemReadonly key={item.metaSn} item={item} />
            ))}
          </div>
        </MetaSection>
      )}

      {/* 6 — event_annotation 읽기 표시 */}
      {hasEventAnnotation && payload && (
        <MetaSection title="이벤트 어노테이션">
          <div
            aria-label="이벤트 어노테이션"
            data-testid="review-meta-event-annotation"
          >
            {eaStatus != null && eaStatus !== '' && (
              <div className="mb-1 flex items-center justify-end">
                <span
                  data-testid="review-meta-ea-status"
                  className="rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-700"
                >
                  {REVIEW_STATUS_LABEL[eaStatus] ?? eaStatus}
                </span>
              </div>
            )}

            {hasEventClass && (
              <ReadonlyField label="이벤트 분류" value={payload.event_class} />
            )}
            {(payload.question ?? '').trim() !== '' && (
              <ReadonlyField label="질의" value={payload.question as string} />
            )}
            {(payload.answer ?? '').trim() !== '' && (
              <ReadonlyField label="답변" value={payload.answer as string} />
            )}

            {captionEntries.length > 0 && (
              <div className="mt-2">
                <span className="text-[11px] font-semibold uppercase text-gray-500">
                  캡션 후보
                </span>
                {captionEntries.map(([ck, cand]) => (
                  <CaptionReadonly key={ck} ck={ck} cand={cand} />
                ))}
              </div>
            )}

            {evidenceEntries.length > 0 && (
              <div className="mt-2">
                <span className="text-[11px] font-semibold uppercase text-gray-500">
                  근거 후보
                </span>
                {evidenceEntries.map(([ck, cand]) => (
                  <EvidenceReadonly key={ck} ck={ck} cand={cand} />
                ))}
              </div>
            )}
          </div>
        </MetaSection>
      )}

      {/* 참고 — 이관 원문 정보. 라벨링 화면과 같은 컴포넌트를 쓰며 이관 영상에서만 스스로 렌더한다. */}
      <ImportedMetaPanel srcSn={srcSn} />

      {/* 참고 — 영상 정보(기술메타). 검토 대상이 아니므로 상태 배지 없이 K/V 만. */}
      {hasTechnical && (
        <MetaSection title="영상 정보">
          <div aria-label="영상 정보" data-testid="review-meta-technical">
            {technicalItems.map((item) => (
              // 값은 BE 원문 그대로 표시한다 — 단위 변환·포맷팅은 하지 않는다(별건).
              <ReadonlyField
                key={item.metaSn}
                label={item.metaKey}
                value={item.metaVal}
              />
            ))}
          </div>
        </MetaSection>
      )}
    </section>
  );
}
