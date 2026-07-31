// Phase 4 — AI Tool 팝업 (매뉴얼 "AI Tool" 디자인).
//
// 구성: 형태(박스/폴리곤 라디오, 기본 박스) + 라벨 선택(마스터 라벨 다중선택) + [일반]/[트랙] 실행 버튼.
//  - 일반  : 단일 프레임 검출/분할 (박스=AI 탐지, 폴리곤=AI 분할)
//  - 트랙  : 후속 프레임 자동 추적 (AI 추적)
//
// 라벨 후보(★Phase COCO 매핑): 하드코딩 6종을 폐기하고 BE 후보 조회(DetectCandidate = 활성 라벨
//   마스터 + COCO 매핑 여부)를 부모(LabelingPage)가 주입한다. 매핑된 라벨만 선택 가능(체크박스 활성),
//   미매핑 라벨은 표시하되 선택 불가(disabled) + 안내. 전송값은 매핑 라벨의 COCO 클래스(dtctTypeCd)이며,
//   BE 가 신뢰 경계에서 '매핑된 라벨→COCO'로 재구성·재검증한다(FE 값 그대로 신뢰 안 함).
//
// 용어 정책(MED #13): 사용자 노출 문구에 모델명(YOLO/SAM/SAM2) 금지 — "박스/폴리곤",
//                     "AI 탐지/AI 분할/AI 추적" 만 사용한다.
// 보안: shape/mode 는 화이트리스트 값만 사용. 라벨명은 React 가 자동 escape(XSS 방어).
// 접근성: Modal 이 포커스 트랩·ESC 닫기·포커스 복귀 제공. 라디오/체크박스는 label 연결(htmlFor).

import { useEffect, useRef, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

import type { DetectShapeType } from '../api';
import type { DetectCandidate } from '../api/labelMaster';

import {
  SENSITIVITY_DEFAULT,
  SensitivitySlider,
  TOLERANCE_DEFAULT,
  ToleranceSlider,
} from './PrecisionSliders';

/** AI Tool 실행 모드 — 일반(단일 프레임) / 트랙(후속 프레임 추적). */
export type AiToolMode = 'detect' | 'track';

/**
 * AI 탐지(일반) 실행 시 조절된 정밀도 옵션. 사용자가 슬라이더를 건드린 값만 담긴다.
 * 미조절 값은 키 자체를 생략해 BE 가 시스템 설정 기본값을 쓰게 한다(무회귀).
 * - confThreshold      : 인식 민감도 0.25~0.80
 * - simplifyTolerance  : 경계 세밀함 0~50 (shape=POLYGON 일 때만)
 */
export interface AiToolOpts {
  confThreshold?: number;
  simplifyTolerance?: number;
}

export interface AiToolModalProps {
  open: boolean;
  onClose(): void;
  /**
   * 실행 확정. shape(박스/폴리곤) + 선택 classIds(빈=전체) + mode(일반/트랙).
   * @param shape    'BBOX'(박스) | 'POLYGON'(폴리곤)
   * @param classIds 선택된 COCO 검출 클래스(dtctTypeCd) 목록(빈 배열=전체 검출)
   * @param mode     'detect'(일반) | 'track'(트랙)
   * @param opts     (detect 전용) 조절된 정밀도 옵션. 미조절이면 미전달(생략) — 무회귀.
   */
  onConfirm(shape: DetectShapeType, classIds: string[], mode: AiToolMode, opts?: AiToolOpts): void;
  /** 트랙 실행 가능 여부(후속 프레임 없음/포털 등). false 면 트랙 버튼 비활성 + 안내. */
  canTrack?: boolean;
  /**
   * AI 탐지 후보(활성 라벨 마스터 + COCO 매핑 여부) — 부모가 useDetectCandidates 로 주입.
   * 매핑된 라벨만 선택 가능하며, 미매핑은 표시하되 선택 불가. 미지정 시 빈 목록으로 처리.
   */
  candidates?: DetectCandidate[];
  /** 후보 조회 로딩 상태 — true 면 목록 대신 로딩 안내. */
  candidatesLoading?: boolean;
  /** 후보 조회 실패 상태 — true 면 목록 대신 에러 안내 + 재시도 버튼. */
  candidatesError?: boolean;
  /** 후보 조회 재시도(refetch). candidatesError 안내의 '다시 시도' 버튼이 호출한다. */
  onRetryCandidates?: () => void;
  /**
   * 인식 민감도 프리필 값(0.25~0.80) — 시스템 설정 YOLO_CONF_THRESHOLD/100.
   * 미지정(로딩/실패) 시 코드 상수로 폴백. 사용자가 조절하지 않으면 요청에 미포함.
   */
  defaultConfThreshold?: number;
  /**
   * 경계 세밀함 프리필 값(0~50) — 시스템 설정 POLYGON_SIMPLIFY_TOLERANCE.
   * 미지정(로딩/실패) 시 코드 상수로 폴백. 사용자가 조절하지 않으면 요청에 미포함.
   */
  defaultSimplifyTolerance?: number;
}

export function AiToolModal({
  open,
  onClose,
  onConfirm,
  canTrack = true,
  candidates = [],
  candidatesLoading = false,
  candidatesError = false,
  onRetryCandidates,
  defaultConfThreshold,
  defaultSimplifyTolerance,
}: AiToolModalProps) {
  const [shape, setShape] = useState<DetectShapeType>('BBOX');
  // 선택은 라벨 마스터 PK(labelId) 기준 — 매핑된 라벨만 선택 대상이 된다.
  const [selected, setSelected] = useState<Set<number>>(new Set());
  // 정밀도 슬라이더 — 프리필(시스템 설정)로 초기화. 사용자가 조절하면 touched=true 로 표시하고
  // 그 값만 onConfirm 옵션에 실어 보낸다(미조절이면 생략 → BE 기본값, 무회귀).
  const [confThreshold, setConfThreshold] = useState(defaultConfThreshold ?? SENSITIVITY_DEFAULT);
  const [simplifyTolerance, setSimplifyTolerance] = useState(
    defaultSimplifyTolerance ?? TOLERANCE_DEFAULT,
  );
  const confTouchedRef = useRef(false);
  const tolTouchedRef = useRef(false);
  // 직전 open 값 추적 — false→true 전이(새로 열림)를 감지한다.
  const prevOpenRef = useRef(false);
  // 프리필 prop 최신값을 전이 시점에 읽기 위한 ref(값 변화만으로 재초기화가 트리거되지 않도록 deps 제외).
  const defaultConfRef = useRef(defaultConfThreshold);
  const defaultTolRef = useRef(defaultSimplifyTolerance);
  defaultConfRef.current = defaultConfThreshold;
  defaultTolRef.current = defaultSimplifyTolerance;

  // 팝업이 "새로 열리는 시점"(open false→true 전이)에만 형태/선택/슬라이더를 프리필로 초기화한다.
  // 프리필 prop(useConfigs)이 모달이 열려 있는 동안 뒤늦게 도착해도 재초기화하지 않는다 —
  // 그 경우 사용자가 이미 조작한 shape/selected/슬라이더/touched 가 조용히 리셋되는 결함을 막는다.
  // 전이 시점에는 ref 로 최신 default 값을 읽어 프리필하되, 아직 미도착(undefined)이면 상수 폴백.
  useEffect(() => {
    const opened = open && !prevOpenRef.current;
    prevOpenRef.current = open;
    if (!opened) return;
    setShape('BBOX');
    setSelected(new Set());
    confTouchedRef.current = false;
    tolTouchedRef.current = false;
    setConfThreshold(defaultConfRef.current ?? SENSITIVITY_DEFAULT);
    setSimplifyTolerance(defaultTolRef.current ?? TOLERANCE_DEFAULT);
  }, [open]);

  const toggle = (labelId: number) => {
    setSelected((prev) => {
      const next = new Set(prev); // 불변성 — 새 Set 생성.
      if (next.has(labelId)) next.delete(labelId);
      else next.add(labelId);
      return next;
    });
  };

  // 매핑된 후보만 실제 검출 대상 — 미매핑은 표시하되 선택 불가.
  const mappedCandidates = candidates.filter((c) => c.mapped && c.dtctTypeCd);
  const mappedCount = mappedCandidates.length;

  const selectedClassIds = () =>
    // 선택된 라벨(매핑된 것만) → COCO 클래스(dtctTypeCd). 후보 정의 순서로 안정화하여 전달.
    mappedCandidates
      .filter((c) => selected.has(c.labelId))
      .map((c) => c.dtctTypeCd as string);

  // 조절된 값만 옵션으로 수집. 경계 세밀함은 폴리곤 형태일 때만 유효(BBOX 무의미).
  const buildOpts = (): AiToolOpts | undefined => {
    const opts: AiToolOpts = {};
    if (confTouchedRef.current) opts.confThreshold = confThreshold;
    if (tolTouchedRef.current && shape === 'POLYGON') opts.simplifyTolerance = simplifyTolerance;
    return Object.keys(opts).length > 0 ? opts : undefined;
  };

  const handleRun = (mode: AiToolMode) => {
    const ids = selectedClassIds();
    // detect 만 정밀도 옵션 대상(트랙은 이번 범위 제외). 미조절이면 인자 자체를 생략해 무회귀.
    const opts = mode === 'detect' ? buildOpts() : undefined;
    if (opts) onConfirm(shape, ids, mode, opts);
    else onConfirm(shape, ids, mode);
  };

  const handleConfChange = (v: number) => {
    confTouchedRef.current = true;
    setConfThreshold(v);
  };
  const handleTolChange = (v: number) => {
    tolTouchedRef.current = true;
    setSimplifyTolerance(v);
  };

  const selectedCount = selected.size;
  // 실행 가능 여부 — 매핑된 라벨이 하나도 없으면(전부 미매핑/빈 목록) 검출 대상이 없으므로 실행 차단.
  const canRun = mappedCount > 0;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="AI Tool"
      description="형태와 대상 라벨을 선택한 뒤 실행 방식을 고르세요. 라벨을 선택하지 않으면 매핑된 전체 라벨을 대상으로 합니다."
      size="sm"
    >
      {/* 형태 선택 — 박스 / 폴리곤 (라디오). 기본 박스. */}
      <fieldset className="mb-4 flex flex-col gap-2">
        <legend className="mb-1 text-sub font-semibold text-gray-700">형태</legend>
        <div className="flex gap-4">
          <label htmlFor="ai-tool-shape-bbox" className="flex cursor-pointer items-center gap-2">
            <input
              id="ai-tool-shape-bbox"
              type="radio"
              name="ai-tool-shape"
              className="h-4 w-4"
              checked={shape === 'BBOX'}
              onChange={() => setShape('BBOX')}
            />
            <span className="text-body text-gray-900">박스</span>
          </label>
          <label htmlFor="ai-tool-shape-polygon" className="flex cursor-pointer items-center gap-2">
            <input
              id="ai-tool-shape-polygon"
              type="radio"
              name="ai-tool-shape"
              className="h-4 w-4"
              checked={shape === 'POLYGON'}
              onChange={() => setShape('POLYGON')}
            />
            <span className="text-body text-gray-900">폴리곤</span>
          </label>
        </div>
      </fieldset>

      {/* 라벨 선택 — 마스터 라벨 후보(매핑 여부). 매핑만 선택 가능, 미매핑은 disabled + 안내. */}
      <fieldset className="flex max-h-56 flex-col gap-2 overflow-y-auto">
        <legend className="mb-1 text-sub font-semibold text-gray-700">라벨</legend>

        {candidatesLoading ? (
          <p className="px-2 py-3 text-sub text-gray-500" aria-live="polite">
            라벨 목록을 불러오는 중입니다…
          </p>
        ) : candidatesError ? (
          <div className="flex flex-col items-start gap-2 rounded-md bg-danger/5 px-3 py-3" role="alert">
            <p className="text-sub text-danger">라벨 목록을 불러오지 못했습니다.</p>
            <Button variant="outline" size="sm" onClick={() => onRetryCandidates?.()}>
              다시 시도
            </Button>
          </div>
        ) : candidates.length === 0 ? (
          <p className="px-2 py-3 text-sub text-gray-500">
            등록된 라벨이 없습니다. 라벨 관리에서 라벨을 먼저 등록해 주세요.
          </p>
        ) : (
          <>
            {mappedCount === 0 && (
              <p className="px-2 pb-1 text-[11px] text-gray-500" aria-live="polite">
                AI 검출 클래스가 매핑된 라벨이 없습니다. 라벨 관리에서 AI 검출 클래스를 매핑해 주세요.
              </p>
            )}
            {candidates.map((c) => {
              const inputId = `ai-tool-class-${c.labelId}`;
              const disabled = !c.mapped;
              return (
                <label
                  key={c.labelId}
                  htmlFor={inputId}
                  className={`flex items-center gap-2 rounded-md px-2 py-1.5 ${
                    disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer hover:bg-gray-100'
                  }`}
                >
                  <input
                    id={inputId}
                    type="checkbox"
                    className="h-4 w-4"
                    checked={selected.has(c.labelId)}
                    disabled={disabled}
                    onChange={() => toggle(c.labelId)}
                  />
                  <span className="text-body text-gray-900">{c.name}</span>
                  {disabled && (
                    <span className="ml-auto text-[11px] text-gray-400">미매핑</span>
                  )}
                </label>
              );
            })}
          </>
        )}
      </fieldset>

      {/* 정밀도 조절 — AI 탐지(일반) 실행 직전 조절. 인식 민감도(항상) + 경계 세밀함(폴리곤만).
          미조절 시 요청에 미포함 → 시스템 설정 기본값 사용(무회귀). */}
      <div className="mt-4 flex flex-col gap-3 border-t border-gray-100 pt-4">
        <SensitivitySlider
          id="ai-tool-sensitivity"
          value={confThreshold}
          onChange={handleConfChange}
        />
        {shape === 'POLYGON' && (
          <ToleranceSlider
            id="ai-tool-tolerance"
            value={simplifyTolerance}
            onChange={handleTolChange}
          />
        )}
      </div>

      {/* "즉시 그리기" 토글은 이 팝업에 두지 않는다 — 그 옵션은 AI 분할(SAM_SEGMENT) 도구의
          클릭 프리뷰에만 효력이 있어, 실제 사용 지점인 우측 객체 속성 패널의
          "AI 분할 정밀도" 섹션(ObjectAttributePanel)으로 이동했다. */}

      <div className="mt-6 flex items-center justify-between">
        <span className="text-sub text-gray-500" aria-live="polite">
          {selectedCount === 0 ? `전체 (${mappedCount}종)` : `${selectedCount}종 선택`}
        </span>
        <div className="flex gap-2">
          <Button variant="outline" onClick={onClose}>
            취소
          </Button>
          <Button variant="outline" onClick={() => handleRun('detect')} disabled={!canRun}>
            일반
          </Button>
          <Button variant="primary" onClick={() => handleRun('track')} disabled={!canTrack || !canRun}>
            트랙
          </Button>
        </div>
      </div>
      {!canTrack && (
        <p className="mt-2 text-right text-[11px] text-gray-400" aria-live="polite">
          후속 프레임이 없어 추적할 수 없습니다.
        </p>
      )}
    </Modal>
  );
}
