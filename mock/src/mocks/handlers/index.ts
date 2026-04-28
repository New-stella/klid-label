import { usersHandlers } from './users';
import { videosHandlers } from './videos';
import { tasksHandlers } from './tasks';
import { labelsHandlers } from './labels';
import { reviewsHandlers } from './reviews';
import { deidentHandlers } from './deident';
import { historyHandlers } from './history';
import { augmentHandlers } from './augment';
import { exportHandlers } from './export';
import { portalHandlers } from './portal';
import { statsHandlers } from './stats';
import { adminHandlers } from './admin';
import { presetHandlers } from './preset';
import { generateHandlers } from './generate';

export const handlers = [
  ...usersHandlers,
  ...videosHandlers,
  ...tasksHandlers,
  ...labelsHandlers,
  ...reviewsHandlers,
  ...deidentHandlers,
  ...historyHandlers,
  ...augmentHandlers,
  ...exportHandlers,
  ...portalHandlers,
  ...statsHandlers,
  ...adminHandlers,
  ...presetHandlers,
  ...generateHandlers,
];
