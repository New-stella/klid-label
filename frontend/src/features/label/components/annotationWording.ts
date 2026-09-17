// 「영상 분석 설명 · 이벤트 어노테이션」 창의 화면 문구 단일 원천.
// [@design UI-056] [@design UI-107] [@design UI-156] [@design UI-157] [@design UI-158]
//
// ★왜 한 파일에 모으나 — 같은 문장이 창·요약 카드·띠·읽기 전용 렌더 <b>네 곳</b>에 나온다.
//   각 컴포넌트에 적으면 문구가 다듬어질 때 한쪽만 갱신되고, 그 차이는 화면을 나란히 놓고 보기
//   전에는 드러나지 않는다(이 저장소의 반복 결함).
//
// ★표기 규칙 (2026-09-14 확정) — 이 창과 요약 카드에서는 <b>「영상 분석 설명」</b>이라고 쓴다.
//   「시계열」·「시계열 메타」·「시계열 서술」을 쓰지 않으며, 내부 저장 키(vlm.description ·
//   manual-timeseries)를 라벨로 노출하지 않는다. ⚠ 다른 화면의 「시계열」 표기는 이 규칙의
//   범위가 아니다 — 함께 바꾸지 말 것.

/** 창 제목 — 창의 접근성 이름이자 요약 카드 제목이다(두 자리가 같은 이름을 쓴다). */
export const ANNOTATION_WINDOW_TITLE = '영상 분석 설명 · 이벤트 어노테이션';

export const ANNOTATION_WINDOW_SUBTITLE = {
  editable:
    '외부 분석 설명과 사건 판단 정보를 크게 보고 고치는 창 · 영상 전체에 해당하므로 프레임을 넘겨도 같습니다',
  readOnly:
    '외부 분석 설명과 사건 판단 정보를 크게 보는 창 · 영상 전체에 해당하므로 프레임을 넘겨도 같습니다',
} as const;

/** 제목 표시줄의 미저장 표시. 칸 머리의 표시(아래)와 문구가 다르다 — 층이 다르기 때문이다. */
export const WINDOW_DIRTY_LABEL = '저장 안 된 변경';
/** 칸 머리의 미저장 표시. */
export const COLUMN_DIRTY_LABEL = '저장 안 됨';
/** 칸 머리의 저장 실패 표시 — 한쪽만 실패했을 때 어느 칸이 실패했는지 이 표시가 가른다. */
export const COLUMN_SAVE_FAILED_LABEL = '저장 실패';
/** 칸 머리의 범위 표시 — 두 칸 모두 영상 단위 값이라 프레임을 넘겨도 같다. */
export const COLUMN_SCOPE_LABEL = '영상 전체';

export const TIMESERIES_COLUMN_TITLE = '영상 분석 설명';
export const ANNOTATION_COLUMN_TITLE = '이벤트 어노테이션';

export const TIMESERIES_COLUMN_DESCRIPTION = {
  editable:
    '외부 분석이 영상을 보고 장소·날씨·상황 등을 적은 설명입니다. 틀린 내용은 고칠 수 있습니다.',
  readOnly: '외부 분석이 영상을 보고 장소·날씨·상황 등을 적은 설명입니다.',
} as const;

export const ANNOTATION_COLUMN_DESCRIPTION = {
  editable:
    '학습데이터에 함께 저장되는 사건 판단 정보입니다. 이벤트 분류만 꼭 채우고, 나머지는 확인한 만큼 채웁니다.',
  readOnly: '학습데이터에 함께 저장되는 사건 판단 정보입니다.',
} as const;

/** 읽기 전용에서 값이 없는 칸의 표시 — 빈 칸을 남기지 않는다. */
export const NOT_FILLED_LABEL = '아직 채우지 않았습니다';

/* ─── 필드 이름 ───────────────────────────────────────────────────────────── */

/**
 * 근거 후보의 필드 이름 — <b>편집(라벨링)과 읽기 전용(검수)이 같은 표를 본다.</b>
 *
 * ★두 모드가 각자 문자열을 들고 있으면 한쪽만 다듬어져 같은 칸이 화면마다 다른 이름으로 보인다
 * (실제로 그랬다 — 읽기 전용만 「프레임 번호」·「객체 박스 좌표」로 길었다).
 *
 * ★<b>「프레임」이 곧 칩들 앞에 한 번만 두는 접두</b>다(읽기 전용). 칩 안에 「프레임」을
 * 되풀이해 적지 않는다 — 이 이름이 이미 그 자리에 있기 때문이다.
 */
export const EVIDENCE_FIELD_LABEL = {
  evidenceText: '근거 문장',
  frameId: '프레임',
  objId: '객체 번호',
  objLabel: '객체 분류 이름',
  objBbox: '객체 박스',
  /** 읽기 전용에서 객체 번호와 분류 이름을 한 칸에 합쳐 보이는 자리. */
  objectReadOnly: '객체',
} as const;

/* ─── 필드 도움말 · 입력 안내 ─────────────────────────────────────────────── */

export const FIELD_HELP = {
  eventClass: '이 영상에서 확인하려는 사건의 종류입니다.',
  question: '외부 분석에 물어본 질문입니다. 분석을 요청할 때 보낸 문구가 들어 있습니다.',
  answer: '사람이 적는 최종 답입니다.',
  captionSection: '사건을 설명하는 문장 후보입니다. 여러 개를 둘 수 있습니다.',
  captionText: '외부 분석에 물어본 질문의 답이 처음에 채워집니다.',
  captionMarkdownNote:
    '외부 분석 답을 받은 그대로 보여 줍니다 — 굵게·목록 기호(**, ###)를 어떻게 보일지는 사업자 회신 후 정합니다.',
  cot: '이 문장에 이르기까지의 생각을 순서대로 적습니다.',
  evidenceSection: '판단의 근거가 된 프레임과 객체입니다.',
  evidenceText: '이 프레임·객체가 판단의 근거인 이유입니다.',
  frameIdEditable: '근거가 보이는 프레임 번호입니다. 여러 개면 쉼표로 구분합니다.',
  frameIdReadOnly: '근거가 보이는 프레임 번호입니다. 누르면 그 프레임으로 이동하고 창은 잠시 접힙니다.',
  objId: '근거가 되는 객체(라벨)의 번호입니다. 여러 개면 쉼표로 구분합니다.',
  objLabel: '객체 번호와 같은 순서로 적습니다.',
  objBboxEditable:
    '사각형의 왼쪽 위·오른쪽 아래 좌표를 한 줄에 하나씩 x1,y1,x2,y2 로 적습니다.',
  objBboxReadOnly: '객체를 감싸는 사각형의 왼쪽 위·오른쪽 아래 좌표입니다.',
  objectReadOnly: '근거가 되는 객체(라벨)의 번호와 분류 이름입니다.',
} as const;

export const FIELD_PLACEHOLDER = {
  answer: '이 영상에서 사건이 발생했는지와 그렇게 판단한 이유를 적어 주세요.',
  captionText: '사건을 한두 문장으로 설명해 주세요.',
  evidenceText: '이 프레임·객체가 왜 근거인지 적어 주세요.',
  cot: [
    '외부 분석이 적은 상황 설명으로 채워집니다.',
    '1단계 다음으로 확인한 내용을 적어 주세요.',
    '최종 판단에 이른 이유를 적어 주세요.',
  ],
} as const;

export const NO_EVIDENCE_TEXT = {
  editable:
    '아직 근거가 없습니다. 「+ 근거 추가」로 만든 뒤 「화면에서 지정」으로 프레임·객체를 담아 주세요.',
  readOnly: '근거가 없습니다.',
} as const;

/* ─── 버튼 · 띠 · 알림 ────────────────────────────────────────────────────── */

export const ADD_CAPTION_LABEL = '+ 캡션 추가';
export const ADD_EVIDENCE_LABEL = '+ 근거 추가';
export const PICK_BUTTON_LABEL = '화면에서 지정';
export const PICK_BUTTON_HELP = '창을 잠시 숨기고 화면에서 프레임·객체를 골라 담습니다';

/** 지정 직후 그 후보 안에 뜨는 안내 — 저장 전임을 분명히 말한다. */
export function pickedNoticeText(frames: number, objects: number): string {
  return `방금 「화면에서 지정」으로 담은 값입니다(프레임 ${frames}개 · 객체 ${objects}개). 저장해야 반영됩니다.`;
}

export const HELP_TOGGLE_LABEL = { hide: '도움말 숨기기', show: '도움말 보기' } as const;

export const SAVE_BAR = {
  nothingChanged: '고친 내용이 없습니다.',
  changed: (columns: string) => `바뀐 칸만 저장합니다 — ${columns}`,
  close: '닫기',
  save: '저장',
} as const;

/**
 * 저장 결과 알림.
 *
 * ★「일부만」을 별도 문구로 두는 이유 — 두 칸을 한 버튼으로 저장하므로 한쪽만 실패하는 결말이
 * 실재한다. 그때 「저장했습니다」라고 하면 실패한 칸이 저장된 줄로 읽힌다(어느 칸이 실패했는지는
 * 그 칸 머리의 「저장 실패」 표시가 가른다).
 */
export const SAVE_RESULT = {
  success: '저장했습니다.',
  partial: '일부만 저장했습니다 — 실패한 칸을 확인해 주세요.',
  failed: '저장에 실패했습니다.',
} as const;

export const UNSAVED_CLOSE_CONFIRM = {
  title: '저장하지 않은 변경이 있습니다',
  description: (columns: string) => `닫으면 고친 내용이 사라집니다. 바뀐 칸: ${columns}`,
  saveAndClose: '저장하고 닫기',
  discardAndClose: '저장하지 않고 닫기',
  keepWriting: '계속 작성',
} as const;

export const STRIP = {
  pickHeading: (no: number) => `근거 ${no} 지정 중`,
  pickStatus: (frameIndex: number, frameTotal: number, object: string) =>
    `지금 프레임 ${frameIndex}/${frameTotal} · 고른 객체: ${object}`,
  pickNoObject: '없음',
  captureFrame: '지금 프레임 담기',
  captureObject: '고른 객체 담기',
  pickedCounts: (frames: number, objects: number) =>
    `담은 것 · 프레임 ${frames} · 객체 ${objects}`,
  cancel: '취소',
  complete: '완료',
  pickGuide: (no: number) =>
    `프레임을 넘기고 화면에서 객체를 고른 뒤 담기 버튼을 누르세요. 「완료」를 누르면 창이 돌아오고 ` +
    `담은 값이 근거 ${no}에 들어갑니다. 「취소」는 이번에 담은 것만 버립니다.`,
  evidenceJumpHeading: (no: number, frameIndex: number) => `근거 ${no} · 프레임 ${frameIndex}`,
  evidenceJumpGuide: '근거로 적힌 프레임으로 이동했습니다. 확인이 끝나면 창을 펼치세요.',
  foldedGuide: '영상 분석 설명 · 이벤트 어노테이션 창을 잠시 접었습니다. 다시 보려면 펼치세요.',
  foldedDirty: '저장 안 된 변경 있음',
  expand: '펼치기',
} as const;

/** 이 영상에 없는 프레임 번호를 눌렀을 때 — 띠가 아니라 알림(Toast)으로 알린다. */
export function missingFrameNoticeText(frameId: number): string {
  return `${frameId} — 이 영상에 없는 프레임 번호라 이동하지 않았습니다.`;
}

/* ─── 요약 카드 ───────────────────────────────────────────────────────────── */

export const SUMMARY_CARD = {
  description: '외부 분석이 적은 영상 설명과, 학습데이터에 함께 저장되는 사건 판단 정보입니다.',
  /**
   * 왜 이 자리에 요약만 두는지 알리는 보조 설명 — 설명 바로 아래 한 줄.
   *
   * ★두 번째 문장이 모드마다 갈린다. 라벨링은 창이 읽기 전용이 아니고 버튼 문구도 다르므로
   * 「크게 보기 · 작성」·「보고 고칩니다」이고, 검수는 「크게 보기」·「읽기 전용 창」이다.
   * 한 문장으로 합치면 한쪽 화면에서 거짓이 된다.
   */
  hint: {
    editable:
      '좁은 탭에서 읽기 어려워 여기에는 간추린 값만 둡니다. 전문은 「크게 보기 · 작성」으로 여는 창에서 보고 고칩니다.',
    readOnly:
      '좁은 탭에서 읽기 어려워 여기에는 간추린 값만 둡니다. 전문은 「크게 보기」로 여는 읽기 전용 창에서 확인합니다.',
  },
  eventClassLabel: '이벤트 분류',
  descriptionLabel: '영상 분석 설명',
  unfilledLabel: '아직 채우지 않은 항목',
  reviewStatusLabel: '검토 상태',
  openEditable: '크게 보기 · 작성',
  openReadOnly: '크게 보기',
  bringToFront: '창 앞으로 가져오기',
  expandWindow: '창 펼치기',
  pickingDisabled: '근거 지정 중 — 위쪽 띠의 「완료」로 창이 돌아옵니다',
  stateOpen: '창 열림',
  stateFolded: '창 접힘',
  statePicking: '근거 지정 중',
  none: '없음',
} as const;

/**
 * 이벤트 어노테이션 검토 상태 → 중립 한글 라벨.
 *
 * 창(라벨링)과 요약 카드가 <b>같은 표</b>를 본다 — 두 자리가 각자 표를 들면 같은 상태가 화면마다
 * 다른 말로 보인다. 기술 모델명·영문 코드는 화면에 노출하지 않는다.
 */
const REVIEW_STATUS_LABEL: Record<string, string> = {
  AUTO_GENERATED: '자동 생성',
  PENDING: '검토 대기',
  APPROVED: '승인됨',
  REJECTED: '반려됨',
};

/** 모르는 상태값은 <b>받은 값을 그대로</b> 보인다 — 감추면 상태가 있는데 없는 것처럼 보인다. */
export function reviewStatusLabel(status: string | null | undefined): string | null {
  if (status === null || status === undefined || status === '') return null;
  return REVIEW_STATUS_LABEL[status] ?? status;
}

/** 창 제목 표시줄의 조작 버튼 이름 — 아이콘만 있는 버튼이라 이름이 곧 접근성 이름이다. */
export const WINDOW_CONTROL_LABEL = {
  fold: '잠시 접기',
  maximize: '크게',
  restore: '원래 크기',
  close: '닫기',
} as const;
