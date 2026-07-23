// SCR-REVIEW-002 — 검수 화면 우측 aside 의 메타 읽기 표시 패널(읽기 전용).
//
// 근본원인 대응: 검수자가 실제 진입하는 ReviewPage(/review/:id)에 event_annotation·
//   시계열 메타 표시가 전혀 없어 검수자가 메타를 확인할 수 없었다. 이 패널은 등록값을
//   읽기 전용으로만 렌더한다 — 편집 인풋·저장·승인/반려 버튼 없음(확정은 영상 승인 시
//   자동 동결에 위임).
//
// 재사용: 데이터 조회는 라벨링 화면과 동일한 useEventAnnotation(rawSn)/useMeta(srcSn) 훅을
//   그대로 재사용한다(신규 API·로직 없음). 편집·검토 로직이 얽힌 EventAnnotationPanel/
//   TimeseriesSidePanel 대신 값만 렌더하는 소형 컴포넌트로 분리해 읽기 전용을 구조적으로 보장.
//
// 보안(저장형 XSS 방어): 모든 값은 React 텍스트 노드로만 렌더 — 자동 escape.
//   dangerouslySetInnerHTML 미사용.
// a11y: 각 섹션에 aria-label, 라벨-값 구조. 읽기 전용이라 인터랙션 요소 없음.
// UI 문구: 기술 모델명(VLM/YOLO/SAM2) 미노출 — '이벤트 어노테이션'/'시계열 메타' 표기.

import { useEventAnnotation } from '@/features/label/hooks/useEventAnnotation';
import { useMeta } from '@/features/auto/hooks/useMeta';
import type {
  CaptionCandidate,
  EvidenceCandidate,
} from '@/features/label/api/eventAnnotation';
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

const LABEL_CLASS = 'text-[11px] text-gray-400';
const VALUE_CLASS = 'whitespace-pre-wrap break-words text-sm text-gray-200';

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
  const cot = (cand.cot ?? []).filter((s) => s.trim() !== '');
  return (
    <div className="mt-2 rounded border border-gray-700 p-2">
      <span className={LABEL_CLASS}>캡션 {ck}</span>
      {cand.caption_text != null && cand.caption_text.trim() !== '' && (
        <p className={VALUE_CLASS}>{cand.caption_text}</p>
      )}
      {cot.length > 0 && (
        <ol className="mt-1 list-decimal pl-4 text-sm text-gray-300">
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
    <div className="mt-2 rounded border border-gray-700 p-2">
      <span className={LABEL_CLASS}>근거 {ck}</span>
      {cand.evidence_text != null && cand.evidence_text.trim() !== '' && (
        <p className={VALUE_CLASS}>{cand.evidence_text}</p>
      )}
      {objId !== '' && (
        <p className="text-xs text-gray-400">
          객체 ID: <span className="text-gray-200">{objId}</span>
        </p>
      )}
      {objLabel !== '' && (
        <p className="text-xs text-gray-400">
          객체 라벨: <span className="text-gray-200">{objLabel}</span>
        </p>
      )}
      {objBbox !== '' && (
        <p className="text-xs text-gray-400">
          객체 좌표: <span className="text-gray-200">{objBbox}</span>
        </p>
      )}
      {frameId !== '' && (
        <p className="text-xs text-gray-400">
          프레임: <span className="text-gray-200">{frameId}</span>
        </p>
      )}
    </div>
  );
}

/** 시계열 메타 항목(metaVal + reviewStatus 배지)을 읽기 전용으로 렌더. */
function MetaItemReadonly({ item }: { item: MetaItem }) {
  const status = item.reviewStatus ?? null;
  return (
    <div className="mt-2 first:mt-0 rounded border border-gray-700 p-2">
      <div className="flex items-center justify-between gap-2">
        <span className={LABEL_CLASS}>{item.metaKey}</span>
        {status != null && status !== '' && (
          <span
            data-testid={`review-meta-ts-status-${item.metaSn}`}
            className="rounded bg-gray-700 px-1.5 py-0.5 text-[11px] text-gray-200"
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
 * 검수 화면 메타 읽기 표시 패널.
 *
 * event_annotation(현재 영상) + 시계열 메타(현재 프레임)를 등록값 그대로 표시한다.
 * 어느 쪽도 없으면(0건/미생성) 빈 상태 플레이스홀더를 노출하고 크래시하지 않는다.
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

  const isEmpty = !hasEventAnnotation && !hasMeta;

  return (
    <section
      className="border-t border-gray-700 p-3"
      aria-label="메타 정보"
      data-testid="review-meta-panel"
    >
      <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">
        메타 정보
      </h2>

      {isEmpty && (
        <p
          className="px-1 py-4 text-center text-xs text-gray-500"
          data-testid="review-meta-empty"
        >
          표시할 메타 정보가 없습니다.
        </p>
      )}

      {/* event_annotation 읽기 표시 */}
      {hasEventAnnotation && payload && (
        <div
          className="mb-3"
          aria-label="이벤트 어노테이션"
          data-testid="review-meta-event-annotation"
        >
          <div className="mb-1 flex items-center justify-between gap-2">
            <span className="text-[11px] font-semibold uppercase text-gray-400">
              이벤트 어노테이션
            </span>
            {eaStatus != null && eaStatus !== '' && (
              <span
                data-testid="review-meta-ea-status"
                className="rounded bg-gray-700 px-1.5 py-0.5 text-[11px] text-gray-200"
              >
                {REVIEW_STATUS_LABEL[eaStatus] ?? eaStatus}
              </span>
            )}
          </div>

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
              <span className="text-[11px] font-semibold uppercase text-gray-400">
                캡션 후보
              </span>
              {captionEntries.map(([ck, cand]) => (
                <CaptionReadonly key={ck} ck={ck} cand={cand} />
              ))}
            </div>
          )}

          {evidenceEntries.length > 0 && (
            <div className="mt-2">
              <span className="text-[11px] font-semibold uppercase text-gray-400">
                근거 후보
              </span>
              {evidenceEntries.map(([ck, cand]) => (
                <EvidenceReadonly key={ck} ck={ck} cand={cand} />
              ))}
            </div>
          )}
        </div>
      )}

      {/* 시계열 메타 읽기 표시 */}
      {hasMeta && (
        <div aria-label="시계열 메타" data-testid="review-meta-timeseries">
          <span className="text-[11px] font-semibold uppercase text-gray-400">
            시계열 메타
          </span>
          {metaItems.map((item) => (
            <MetaItemReadonly key={item.metaSn} item={item} />
          ))}
        </div>
      )}
    </section>
  );
}
