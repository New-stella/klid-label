/**
 * 증적 촬영용 로컬 중계 — 브라우저의 Origin 을 떼고 대상 서버로 넘긴다.
 *
 * 왜 필요한가
 *   포털 화면(024·027)은 포털 채널 산출물에만 있어 그 채널로 빌드한 FE 를 로컬(:15173)에
 *   따로 띄워야 한다. 그러면 브라우저가 `Origin: http://localhost:15173` 을 보내는데,
 *   대상 서버의 CORS 허용목록에 그 오리진이 없어 403 `Invalid CORS request` 가 난다.
 *   허용목록이 비어 있는 것은 **의도된 설정**이다(지금은 같은 출처로만 쓰이므로).
 *   그래서 서버를 고치는 대신, 이 중계가 Origin 만 떼어 <브라우저가 아닌 요청>처럼 만들어
 *   넘긴다. 촬영이 끝나면 이 프로세스만 끄면 된다.
 *
 * 쓰는 법
 *   CAPTURE_APP=http://<서버>:<포트>/<베이스경로> node capture-origin-proxy.mjs
 *   (capture.env 를 이미 채웠다면 `set -a; . ./capture.env; set +a` 뒤에 그냥 실행하면 된다)
 *
 * 환경변수
 *   CAPTURE_PROXY_TARGET  중계 대상. 없으면 CAPTURE_APP 을 쓴다. 베이스 경로까지 포함한다.
 *   CAPTURE_PROXY_PORT    이 중계가 듣는 포트 (기본 15174)
 *
 * ⚠ 이것은 <촬영용 우회>이지 CORS 문제의 해소가 아니다. 포털 임베딩(INT-013)이 실제로
 *   붙을 때는 서버 허용목록에 그 Host 오리진을 넣어야 하며, 그때까지 이 중계가 그 사실을
 *   가려서는 안 된다.
 */
import http from 'node:http';
import https from 'node:https';

const raw = process.env.CAPTURE_PROXY_TARGET || process.env.CAPTURE_APP || '';
if (!raw) {
  console.error(
    '중계 대상이 없다. CAPTURE_PROXY_TARGET 또는 CAPTURE_APP 을 베이스 경로까지 포함해 준다.\n' +
      '  예) CAPTURE_APP=http://192.168.102.246:8088/label-studio node capture-origin-proxy.mjs',
  );
  process.exit(2);
}

let target;
try {
  target = new URL(raw);
} catch {
  console.error(`중계 대상 주소를 읽을 수 없다: ${raw}`);
  process.exit(2);
}

const secure = target.protocol === 'https:';
const agent = secure ? https : http;
const upstreamPort = Number(target.port || (secure ? 443 : 80));
const prefix = target.pathname.replace(/\/$/, ''); // 베이스 경로 (끝 슬래시 제거)
const PORT = Number(process.env.CAPTURE_PROXY_PORT ?? 15174);

http
  .createServer((req, res) => {
    const headers = { ...req.headers };
    // 이 둘이 이 중계의 존재 이유다 — 서버가 <브라우저 요청>으로 보지 않게 한다.
    delete headers.origin;
    delete headers.referer;
    headers.host = `${target.hostname}:${upstreamPort}`;

    const up = agent.request(
      {
        host: target.hostname,
        port: upstreamPort,
        method: req.method,
        path: prefix + req.url,
        headers,
      },
      (ur) => {
        res.writeHead(ur.statusCode ?? 502, ur.headers);
        ur.pipe(res);
      },
    );
    up.on('error', (e) => {
      res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
      res.end(`중계 대상에 닿지 못했다: ${e.message}`);
    });
    req.pipe(up);
  })
  .listen(PORT, () => {
    console.log(`Origin 제거 중계 :${PORT}  ->  ${target.protocol}//${target.hostname}:${upstreamPort}${prefix}`);
  });
