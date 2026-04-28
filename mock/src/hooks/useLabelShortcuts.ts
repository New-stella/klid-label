import { useEffect } from 'react';
import { useLabelStore } from '../store/labelStore';

interface Options {
  onSave?: () => void;
}

export function useLabelShortcuts({ onSave }: Options = {}) {
  const setTool = useLabelStore((s) => s.setTool);
  const removeSelected = useLabelStore((s) => s.removeSelected);
  const undo = useLabelStore((s) => s.undo);
  const setFrame = useLabelStore((s) => s.setFrame);
  const currentFrame = useLabelStore((s) => s.currentFrame);
  const totalFrames = useLabelStore((s) => s.totalFrames);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      // Ignore when typing in input/textarea/select
      const target = e.target as HTMLElement;
      const tag = target.tagName.toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select') return;
      if (target.isContentEditable) return;

      const key = e.key.toLowerCase();

      // Tool shortcuts
      if (!e.ctrlKey && !e.metaKey) {
        if (key === 'v') {
          e.preventDefault();
          setTool('select');
          return;
        }
        if (key === 'b') {
          e.preventDefault();
          setTool('bbox');
          return;
        }
        if (key === 'p') {
          e.preventDefault();
          setTool('polygon');
          return;
        }
        if (key === 'delete' || key === 'backspace') {
          e.preventDefault();
          removeSelected();
          return;
        }
        if (key === 'arrowleft') {
          e.preventDefault();
          if (currentFrame > 0) setFrame(currentFrame - 1);
          return;
        }
        if (key === 'arrowright') {
          e.preventDefault();
          if (currentFrame < totalFrames - 1) setFrame(currentFrame + 1);
          return;
        }
      }

      // Ctrl/Cmd shortcuts
      if (e.ctrlKey || e.metaKey) {
        if (key === 'z') {
          e.preventDefault();
          undo();
          return;
        }
        if (key === 's') {
          e.preventDefault();
          onSave?.();
          return;
        }
      }
    };

    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [setTool, removeSelected, undo, setFrame, currentFrame, totalFrames, onSave]);
}
