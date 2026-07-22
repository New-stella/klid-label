// Phase 4 — AI Tool 팝업 (매뉴얼 "AI Tool" 디자인).
//
// 구성: 형태(박스/폴리곤 라디오, 기본 박스) + 라벨 선택(클래스 다중선택) + [일반]/[트랙] 실행 버튼.
//  - 일반  : 단일 프레임 검출/분할 (박스=AI 탐지, 폴리곤=AI 분할)
//  - 트랙  : 후속 프레임 자동 추적 (AI 추적)
//
// 용어 정책(MED #13): 사용자 노출 문구에 모델명(YOLO/SAM/SAM2) 금지 — "박스/폴리곤",
//                     "AI 탐지/AI 분할/AI 추적" 만 사용한다.
// 보안: shape/mode 는 화이트리스트 값만 사용. 라벨명은 React 가 자동 escape(XSS 방어).
// 접근성: Modal 이 포커스 트랩·ESC 닫기·포커스 복귀 제공. 라디오/체크박스는 label 연결(htmlFor).

import { useEffect, useRef, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

import type { DetectShapeType } from '../api';
import { YOLO_CLASSES } from '../constants/yoloClasses';

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
   * @param classIds 선택된 COCO 영문 id 목록(빈 배열=전체 검출)
   * @param mode     'detect'(일반) | 'track'(트랙)
   * @param opts     (detect 전용) 조절된 정밀도 옵션. 미조절이면 미전달(생략) — 무회귀.
   */
  onConfirm(shape: DetectShapeType, classIds: string[], mode: AiToolMode, opts?: AiToolOpts): void;
  /** 트랙 실행 가능 여부(후속 프레임 없음/포털 등). false 면 트랙 버튼 비활성 + 안내. */
  canTrack?: boolean;
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
   * "즉시 그리기" 토글 상태(controlled). true 면 AI 분할 클릭마다 미리보기가 즉시 그려진다.
   * 미지정 시 OFF(false). 상위(LabelingPage)가 값과 변경 콜백을 함께 소유한다.
   */
  immediateDraw?: boolean;
  /** "즉시 그리기" 토글 변경 콜백. */
  onImmediateDrawChange?: (value: boolean) => void;
}

export function AiToolModal({
  open,
  onClose,
  onConfirm,
  canTrack = true,
  immediateDraw = false,
  onImmediateDrawChange,
  defaultConfThreshold,
  defaultSimplifyTolerance,
}: AiToolModalProps) {
  const [shape, setShape] = useState<DetectShapeType>('BBOX');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  // 정밀도 슬라이더 — 프리필(시스템 설정)로 초기화. 사용자가 조절하면 touched=true 로 표시하고
  // 그 값만 onConfirm 옵션에 실어 보낸다(미조절이면 생략 → BE 기본값, 무회귀).
  const [confThreshold, setConfThreshold] = useState(defaultConfThreshold ?? SENSITIVITY_DEFAULT);
  const [simplifyTolerance, setSimplifyTolerance] = useState(
    defaultSimplifyTolerance ?? TOLERANCE_DEFAULT,
  );
  const confTouchedRef = useRef(false);
  const tolTouchedRef = useRef(false);

  // 팝업이 새로 열릴 때마다 형태/선택/슬라이더를 프리필로 초기화(직전 상태·조절 잔존 방지).
  // 프리필 prop 변화(useConfigs 도착)도 함께 반영하려 deps 에 포함하나, 조절 플래그도 리셋되는 것은
  // "새로 열림 = 새 조절 세션" 의도와 일치한다(모달은 닫혔다 열릴 때만 재초기화됨).
  useEffect(() => {
    if (!open) return;
    setShape('BBOX');
    setSelected(new Set());
    confTouchedRef.current = false;
    tolTouchedRef.current = false;
    setConfThreshold(defaultConfThreshold ?? SENSITIVITY_DEFAULT);
    setSimplifyTolerance(defaultSimplifyTolerance ?? TOLERANCE_DEFAULT);
  }, [open, defaultConfThreshold, defaultSimplifyTolerance]);

  const toggle = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev); // 불변성 — 새 Set 생성.
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const selectedClassIds = () =>
    // 선택 순서를 YOLO_CLASSES 정의 순서로 안정화하여 전달.
    YOLO_CLASSES.filter((c) => selected.has(c.id)).map((c) => c.id);

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

  const allCount = YOLO_CLASSES.length;
  const selectedCount = selected.size;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="AI Tool"
      description="형태와 대상 라벨을 선택한 뒤 실행 방식을 고르세요. 라벨을 선택하지 않으면 전체 라벨을 대상으로 합니다."
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

      {/* 라벨 선택 — 클래스 다중선택. */}
      <fieldset className="flex max-h-56 flex-col gap-2 overflow-y-auto">
        <legend className="mb-1 text-sub font-semibold text-gray-700">라벨</legend>
        {YOLO_CLASSES.map((c) => {
          const inputId = `ai-tool-class-${c.id}`;
          return (
            <label
              key={c.id}
              htmlFor={inputId}
              className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 hover:bg-gray-100"
            >
              <input
                id={inputId}
                type="checkbox"
                className="h-4 w-4"
                checked={selected.has(c.id)}
                onChange={() => toggle(c.id)}
              />
              <span className="text-body text-gray-900">{c.label}</span>
            </label>
          );
        })}
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

      {/* 즉시 그리기 토글 — ON 이면 AI 분할 클릭마다 미리보기가 즉시 그려진다(모델명 비노출 정책). */}
      <div className="mt-4 flex flex-col gap-1">
        <label htmlFor="ai-tool-immediate" className="flex cursor-pointer items-center gap-2">
          <input
            id="ai-tool-immediate"
            type="checkbox"
            className="h-4 w-4"
            checked={immediateDraw}
            onChange={(e) => onImmediateDrawChange?.(e.target.checked)}
          />
          <span className="text-body text-gray-900">즉시 그리기</span>
        </label>
        <p className="pl-6 text-[11px] text-gray-400">클릭할 때마다 미리보기가 그려집니다.</p>
      </div>

      <div className="mt-6 flex items-center justify-between">
        <span className="text-sub text-gray-500" aria-live="polite">
          {selectedCount === 0 ? `전체 (${allCount}종)` : `${selectedCount}종 선택`}
        </span>
        <div className="flex gap-2">
          <Button variant="outline" onClick={onClose}>
            취소
          </Button>
          <Button variant="outline" onClick={() => handleRun('detect')}>
            일반
          </Button>
          <Button variant="primary" onClick={() => handleRun('track')} disabled={!canTrack}>
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
