import { forwardRef, useId, type InputHTMLAttributes } from 'react';
import { AlertCircle } from 'lucide-react';

import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

export interface FileInputProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label?: string;
  error?: string;
  /** 허용 확장자·용량 안내 등 보조 문구. `aria-describedby` 로 연결된다. */
  hint?: string;
  hideLabel?: boolean;
  /** 선택된 파일 요약(파일명 + MB)을 입력 아래에 표시한다. `null` 이면 표시하지 않는다. */
  selectedFile?: File | null;
  /** 선택 파일 요약 요소의 `data-testid`. */
  selectedFileTestId?: string;
}

/** 바이트 → MB 표기(소수 2자리) — 파일 선택 요약 공통 포맷. */
function toMegabytes(bytes: number): string {
  return (bytes / (1024 * 1024)).toFixed(2);
}

/**
 * 파일 선택 입력 (KRDS 정합) — `Input`/`Select` 와 같은 골격의 공통 컴포넌트.
 *
 * `<input type="file">` 의 `file:*` 유틸리티 문자열이 업로드 화면마다 복붙돼 한쪽만 바뀌면
 * 조용히 갈라지던 것을 여기 한 곳으로 모은다. 선택 파일 요약(파일명·MB)도 호출처마다 같은
 * 계산식을 복제하고 있어 함께 흡수했다.
 *
 * 보안: `accept` 는 UX 보조 가드일 뿐 신뢰 경계가 아니다 — 확장자·MIME 본 검증은 서버가 한다.
 */
export const FileInput = forwardRef<HTMLInputElement, FileInputProps>(
  function FileInput(
    {
      label,
      error,
      hint,
      hideLabel,
      selectedFile,
      selectedFileTestId,
      id,
      className,
      ...rest
    },
    ref,
  ) {
    const autoId = useId();
    const inputId = id ?? autoId;
    const errorId = error ? `${inputId}-error` : undefined;
    const hintId = hint && !error ? `${inputId}-hint` : undefined;
    const describedBy = errorId ?? hintId;

    return (
      <div className="flex flex-col gap-1">
        {label && (
          <label
            htmlFor={inputId}
            className={cn(
              'text-body font-medium text-gray-700',
              hideLabel && 'sr-only',
            )}
          >
            {label}
          </label>
        )}
        <input
          ref={ref}
          id={inputId}
          type="file"
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={cn(
            'text-body text-gray-700 disabled:opacity-60',
            // 파일 선택 버튼 — ladder `button`(17px/w500). 크기는 구 `file:text-sm` 과 동일.
            'file:mr-3 file:rounded-md file:border-0 file:bg-primary-50 file:px-3 file:py-1.5 file:text-button file:font-medium file:text-primary-700 hover:file:bg-primary-100',
            KRDS_FOCUS,
            className,
          )}
          {...rest}
        />
        {error ? (
          <span
            id={errorId}
            role="alert"
            className="flex items-center gap-1 text-sub text-danger"
          >
            <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            {error}
          </span>
        ) : hint ? (
          <span id={hintId} className="text-sub text-gray-500">
            {hint}
          </span>
        ) : null}
        {selectedFile && (
          <span data-testid={selectedFileTestId} className="text-sub text-gray-700">
            선택: {selectedFile.name} ({toMegabytes(selectedFile.size)} MB)
          </span>
        )}
      </div>
    );
  },
);

export default FileInput;
