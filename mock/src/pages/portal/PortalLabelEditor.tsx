import { LabelEditor } from '../label/LabelEditor';

/**
 * PortalLabelEditor — thin wrapper around Phase 5 LabelEditor.
 * Activates portalMode which:
 *  - Hides version history button
 *  - Shows auto-label button
 *  - Changes title to "포털 — 라벨링"
 *  - Hides mask-related UI (handled in ObjectTree/AttributePanel by filtering mask type)
 */
export function PortalLabelEditor() {
  return <LabelEditor portalMode />;
}

export default PortalLabelEditor;
