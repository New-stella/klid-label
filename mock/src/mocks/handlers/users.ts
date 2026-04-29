import { http } from 'msw';
import { users } from '../data/users';
import { ok, paginate, parsePageParams } from './_utils';

export const usersHandlers = [
  http.get('/api/v1/users', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    const role = url.searchParams.get('role');
    const filtered = role ? users.filter((u) => u.role === role) : users;
    return ok(paginate(filtered, page, size));
  }),

  http.get('/api/v1/users/me', () => {
    const reviewer = users.find((u) => u.role === 'REVIEWER') ?? users[0];
    return ok(reviewer);
  }),

  http.get('/api/v1/users/workers', ({ request }) => {
    const url = new URL(request.url);
    const { page, size } = parsePageParams(url);
    const workers = users.filter((u) => u.role === 'WORKER');
    return ok(paginate(workers, page, size));
  }),
];
