# 증적 촬영 시나리오 — 처음부터 끝까지

`CAPTURE-README.md` 가 **도구 사용법**이라면, 이 문서는 **무엇을 어떤 순서로 준비해 찍는가**다.
값 하나하나가 어디서 왔는지, 무엇을 쓰면 소진되는지, 다시 만들려면 무엇을 해야 하는지 적는다.

> ⚠ **이 문서가 없으면 같은 것을 계속 다시 알아내게 된다.** 실제로 2026-09-09 한 세션에서
> `capture.env` 값의 유래 · 마킹 단계 영상의 배정 경로 · 소진되는 케이스를 **세 번 다시** 찾았다.

---

## 0. 전체 흐름 한눈에

```
① 새 영상 인입        관제가 하는 일을 대신한다 (LS_DATA_INGEST INSERT)
② 비식별 확인          ★원장이 아니라 해시로 본다
③ 마킹 배정            영상 처리 현황 → [마킹 설정] → 수동 → 작업자 배정
④ 마킹                 작업자로 수동 마킹 6건 → 마킹 완료
⑤ 프레임·오토라벨      배치가 자동으로 — 프레임 6장 + 라벨 약 78건
⑥ 값 갈아끼우기        capture.env 의 프레임 번호를 새 영상 것으로
⑦ 촬영                 순서가 있다 (아래 §5)
⑧ 검증                 규격 · 중복 · 빈 칸 세 축
```

---

## 1. 새 영상 인입 — 관제가 하는 일을 대신한다

관제는 저작도구 소유 표 `LS_DATA_INGEST` 에 **직접 INSERT** 하고, 저작도구 배치가 그것을 폴링한다.
그래서 촬영 준비도 같은 방식으로 한다.

```sql
INSERT INTO klid_at.ls_data_ingest
  (rcptn_sn, rcptn_dt, prcs_stts_cd, rty_cnt,
   vms_clip_id, vms_cctv_id, vdo_file_nm, raw_file_path_nm,
   src_type, vrfc_evnt_type_cd, evnt_type_cd)
SELECT COALESCE(MAX(rcptn_sn),0)+1, now(), 'PENDING', 0,
       'CCTV-024', 'CCTV-024',            -- ← 아직 안 쓴 이름
       'body_crop_305.4-450.0.mp4',
       '/nas-storage/data/upload/v2/CLP-41590-NBY-20260728-00051/crop/body_crop_305.4-450.0.mp4',
       'RELAY', 'car_accident', 'EV03000101'
FROM klid_at.ls_data_ingest;
```

⚠ **`vms_cctv_id` 를 반드시 채운다** — 그게 목록의 「CCTV명」 열이다. 비우면 목록에 `영상 #NN` 으로
떠서 클립 이름으로는 찾을 수 없다(2026-09-09 실측: `007-01-cut2` 가 이것 때문에 실패).

폴링은 1분 주기다. `MARKING_READY` 가 되면 인입 + 비식별까지 끝난 것이다.

---

## 2. 비식별 확인 — ★원장을 믿지 말고 해시를 뜬다

```sql
SELECT data_raw_sn, proc_stts_cd, face_dtct_cnt, noplt_dtct_cnt, de_idntf_file_path_nm
  FROM klid_at.ls_deident_proc_log WHERE data_raw_sn = <새 raw_sn>;
```

⚠⚠ **검출 건수는 시뮬레이션 값이라 파일이 원본 그대로여도 채워진다.** 2026-09-09 실측:
`face 18 · 번호판 12 · SUCCEEDED` 인데 산출 파일이 원본과 **sha256 까지 동일**했다. 원장만 보면
절대 알 수 없다. 반드시 파일로 본다:

```bash
nt ssh cudo_246 "docker exec klid-authoring-jboss sh -lc \
  'sha256sum <원본경로> <de_idntf_file_path_nm 값>'"
# 두 해시가 같으면 마스킹이 안 된 것이다
```

정상이면 ①해시가 다르고 ②재인코딩이라 **파일이 커지며**(실측 11.2MB → 22.8MB)
③목서버 로그에 `deid rendered — 실제 마스킹(엔진)` 이 찍힌다. 폴백이면 `원본 복사로 폴백` 이다.

**엔진이 죽는 조건**: `klid-mock-server` 안의 `/app/models` 에 `.onnx` 3종
(`face_detection_yunet` · `text_detection_ppocr` · `object_detection_yolox`)이 없으면
`engine_available()=False` 가 되어 **조용히 원본 복사**로 떨어진다(오류가 아니라 INFO 한 줄).
기동 로그의 `[MOCK][DEID] 비식별 엔진 활성` 유무로 먼저 가른다.

⚠ **목서버를 재기동한 직후에는 인입을 넣지 마라** — 요청이 이전 프로세스로 갈 수 있다.
2026-09-09 에 50초 차이로 그렇게 됐고, 결과가 원본과 동일했다.

---

## 3. 마킹 배정 — ★작업 목록에는 없다

마킹 단계 영상은 **작업 목록(`/task`)에 뜨지 않는다**(그 화면은 처리 완료만 표시). 그래서
「배정」 버튼으로는 갈 수 없다. 경로는 하나다:

```
영상 처리 현황(/video/status) → 그 행의 [마킹 설정] → 「수동」 → 작업자 배정 다이얼로그
```

`마킹 설정` 은 검수자에게만 보이고, **미배정 + 마킹 가능** 행에만 나온다. 「수동」을 고르면
자동으로 작업자 배정 흐름으로 전환된다.

하네스가 이것을 `prep-marking-assign` 케이스로 갖고 있다:
```bash
./run-capture.sh prep-marking-assign      # CAPTURE_RAW_SN 을 작업자에게 배정
```

⚠ 이걸 건너뛰면 `011-01` 이 **403(본인에게 배정되지 않은 영상)** 으로 막힌다. 마킹은
작업권한이 **WORKER** 라(D11 011-01) 검수자로 대신 찍으면 안 된다.

---

## 4. 마킹 → 프레임·오토라벨

```bash
./run-capture.sh 011-01
```
수동 마킹 6건(`CAPTURE_MARK_SECONDS`)을 찍고 **마킹 완료까지 제출**한다. 제출해야 잔여 배치가
돌아 프레임이 나온다. 곧 프레임 6장 + 오토라벨이 붙는다:

```sql
SELECT s.src_sn, s.frm_no,
       (SELECT count(*) FROM klid_at.ls_data_lbl l WHERE l.src_sn = s.src_sn) AS labels,
       s.src_file_path_nm IS NOT NULL AS raw_img,
       s.de_idntf_src_file_path_nm IS NOT NULL AS deid_img
  FROM klid_at.ls_data_src s WHERE s.raw_sn = <새 raw_sn> ORDER BY s.src_sn;
```
`raw_img` 와 `deid_img` 가 **둘 다 t** 여야 라벨링 화면이 열린다.

---

## 5. 값 갈아끼우기 — 각 값이 무엇을 요구하는가

| 값 | 요구 조건 | 안 맞으면 |
|---|---|---|
| `CAPTURE_RAW_SN` | `MARKING_READY` + `de_ident_yn='Y'` + **작업자 배정** | 마킹 화면이 403 이거나 「이미 다음 단계로 넘어간 영상」 |
| `CAPTURE_FRAME_MAIN` | 배정된 영상의 프레임 + **이미지 실재** | 라벨링 화면이 안 열림 |
| `CAPTURE_FRAME_AI` | 위 + **라벨이 적은** 프레임 | AI 탐지가 중복 억제로 0건 |
| `CAPTURE_FRAME_TRACK/_NEXT` | **연속한** 두 프레임 + 이미지 실재 | 추적이 반영할 것을 못 찾음 |
| `CAPTURE_FRAME_REPORT` | **승인 이력이 없는** 영상의 프레임 | 신고 버튼이 잠김(승인 이력 있으면 신고 불가) |
| `CAPTURE_RESL_RAW_SN` | 해상도 3종이 **아직 없는** 검수완료 영상 | 「전부 스킵」 400, 결과 화면이 안 뜸 |
| `CAPTURE_INGEST_CLIP` | **아직 적재 안 된** 클립 이름 | `007-01-cut1`(적재 전)이 실패 |

★**`FRAME_AI` 와 `FRAME_REPORT` 는 전제가 정반대다** — 하나는 오토라벨이 붙은(=파이프라인이 끝난)
프레임, 하나는 한 번도 승인된 적 없는 영상. **같은 값을 쓰면 하나를 맞추는 순간 다른 하나가 깨진다.**

조회 쿼리:
```sql
-- 라벨링 화면이 열리는 프레임 (배정 + 이미지 실재)
SELECT s.src_sn, s.raw_sn,
       (SELECT count(*) FROM klid_at.ls_data_lbl l WHERE l.src_sn=s.src_sn) AS labels
  FROM klid_at.ls_data_src s
  JOIN klid_at.ls_task_altmnt a ON a.raw_data_id=s.raw_sn AND a.task_type_cd='LABELER'
 WHERE s.src_file_path_nm IS NOT NULL ORDER BY labels;

-- 승인 이력이 없는 영상 (신고용)
SELECT r.raw_sn FROM klid_at.ls_data_raw r
 WHERE r.de_ident_yn='Y'
   AND NOT EXISTS (SELECT 1 FROM klid_at.ls_label_version v
                     JOIN klid_at.ls_data_src s2 ON s2.src_sn=v.data_src_sn
                    WHERE s2.raw_sn=r.raw_sn);
```

---

## 6. 촬영 순서 — 뒤 케이스의 재료를 앞 케이스가 만든다

```
prep-marking-assign  →  011-01(마킹·프레임 생성)
  ↓
014-01 · 012-01 · 018-01 · 017-* · 016-01 · 015-*       (읽기·편집 계열)
  ↓
019-02(반려)  →  019-01(제출→승인)                      ← 승인 버전을 만든다
  ↓
021-01 · 021-02(버전 diff)                              ← 승인 버전이 있어야 한다
  ↓
010-01(신고) · 010-02(해소)                             ★영상을 잠그므로 맨 끝
  ↓
023-01(증강 요청) → 025-01(채택) / 023-01 → 025-02(반려) ← 결정 대상은 요청마다 1개
  ↓
007-01-cut1(적재 전) → 인입 INSERT → 007-01-cut2(적재 후)
  ↓
포털 029-* · 030-*                                      (별도 채널 — §CAPTURE-README §3)
```

**소진되는 케이스**(두 번째 실행은 실패한다):

| 케이스 | 소비하는 것 | 다시 만들려면 |
|---|---|---|
| `011-01` | 마킹을 완료해 영상이 다음 단계로 넘어간다 | 새 영상을 §1~§3 으로 준비 |
| `007-01` | 그 클립이 적재돼 목록에 남는다 | 새 클립 이름 + 인입 INSERT |
| `025-01`·`025-02` | 활용 결정 대기 1건을 확정한다 | `023-01` 을 한 번 더 돌린다 |
| `019-01` | 검수 건을 승인해 대기에서 뺀다 | 작업자가 다시 제출 |
| `010-01` | 영상을 신고 상태로 잠근다 | `010-02` 로 해소 |
| `026-02` | 그 영상의 해상도 프리셋을 소비 | 다른 검수완료 영상 |

⚠ **해상도 파생(`RESL_*`)은 채택·반려 대상이 아니다** — 「활용 결정 대기」로 보여도 버튼이 없다.
`025-*` 은 **외부 증강 결과**를 골라야 한다.

---

## 7. 검증 — 세 축을 다 본다

```bash
./run-capture.sh --manifest-only     # 원장 재생성 (찍힌 파일을 훑어 다시 만든다)
```

| 축 | 무엇을 보나 | 통과 기준 |
|---|---|---|
| **빈 칸** | 원장에 케이스가 다 있고 컷이 비지 않았나 | 빈 케이스 0 |
| **규격** | 화면 컷이 정확히 1920×1080 인가 | 규격 밖 0장 (요소 캡처·쿼리 렌더 제외) |
| **중복** | 서로 다른 케이스가 같은 그림을 갖는가 | 의도된 공유 2건 외 0건 |

⚠⚠ **실패해도 원장은 차 있을 수 있다** — 원장은 폴더의 파일을 훑어 만들므로, 그 케이스에 앞
회차 그림이 남아 있으면 실패했는데도 채워진다. **규격(파일 크기)으로 낡은 것을 걸러야** 한다.
2026-09-09 실측: 실패 13건인데 원장은 48/90 그대로였다.

⚠ **중복은 눈으로 못 잡는다** — 2026-09-09 에 해시 대조가 6건을 잡았고 그중 하나는
「수동 라벨링」 증적이 **빈 캔버스**인 건이었다. 반드시 기계로 훑는다.

```bash
python3 - <<'PY'
import glob,os,hashlib,collections
d=os.path.expanduser("~/Desktop/CBD-캡처루트-YYYYMMDD/captures")
h=collections.defaultdict(list)
for p in glob.glob(d+"/*.png"): h[hashlib.sha256(open(p,'rb').read()).hexdigest()].append(os.path.basename(p))
for v in h.values():
    if len(v)>1: print("중복:", v)
PY
```
의도된 공유는 둘뿐이다 — `026-01`↔`026-02-cut2`, `020-01`↔`019-01-cut2`.

---

## 8. ★산출물은 세 벌이고 **원장도 벌마다 달라야 한다**

CBD 산출물은 범위가 다른 세 벌이다. **D11 의 케이스 집합이 벌마다 다르므로, 증적 원장도 그 벌의
D11 에 있는 케이스만 담아야 한다.**

| 벌 | D11 케이스 | 증적 원장 | 실린 컷 |
|---|---|---|---|
| 포털 포함 | 48 | `capture-manifest.json` (전건) | 90 |
| 포털 제외 | 44 (포털 4건 없음) | `manifest-포털제외.json` | 83 |
| 포털향 | **4** (포털 4건만) | `manifest-포털향.json` | 7 |

전건 원장을 그대로 주면 생성기가 **멈춘다**:

```
[중단] I2 생성 실패: 증적 캡처 원장의 케이스ID 4건이 D11 단위시험 케이스 의 어느 케이스 행에도
없다 — KLID-AT-UT-029-01, ... 그대로 두면 그 캡처가 조용히 빠진다
```

**멈추는 것이 옳다** — 안 멈추면 그 캡처가 소리 없이 빠진 산출물이 나간다.

벌별 원장은 **그 벌의 D11 에서 뽑아** 만든다(손으로 목록을 적지 않는다). 스크립트는
`bin/split-manifest.py` 에 있다:

```bash
python3 frontend/bin/split-manifest.py \
    --captures ~/Desktop/CBD-캡처루트-YYYYMMDD \
    --out-root ~/Desktop/CBD-생성본-YYYYMMDDx
```

⚠ **순서가 있다** — D11 이 먼저 나와야 그 케이스 집합을 알 수 있다. 그래서
①벌마다 한 번 생성(그때 I2 에서 멈춰도 **D11 은 이미 나와 있다**) → ②원장 쪼개기 → ③다시 생성,
이 순서다.

⚠ **`증적 없는 케이스` 가 0 이 아니면 촬영이 덜 된 것이다** — 원장을 줄이지 말고 그 케이스를 찍어라.

---

## 9. ★I2 변환은 공용 서버에서 죽는다 — 로컬로 돌린다

증적을 data-URI 로 인라인하면 I2 md 가 **수십 MB**가 된다(90컷 = 48.1MB). 공용 변환 서버는
그 한 종에서 **504 TIMEOUT** 을 낸다(실측 2026-09-09: 254초에 504 · 재시도 1회 후에도 실패).
컷이 적은 벌(포털향 7컷)은 통과하므로, **벌마다 결과가 갈리는 것이 정상**이다.

```
[건너뜀] I2 변환 실패 [TIMEOUT] — 504 … (재시도 1회 후에도 실패)
[변환 결과] 13/14종 · 실패 1종
```

**로컬로 그 한 종만 다시 변환한다.** 전량 재실행하지 않는다 — 이미 만든 md 를 그대로 변환한다.

```bash
# ① 변환 대상 앱 (md_uml 루트가 그 앱이다)
cd <md_uml>            && npx vite --port 8080 --strictPort &
# ② 변환 서버 — ★APP_URL 이 그 앱을 가리켜야 한다
cd <md_uml>/convert-service && PORT=8799 APP_URL=http://localhost:8080 node src/server.js &
# ③ 끊긴 종만
node bin/reconvert.mjs --dir <산출 폴더> --docs I2 \
     --convert-url http://127.0.0.1:8799 --convert-timeout 1800
```

⚠ **`APP_URL` 을 빼면 `502 APP_UNREACHABLE` 이다** — 기본값 `http://127.0.0.1:8080` 에 아무것도
없으면 0.3초 만에 죽는다. 앱을 먼저 띄우고 그 주소를 준다.
⚠ **`127.0.0.1` 과 `localhost` 를 섞지 마라** — vite 가 `localhost` 로만 듣는 경우가 있다.
⚠ 끝나면 두 프로세스를 **끈다.**

⚠ **`_출처.json` 의 `변환_미생성` 이 남아 있으면 그 폴더의 변환 축은 「통과」가 아니라 「없음」이다.**
재변환에 성공하면 그 칸이 사라지고 `재변환` 기록이 붙는다.

---

## 10. 판정 기준 (구속)

- **증적은 1920×1080 화면 한 장.** 스크롤 분할·`fullPage` 둘 다 쓰지 않는다.
- **그 케이스가 검증하는 것이 화면에 보여야 한다.** 대상이 첫 화면 밖이면 `shotAt`(대상을
  화면 안으로 끌어온 뒤 한 장), 조각만 필요하면 `shotEl`. **두 장이 필요하면 컷을 둘로 나눈다.**
- **정상 상태만 찍는다.** 실패 화면은 케이스가 일부러 요구할 때만.
- **빈 화면을 조용히 찍는 것이 가장 나쁜 결과다.** 그래서 하네스가 사전 점검에서 멈춘다.
