// Phase 4 — 'AI 탐지' 대상·정밀도 다이얼로그 (SCREEN-005 §AI 탐지 대상·정밀도 다이얼로그).
//
// ★제목은 "AI 탐지"다 — 영문 "AI Tool" 은 사용자 노출 문구 규칙(모델명·영문 기술어 금지)과
//   사양 양쪽에 어긋난다. 레이아웃은 좌우 분리(좌: 형태+대상 라벨 / 우: 정밀도)다.
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

import { Field, FieldLabel } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import { Modal } from '@/components/common/Modal';
import { Radio } from '@/components/common/Radio';
import { cn } from '@/lib/cn';

import type { DetectShapeType } from '../api';
import type { DetectCandidate } from '../api/labelMaster';
import { resolveLabelDisplayName } from '../utils/labelDisplayName';

import {
  SENSITIVITY_DEFAULT,
  SensitivitySlider,
  TOLERANCE_DEFAULT,
  ToleranceSlider,
} from './PrecisionSliders';

/** AI Tool 실행 모드 — 일반(단일 프레임) / 트랙(후속 프레임 추적). */
export type AiToolMode = 'detect' | 'track';

/** 선택지 라벨 글자색 — 공통 Radio/Checkbox 의 기본 라벨색(gray-700)보다 진하게 유지한다. */
const SHAPE_LABEL_CLASS = 'text-body text-gray-900';

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
  /**
   * 실행 차단(장시간 작업 진행 중) — 실행 버튼과 정밀도 슬라이더를 비활성화한다.
   * 눌러도 배타 실행에 거부될 뿐인 버튼을 활성처럼 보이게 두지 않는다.
   */
  disabled?: boolean;
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
  disabled = false,
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

  // 라벨 목록이 실제로 넘치는지 — 넘칠 때만 스크롤 단서를 노출한다(항상 띄우면 거짓 안내).
  const labelListRef = useRef<HTMLDivElement>(null);
  const [labelListScrollable, setLabelListScrollable] = useState(false);
  const updateLabelListScrollState = () => {
    const el = labelListRef.current;
    setLabelListScrollable(el !== null && el.scrollHeight > el.clientHeight + 1);
  };
  useEffect(updateLabelListScrollState, [open, candidates, candidatesLoading, candidatesError]);

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
    mappedCandidates.filter((c) => selected.has(c.labelId)).map((c) => c.dtctTypeCd as string);

  // 조절된 값만 옵션으로 수집. 경계 세밀함은 폴리곤 형태일 때만 유효(BBOX 무의미).
  const buildOpts = (): AiToolOpts | undefined => {
    const opts: AiToolOpts = {};
    if (confTouchedRef.current) opts.confThreshold = confThreshold;
    if (tolTouchedRef.current && shape === 'POLYGON') opts.simplifyTolerance = simplifyTolerance;
    return Object.keys(opts).length > 0 ? opts : undefined;
  };

  const handleRun = (mode: AiToolMode) => {
    if (disabled) return;
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
      title="AI 탐지"
      description="형태와 대상 라벨을 선택한 뒤 실행 방식을 고르세요. 라벨을 선택하지 않으면 매핑된 전체 라벨을 대상으로 합니다."
      size="lg"
    >
      {/* ★좌우 분리 배치(사양) — 좌: 검출 형태 + 대상 라벨 / 우: 정밀도 조절.
          좁은 폭에서는 1열로 접힌다(세로 순차 배치는 좁은 화면 전용 폴백이다).
          ★열 비율 3:2 (2026-08-08) — 라벨 목록이 넓은 쪽을 쓰도록 한다.
          ★브레이크포인트는 `md:` 다 — tailwind.config 가 screens 를 md/xl 로 **교체**해
            `sm:` 접두 클래스는 아예 생성되지 않는다. 구 `sm:grid-cols-2` 는 한 번도 적용된 적이
            없어 좌우 분리가 성립하지 않았고, 그래서 넓힌 폭의 우측이 통째로 비어 있었다(실측). */}
      <div className="grid grid-cols-1 gap-6 md:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <div className="flex min-w-0 flex-col">
      {/* 형태 선택 — 박스 / 폴리곤 (라디오). 기본 박스. */}
      <fieldset className="mb-4 flex flex-col gap-2">
        <legend className="mb-1 text-sub font-semibold text-gray-700">형태</legend>
        <div className="flex gap-4">
          <Radio
            id="ai-tool-shape-bbox"
            name="ai-tool-shape"
            label={<span className={SHAPE_LABEL_CLASS}>박스</span>}
            checked={shape === 'BBOX'}
            onChange={() => setShape('BBOX')}
          />
          <Radio
            id="ai-tool-shape-polygon"
            name="ai-tool-shape"
            label={<span className={SHAPE_LABEL_CLASS}>폴리곤</span>}
            checked={shape === 'POLYGON'}
            onChange={() => setShape('POLYGON')}
          />
        </div>
      </fieldset>

      {/* 라벨 선택 — 마스터 라벨 후보(매핑 여부). 매핑만 선택 가능, 미매핑은 disabled + 안내.
          ★스크롤 상자는 legend 바깥의 별도 div 다 — fieldset 자체에 overflow 를 걸면 항목과 함께
            '라벨' 제목까지 함께 스크롤돼 무엇을 고르는 목록인지 사라진다. */}
      <fieldset className="flex min-w-0 flex-col gap-2">
        <legend className="mb-1 text-sub font-semibold text-gray-700">라벨</legend>

        <div
          ref={labelListRef}
          data-testid="ai-tool-label-list"
          onScroll={updateLabelListScrollState}
          // 상한은 뷰포트 기준 — 고정 px 이면 낮은 해상도에서 모달이 화면을 넘긴다.
          className="max-h-[min(256px,34vh)] min-w-0 overflow-y-auto overscroll-contain"
        >
        {candidatesLoading ? (
          <p className="px-2 py-3 text-sub text-gray-500" aria-live="polite">
            라벨 목록을 불러오는 중입니다…
          </p>
        ) : candidatesError ? (
          <div
            className="flex flex-col items-start gap-2 rounded-md bg-danger/5 px-3 py-3"
            role="alert"
          >
            <p className="text-sub text-danger-700">라벨 목록을 불러오지 못했습니다.</p>
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
                AI 검출 클래스가 매핑된 라벨이 없습니다. 라벨 관리에서 AI 검출 클래스를 매핑해
                주세요.
              </p>
            )}
            {/* ★다열 배치 — 넓어진 모달 폭을 실제로 쓴다. 1열이면 같은 높이에서 보이는 라벨이
                절반 이하라 나머지가 스크롤 뒤에 숨는다(실측: 9종 중 4종만 노출). */}
            <div className="grid grid-cols-1 gap-x-4 gap-y-0.5 md:grid-cols-2">
            {candidates.map((c) => {
              const inputId = `ai-tool-class-${c.labelId}`;
              const disabled = !c.mapped;
              return (
                // '미매핑' 뱃지는 행 우측 끝(ml-auto)에 붙어야 해 공통 Checkbox 의 라벨 안이 아니라
                // 형제로 둔다. 체크박스 라벨 연결(implicit label)은 그대로 유지된다.
                <div
                  key={c.labelId}
                  className={cn(
                    'flex items-center gap-2 rounded-md px-2',
                    disabled ? 'cursor-not-allowed opacity-60' : 'hover:bg-gray-100',
                  )}
                >
                  <Field orientation="horizontal">
                    <Checkbox
                      id={inputId}
                      checked={selected.has(c.labelId)}
                      disabled={disabled}
                      onCheckedChange={() => toggle(c.labelId)}
                    />
                    {/* 마스터 등록명 그대로 — 전송값은 labelId 라 표시명과 무관하다. */}
                    <FieldLabel className={SHAPE_LABEL_CLASS}>
                      {resolveLabelDisplayName(c.name)}
                    </FieldLabel>
                  </Field>
                  {disabled && <span className="ml-auto text-[11px] text-gray-400">미매핑</span>}
                </div>
              );
            })}
            </div>
          </>
        )}
        </div>
        {/* 스크롤 단서 — 잘린 목록에 더 있다는 사실을 알리지 않으면 라벨이 그것뿐인 줄 오인한다.
            (macOS 오버레이 스크롤바는 정지 상태에서 보이지 않아 시각 단서가 되지 못한다.) */}
        {labelListScrollable && (
          <p className="text-[11px] text-gray-500" aria-live="polite">
            목록을 스크롤하면 라벨이 더 있습니다.
          </p>
        )}
      </fieldset>
        </div>

      {/* 우측 열 — 정밀도 조절. AI 탐지(일반) 실행 직전 조절. 인식 민감도(항상) + 경계 세밀함(폴리곤만).
          미조절 시 요청에 미포함 → 시스템 설정 기본값 사용(무회귀). */}
      <div className="flex flex-col gap-3 border-t border-gray-100 pt-4 md:border-l md:border-t-0 md:pl-6 md:pt-0">
        <p className="text-sub font-semibold text-gray-700">정밀도</p>
        <SensitivitySlider
          id="ai-tool-sensitivity"
          value={confThreshold}
          disabled={disabled}
          onChange={handleConfChange}
        />
        {shape === 'POLYGON' && (
          <ToleranceSlider
            id="ai-tool-tolerance"
            value={simplifyTolerance}
            disabled={disabled}
            onChange={handleTolChange}
          />
        )}
      </div>
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
          <Button
            variant="outline"
            onClick={() => handleRun('detect')}
            disabled={disabled || !canRun}
          >
            일반
          </Button>
          <Button
            variant="primary"
            onClick={() => handleRun('track')}
            disabled={disabled || !canTrack || !canRun}
          >
            트랙
          </Button>
        </div>
      </div>
      {disabled && (
        <p className="mt-2 text-right text-[11px] text-gray-400" aria-live="polite">
          다른 작업이 진행 중이라 지금은 실행할 수 없습니다.
        </p>
      )}
      {!canTrack && (
        <p className="mt-2 text-right text-[11px] text-gray-400" aria-live="polite">
          후속 프레임이 없어 추적할 수 없습니다.
        </p>
      )}
    </Modal>
  );
}
