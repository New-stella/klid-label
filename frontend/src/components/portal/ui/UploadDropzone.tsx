/**
 * 업로드 드롭존 — **표면 전체가 조작 영역인 파일 받침**. [@design UI-131]
 *
 * <h3>왜 폼 필드형(`FileInput`)과 따로 두나</h3>
 * 관제 공통 `FileInput` 은 「라벨 + 입력 + 힌트 + 선택 요약」의 **폼 필드**다. 여기 필요한 것은
 * 끌어다 놓기와 눌러서 고르기를 **한 표면**이 함께 받는 받침이라 형태가 다르다(카탈로그가 이 둘을
 * 별개로 규정한다). 그리고 관제 컴포넌트는 고칠 수 없다(관제향 불변 구속).
 *
 * <h3>파일 입력을 숨기지 않는다 — 이것이 핵심이다</h3>
 * 숨김 입력 + 가짜 버튼 조합은 실제로 눌리는 영역이 **버튼만** 남는다. 44px 규칙을 만족해도 큰
 * 표면을 겨냥한 사용자의 클릭이 빗나간다. 여기서는 입력을 표면 전면에 **투명하게 깔아** 눌리는
 * 영역과 보이는 영역을 일치시킨다. 그 결과 포커스 링도 드롭존 전체에 그려진다.
 *
 * <h3>파선 표면의 뜻을 흔들지 않는다</h3>
 * 파선 표면(`gray-50` 배경 + 2px dashed `gray-400`)은 이 저장소에서 **아직 내용이 놓이지 않은
 * 자리**를 뜻하는 확정 관례다. 파일을 받는 빈 자리가 정확히 그 뜻이라 규격을 그대로 쓴다.
 * ⚠ **파선 변형(비활성 톤 등)을 만들지 않는다** — 만들면 파선이 화면마다 다른 뜻을 갖는다.
 * 그래서 「올리는 중」은 파선을 흐리는 대신 **실선 파일 표시로 자리를 바꾼다**(호출부 책임).
 *
 * <h3>대비</h3>
 * 파선 자체는 2.82:1 로 1.4.11 에 못 미친다. 컨트롤 식별은 안쪽 「파일 고르기」 경계(4.13:1)와
 * 안내 문구·아이콘이 담당하므로 취지는 충족한다 — 확정 관례라 파선 값을 올리지 않는다.
 *
 * @design DS-002
 * @design SCREEN-033
 */

import { useId, type ChangeEvent } from 'react';
import { Upload } from 'lucide-react';

import { cn } from '@/lib/cn';

interface UploadDropzoneProps {
  /** `<input accept>` 값. 1차 가드일 뿐 최종 검증은 서버가 한다. */
  accept?: string;
  multiple?: boolean;
  disabled?: boolean;
  /** 표면 안 안내 문구. */
  lead?: string;
  /** 표면 아래 정책 힌트(형식·용량·재개 가능 여부). */
  hint?: string;
  /** 이 받침을 설명하는 라벨 텍스트. 시각적으로도 보인다. */
  label: string;
  onFiles: (files: File[]) => void;
}

export function UploadDropzone({
  accept,
  multiple = false,
  disabled = false,
  lead = '여기로 파일을 끌어다 놓거나 눌러서 고르세요',
  hint,
  label,
  onFiles,
}: UploadDropzoneProps) {
  const inputId = useId();
  const hintId = `${inputId}-hint`;

  const handleChange = (e: ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(e.target.files ?? []);
    if (picked.length > 0) onFiles(picked);
    // 같은 파일을 다시 골라도 change 가 뜨도록 값을 비운다 — 이어서 올리기가 이 경로를 탄다.
    e.target.value = '';
  };

  return (
    <div className="flex flex-col gap-label-gap">
      <label htmlFor={inputId} className="text-label text-gray-800">
        {label}
      </label>

      {/*
        입력이 표면 전면을 덮으므로 포커스 링을 **감싸는 상자**에 그린다(`focus-within`).
        offset 을 음수로 두어 링이 파선 안쪽에 들어오게 한다 — 바깥에 그리면 카드 경계를 넘는다.
      */}
      <div
        className={cn(
          'relative flex min-h-[7.5rem] items-center justify-center',
          'rounded-tile border-2 border-dashed border-gray-400 bg-gray-50',
          'transition-colors duration-fast',
          'focus-within:outline focus-within:outline-[3px] focus-within:outline-offset-[-2px] focus-within:outline-primary-500',
          disabled ? 'opacity-60' : 'hover:border-gray-500 hover:bg-gray-100',
        )}
      >
        <div className="pointer-events-none flex flex-col items-center gap-inline px-in-component py-dense text-center">
          <Upload className="size-8 text-gray-400" strokeWidth={1.5} aria-hidden />
          <p className="text-body-sm text-pretty text-gray-700">{lead}</p>
          {/* 안쪽 상자는 바깥(12)보다 작은 8 — 중첩 모서리가 눌려 보이지 않게. */}
          <span className="rounded-input border border-gray-500 bg-white px-3 py-1.5 text-caption font-medium text-gray-700">
            파일 고르기
          </span>
        </div>

        <input
          id={inputId}
          type="file"
          accept={accept}
          multiple={multiple}
          disabled={disabled}
          aria-describedby={hint !== undefined ? hintId : undefined}
          onChange={handleChange}
          className={cn(
            'absolute inset-0 size-full opacity-0',
            disabled ? 'cursor-not-allowed' : 'cursor-pointer',
          )}
        />
      </div>

      {hint !== undefined && (
        <p id={hintId} className="text-caption text-gray-600">
          {hint}
        </p>
      )}
    </div>
  );
}
