// Phase 4 — YOLO 오토라벨 클래스 선택 팝업 (R3 AC3).
//
// YOLO 오토라벨 실행 전 검출 대상 클래스를 다중 선택한다.
// - 아무것도 선택하지 않고 실행하면 전체 검출(미필터, 하위호환).
// - 선택 시 해당 클래스(COCO 영문 id)만 BE→ai-server 로 전달되어 필터된다.
//
// 접근성: Modal 이 포커스 트랩·ESC 닫기·포커스 복귀를 제공. 체크박스는 label 연결(htmlFor).

import { useEffect, useState } from 'react';

import { Button } from '@/components/common/Button';
import { Modal } from '@/components/common/Modal';

import { YOLO_CLASSES } from '../constants/yoloClasses';

export interface AutolabelClassModalProps {
  open: boolean;
  onClose(): void;
  /**
   * 실행 확정. classIds 는 선택된 COCO 영문 id 목록.
   * 빈 배열이면 '전체 검출'을 의미한다(호출 측이 body 없이 요청).
   */
  onConfirm(classIds: string[]): void;
}

export function AutolabelClassModal({ open, onClose, onConfirm }: AutolabelClassModalProps) {
  const [selected, setSelected] = useState<Set<string>>(new Set());

  // 팝업이 새로 열릴 때마다 선택 초기화(직전 선택 잔존 방지).
  useEffect(() => {
    if (open) setSelected(new Set());
  }, [open]);

  const toggle = (id: string) => {
    // 불변성 — 새 Set 생성.
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleRun = () => {
    // 선택 순서를 YOLO_CLASSES 정의 순서로 안정화하여 전달.
    onConfirm(YOLO_CLASSES.filter((c) => selected.has(c.id)).map((c) => c.id));
  };

  const allCount = YOLO_CLASSES.length;
  const selectedCount = selected.size;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="AI 탐지 클래스 선택"
      description="검출할 객체 종류를 선택하세요. 아무것도 선택하지 않으면 전체 클래스를 검출합니다."
      size="sm"
    >
      <fieldset className="flex flex-col gap-2">
        <legend className="sr-only">검출 대상 클래스</legend>
        {YOLO_CLASSES.map((c) => {
          const inputId = `autolabel-class-${c.id}`;
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
          <Button variant="primary" onClick={handleRun}>
            실행
          </Button>
        </div>
      </div>
    </Modal>
  );
}
