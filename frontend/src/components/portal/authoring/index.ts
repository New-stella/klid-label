/**
 * 저작도구 화면 부품 — 부모 포털(KLID_Portal `pages/workspace/authoring`)에서 **복사해 온** 한 벌.
 *
 * 부모 포털이 우리 화면을 자기 부품으로 다시 그려 「저작도구 쪽에 넘기는 기준」으로 삼은 것이
 * 이 부품들이다. 마킹·라벨링 두 편집 면이 함께 쓴다.
 *
 * 전부 **표시 전용**이다 — 데이터를 스스로 부르지 않고 받은 값만 그린다. 그래서 부모 포털의
 * 목 데이터 모양에 묶이지 않고 우리 API 응답을 그대로 꽂을 수 있다.
 *
 * ⚠ 포털 채널 전용이다. 관제 화면에서 쓰지 말 것 — 토큰·루트 글꼴 전제가 갈린다([[kit]] 주석).
 * ⚠ 부모 포털의 `frameFit.ts` 는 들여오지 않았다 — 그것은 **포털이 iframe 을 재는** 쪽 코드이고
 *   우리는 Module Federation 으로 마운트돼 iframe 이 없다.
 */
export { AnnotatedFrame } from './AnnotatedFrame';
export { CanvasNotice } from './CanvasNotice';
export { ConditionChips } from './ConditionChips';
export { FrameStrip } from './FrameStrip';
export { Kbd, KeyCombo } from './Kbd';
export { MarkPointList } from './MarkPointList';
export { MarkTimeline } from './MarkTimeline';
export { NoteList } from './NoteList';
export { ObjectList } from './ObjectList';
export { PlaybackBar } from './PlaybackBar';
export { RangeSlider } from './RangeSlider';
export { ShortcutTable } from './ShortcutTable';
export { StatusText } from './StatusText';
export { ToolList, ToolAction, ToolSwitch, type ToolListItem } from './ToolList';
export { ToolPanel, ToolRow } from './ToolPanel';
export { VideoStage } from './VideoStage';
