// Phase 5 — 포털 이미지 업로드 클라이언트 사전 검증(UX 전용).
//
// 서버(BE PortalUploadService)가 확장자 allowlist·매직바이트·크기·개수 최종 검증의 진실원이다.
// 여기 검증은 사용자에게 즉시 안내하여 불필요한 업로드 왕복을 줄이기 위함이며, 안내 문구에
// 서버 정책을 명시한다(과신 방지).

/** 허용 확장자(BE 와 동일 — jpg/jpeg/png). */
export const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png'] as const;
/** 개당 최대 크기 — 20MB. */
export const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
/** 요청당 최대 장수 — 50. */
export const MAX_IMAGE_COUNT = 50;

/** 서버 정책 안내 문구 — 검증 메시지에 함께 노출. */
export const IMAGE_POLICY_TEXT = `허용: jpg/jpeg/png · 개당 최대 20MB · 요청당 최대 ${MAX_IMAGE_COUNT}장 (최종 검증은 서버가 수행합니다)`;

export interface ImageValidationResult {
  /** 검증 통과 파일(최대 MAX_IMAGE_COUNT 장). */
  valid: File[];
  /** 사용자 안내용 오류 메시지 목록. */
  errors: string[];
}

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : '';
}

/**
 * 이미지 파일 선택을 사전 검증한다. 확장자/크기 위반 파일은 제외하고, 개수 상한 초과분은
 * 잘라낸다. 위반이 있으면 서버 정책을 명시한 오류 메시지를 함께 반환한다.
 */
export function validateImageFiles(files: File[]): ImageValidationResult {
  const errors: string[] = [];
  const accepted: File[] = [];

  for (const f of files) {
    const ext = extensionOf(f.name);
    if (!(IMAGE_EXTENSIONS as readonly string[]).includes(ext)) {
      errors.push(`${f.name}: 허용되지 않는 형식입니다. ${IMAGE_POLICY_TEXT}`);
      continue;
    }
    if (f.size > MAX_IMAGE_BYTES) {
      errors.push(`${f.name}: 크기가 20MB를 초과했습니다. ${IMAGE_POLICY_TEXT}`);
      continue;
    }
    accepted.push(f);
  }

  if (accepted.length > MAX_IMAGE_COUNT) {
    errors.push(
      `한 번에 최대 ${MAX_IMAGE_COUNT}장까지 업로드할 수 있어 초과분은 제외했습니다. ${IMAGE_POLICY_TEXT}`,
    );
  }

  return { valid: accepted.slice(0, MAX_IMAGE_COUNT), errors };
}
