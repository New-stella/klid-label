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

import { useEffect, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

import type { DetectShapeType } from '../api';
import { YOLO_CLASSES } from '../constants/yoloClasses';

/** AI Tool 실행 모드 — 일반(단일 프레임) / 트랙(후속 프레임 추적). */
export type AiToolMode = 'detect' | 'track';

export interface AiToolModalProps {
  open: boolean;
  onClose(): void;
  /**
   * 실행 확정. shape(박스/폴리곤) + 선택 classIds(빈=전체) + mode(일반/트랙).
   * @param shape    'BBOX'(박스) | 'POLYGON'(폴리곤)
   * @param classIds 선택된 COCO 영문 id 목록(빈 배열=전체 검출)
   * @param mode     'detect'(일반) | 'track'(트랙)
   */
  onConfirm(shape: DetectShapeType, classIds: string[], mode: AiToolMode): void;
  /** 트랙 실행 가능 여부(후속 프레임 없음/포털 등). false 면 트랙 버튼 비활성 + 안내. */
  canTrack?: boolean;
}

export function AiToolModal({ open, onClose, onConfirm, canTrack = true }: AiToolModalProps) {
  const [shape, setShape] = useState<DetectShapeType>('BBOX');
  const [selected, setSelected] = useState<Set<string>>(new Set());

  // 팝업이 새로 열릴 때마다 형태/선택 초기화(직전 상태 잔존 방지).
  useEffect(() => {
    if (open) {
      setShape('BBOX');
      setSelected(new Set());
    }
  }, [open]);

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

  const handleRun = (mode: AiToolMode) => {
    onConfirm(shape, selectedClassIds(), mode);
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
