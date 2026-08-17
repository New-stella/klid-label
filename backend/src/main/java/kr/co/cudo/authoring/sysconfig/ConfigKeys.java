package kr.co.cudo.authoring.sysconfig;

import java.util.Map;
import java.util.Set;

/**
 * 시스템 설정 키 화이트리스트 (V1.4 §5A.4).
 * <p>
 * 운영 UI(ManageConfig)에서 편집 가능한 키만 {@code ALLOWED} 에 등록한다.
 * 알 수 없는 키 PUT 시 INVALID_INPUT 으로 거부한다.
 * <p>
 * ⚠ <b>여기에 키 개수를 적지 않는다.</b> 키가 늘 때마다 서술이 낡아 실제와 어긋난다
 * (구 서술 "편집 가능한 5개 키만 등록한다" 가 실제 12개인 상태로 오래 남아 있었다).
 * 개수가 필요하면 {@code ALLOWED} 를 직접 세거나 테스트에서 단언한다.
 */
public final class ConfigKeys {

    public static final String BATCH_INTERVAL_SEC = "BATCH_INTERVAL_SEC";
    public static final String BATCH_CONCURRENCY  = "BATCH_CONCURRENCY";

    /**
     * Phase 1 (YOLO 정확도 개선) — 운영 UI 에서 조정 가능한 YOLO 추론 파라미터.
     * <ul>
     *   <li>{@code YOLO_CONF_THRESHOLD} : 정수 25~80 (사용 시 /100.0 → 0.25~0.80)</li>
     *   <li>{@code YOLO_IMGSZ} : 추론 입력 해상도 320~1920 px</li>
     *   <li>{@code YOLO_IOU} : 정수 30~80 (사용 시 /100.0 → 0.30~0.80)</li>
     * </ul>
     */
    public static final String YOLO_CONF_THRESHOLD = "YOLO_CONF_THRESHOLD";
    public static final String YOLO_IMGSZ          = "YOLO_IMGSZ";
    public static final String YOLO_IOU            = "YOLO_IOU";

    /**
     * FEAT-007 (SFR-08-03) 라벨링 정밀도 — 경계 세밀함.
     * <p>
     * Douglas-Peucker 단순화 epsilon(px). DECIMAL 타입(0.0~50.0, 기본 1.0).
     * 값이 클수록 폴리곤 점이 더 많이 제거되어 경계가 거칠어진다(=세밀함 낮춤).
     * 인식 민감도는 기존 {@link #YOLO_CONF_THRESHOLD} 가 담당.
     */
    public static final String POLYGON_SIMPLIFY_TOLERANCE = "POLYGON_SIMPLIFY_TOLERANCE";

    /**
     * 포털 전용 업로드 — 영상 프레임 추출 간격(초).
     * <p>
     * NUMBER 정수 1~600. 기본 5(V109 시드). 값이 클수록 추출 프레임이 줄어든다.
     * {@code SystemConfigService.getInt} 로 조회.
     */
    public static final String PORTAL_UPLOAD_FRAME_INTERVAL_SEC = "portal.upload.frame-interval-sec";

    /**
     * 폴리곤 오토라벨(온라인) — 한 프레임에서 SAM 분할을 시도하는 YOLO 검출 박스 개수 상한.
     * <p>
     * NUMBER 정수 1~100, 기본 20(V113 시드). YOLO 가 상한을 초과해 검출하면 상한까지만 SAM 분할하고
     * 나머지는 잘라 처리 예산·자원 소모를 제한한다(HIGH #1 / MED #5, CWE-770/400).
     * {@code SystemConfigService.getInt} 로 조회. 조회 실패 시 서비스 폴백 기본값 사용(fail-safe).
     */
    public static final String AUTOLABEL_POLYGON_MAX_BOXES = "autolabel.polygon.max-boxes";

    /**
     * 포털 보존기간 3종 — 보존일수가 지난 포털 데이터를 삭제 배치가 정리하는 기준(일). @design DFEAT-055
     *
     * <ul>
     *   <li>{@code portal.datamart.retention-days} : 데이터마트 영상에 포털 사용자가 저장한 라벨</li>
     *   <li>{@code portal.upload.retention-days} : 포털 사용자가 업로드한 자산 중 정상 처리(READY)된 것</li>
     *   <li>{@code portal.upload.failed-retention-days} : 같은 업로드 자산 중 처리 실패(FAILED)한 것</li>
     * </ul>
     *
     * <p>실패분을 별도 키로 둔 것은 <b>의도</b>다 — 실패 자산은 사용자가 다시 올리면 되는 잔여물이라
     * 정상 자산과 같은 기간을 붙잡아 둘 이유가 없다. 두 축을 한 키로 합치면 둘 중 하나는 반드시
     * 틀린 기간으로 운영된다.
     *
     * <h3>★ 하한이 1 인 이유 — 0·음수는 "즉시 삭제"다</h3>
     * <p>이 값은 <b>파괴적 배치</b>의 기준선이다. 0 이 들어가면 "오늘 것까지 지운다"가 되어 방금 저장한
     * 라벨·방금 올린 파일이 <b>다음 스윕에 곧바로 사라지고</b>, 음수면 미래 시각이 커트라인이 되어
     * 전량이 대상이 된다. 어느 쪽이든 복구 수단이 없으므로 값 자체를 입구에서 막는다
     * ({@link #NUMBER_RANGE} 하한 1). 상한(3650=10년)은 실질 무제한과 같되 오타로 들어온 천문학적
     * 값이 커트라인 계산을 넘치게 하는 것을 막는 상식선이다.
     *
     * <h3>★ 시드가 왜 필수인가 — 이 키들은 폴백하지 않는다</h3>
     * <p>다른 설정 키는 조회 실패 시 서비스 상수로 폴백하지만(fail-safe), 이 3키를 읽는 삭제 배치는
     * <b>값이 없으면 폴백하지 않고 그 회차를 건너뛴다</b>. 파괴적 기능이 fail-open 하면 "기본값 7 로
     * 알아서 지웠다"가 되기 때문이다. 그 대가로 <b>시드가 없으면 기능이 죽은 채 배포된다</b> —
     * 폴백 금지와 시드는 <b>세트</b>이며 한쪽만 두면 안 된다(V11 시드).
     */
    public static final String PORTAL_DATAMART_RETENTION_DAYS      = "portal.datamart.retention-days";
    public static final String PORTAL_UPLOAD_RETENTION_DAYS        = "portal.upload.retention-days";
    public static final String PORTAL_UPLOAD_FAILED_RETENTION_DAYS = "portal.upload.failed-retention-days";

    /**
     * 이벤트 필터 옵션에서 제외할 관제 대분류 코드(EVNT_CLS_CD) 목록.
     * <p>
     * JSON 배열 문자열(예 {@code ["08"]}). 기본값 {@code ["08"]}(배회) 시드.
     * 각 원소는 2자리 숫자({@code \d{2}})여야 하며 최대 20개까지 허용한다(CWE-20/770).
     * {@code SystemConfigService.getStringSet} 로 조회하며, 조회 실패 시
     * {@code EventTypeService} 가 기본값(08)으로 폴백한다(fail-safe).
     * <p>
     * 소스 상수(구 {@code EventTypeService.IGNORE_CLASS_CD})를 대체해 REVIEWER 가 설정 화면에서
     * 배포 없이 조정할 수 있게 한다.
     */
    public static final String EVENT_EXCLUDED_CLASS_CODES = "eventtype.excluded-class-codes";

    /**
     * R9 — 외부 비식별 솔루션 위탁 요청({@code POST /project})에 실리는 마스킹 옵션 3종.
     *
     * <p>벤더 확인 결과 실제로 의미 있게 조정 가능한 값은 아래 셋뿐이다. 규격상 필수 필드인
     * {@code exp_quality}·{@code exp_format} 은 <b>벤더가 미지원이라고 회신</b>했으므로 화면에
     * 노출하지 않고 요청에는 기존 규격 기본값을 계속 싣는다(설정 키로 열지 않는다).
     *
     * <ul>
     *   <li>{@code kpst.deid.masking-type} : NUMBER, <b>허용값 {0, 2, 3}</b>
     *       (0=색상 · 2=모자이크 · 3=블러). <b>1 은 벤더 미할당</b>이라 연속 범위가 아니다 —
     *       그래서 {@link #NUMBER_RANGE} 가 아니라 {@link #NUMBER_ALLOWED_VALUES} 로 판정한다.</li>
     *   <li>{@code kpst.deid.masking-range} : DECIMAL, 0.5 ~ 2.0 (마스킹 영역 배율).</li>
     *   <li>{@code kpst.deid.db-save} : NUMBER, <b>허용값 {0, 1}</b> (프레임 DB 저장 여부).</li>
     * </ul>
     *
     * <p>V178 시드 기본값은 현재 코드 상수와 동일하다(0 / 1.0 / 0) — 시드 적용만으로 위탁 동작이
     * 달라지지 않는다. 조회는 {@code SystemConfigService.getInt}/{@code getDouble} 이며 조회 실패 시
     * {@code KpstDeidentService} 가 {@code KpstProjectRequest.DEFAULT_*} 로 폴백한다(fail-safe).
     */
    public static final String KPST_DEID_MASKING_TYPE  = "kpst.deid.masking-type";
    public static final String KPST_DEID_MASKING_RANGE = "kpst.deid.masking-range";
    public static final String KPST_DEID_DB_SAVE       = "kpst.deid.db-save";

    /**
     * R11 — 운영 화면에서 조정하는 <b>외부 연동 서버 주소 4종</b>.
     *
     * <p><b>키 이름 = 애플리케이션 속성명</b>이다. 별도 키명을 만들면 "설정 키 ↔ 속성명" 매핑표가
     * 생기고 그 표가 두 번째 진실원이 되어, 한쪽만 갱신되는 순간 화면에서 바꾼 주소가 엉뚱한 연동에
     * 반영된다. 이름을 같게 두면 <b>"설정에 있으면 설정, 없으면 배포 기본값"</b> 이 표 없이 성립한다.
     *
     * <p>타입은 모두 <b>STRING</b> 이며, 값 검증은 {@code NUMBER_RANGE}/{@code DECIMAL_RANGE} 가 아니라
     * {@code IntegrationEndpointUrlValidator}(스키마·형식 판정)가 담당한다. <b>IP 대역으로는 막지
     * 않는다</b> — 이 연동들은 내부망에 있을 수 있고 망 통제는 인프라 계층 책임이다.
     *
     * <p>⚠ 비식별 키가 {@code kpst.deid.base-url} 인 것은 <b>의도</b>다. 구 키
     * {@code authoring.integration.deidentify.base-url} 은 주입 대상 0건인 빈을 구동해 실효가 없었다.
     *
     * <p>⚠ <b>시드하지 않는다</b> — 행이 없는 것이 정상이며 그때는 배포 기본값(@Value)이 쓰인다.
     * 그래서 {@code SystemConfigService.update} 는 이 키들에 한해 <b>행이 없으면 새로 만든다</b>
     * ({@link #DECLARED_TYPE} 참조).
     */
    public static final String KPST_DEID_BASE_URL              = "kpst.deid.base-url";
    public static final String INTEGRATION_AI_SERVER_BASE_URL  = "authoring.integration.ai-server.base-url";
    public static final String VLM_CLIENT_URL                  = "vlm.client.url";
    public static final String CONTROL_NOTIFY_URL              = "authoring.control-notify.url";

    /** 화이트리스트 — Service.update / getInt 진입 검증에 사용. */
    public static final Set<String> ALLOWED = Set.of(
            BATCH_INTERVAL_SEC, BATCH_CONCURRENCY,
            YOLO_CONF_THRESHOLD, YOLO_IMGSZ, YOLO_IOU,
            POLYGON_SIMPLIFY_TOLERANCE,
            PORTAL_UPLOAD_FRAME_INTERVAL_SEC,
            PORTAL_DATAMART_RETENTION_DAYS, PORTAL_UPLOAD_RETENTION_DAYS,
            PORTAL_UPLOAD_FAILED_RETENTION_DAYS,
            AUTOLABEL_POLYGON_MAX_BOXES,
            EVENT_EXCLUDED_CLASS_CODES,
            KPST_DEID_MASKING_TYPE, KPST_DEID_MASKING_RANGE, KPST_DEID_DB_SAVE,
            KPST_DEID_BASE_URL, INTEGRATION_AI_SERVER_BASE_URL,
            VLM_CLIENT_URL, CONTROL_NOTIFY_URL
    );

    /**
     * 시드 행 없이도 저장할 수 있는 키의 <b>선언 타입</b> (CONFIG_TYPE_CD).
     *
     * <p>{@code SystemConfigService.update} 는 원래 <b>기존 행에서</b> CONFIG_TYPE_CD 를 읽으므로 행이
     * 없으면 404 였다. 연동 주소 4종은 시드하지 않는 것이 설계라 그대로면 <b>한 번도 저장할 수 없다</b>.
     * 이 맵에 등록된 키만 최초 저장 시 이 타입으로 행을 만든다 — 그래서 임의의 키가 DB 에 생기지 않는다
     * (화이트리스트 {@link #ALLOWED} 통과가 선행 조건이다).
     */
    public static final Map<String, String> DECLARED_TYPE = Map.of(
            KPST_DEID_BASE_URL,              "STRING",
            INTEGRATION_AI_SERVER_BASE_URL,  "STRING",
            VLM_CLIENT_URL,                  "STRING",
            CONTROL_NOTIFY_URL,              "STRING"
    );

    /**
     * NUMBER(정수) 키별 허용 범위 [min, max] (DB설계서 §5A.4 정책).
     *
     * <p>{@code Map.of} 가 아니라 {@code Map.ofEntries} 인 것은 <b>키 개수 상한(10) 때문</b>이다 —
     * 여기가 상한에 닿아 있으면 다음 키를 등록하려는 사람이 뜻을 알 수 없는 컴파일 오류를 만나고,
     * 그때 급히 "범위 등록을 생략"하는 쪽으로 도망가면 그 키는 <b>무검증</b>이 된다
     * ({@code SystemConfigService.validateNumberRange} — 등록 누락 = 무제한 허용).
     */
    public static final Map<String, int[]> NUMBER_RANGE = Map.ofEntries(
            Map.entry(BATCH_INTERVAL_SEC,  new int[]{10, 3600}),
            Map.entry(BATCH_CONCURRENCY,   new int[]{1, 10}),
            Map.entry(YOLO_CONF_THRESHOLD, new int[]{25, 80}),
            Map.entry(YOLO_IMGSZ,          new int[]{320, 1920}),
            Map.entry(YOLO_IOU,            new int[]{30, 80}),
            Map.entry(PORTAL_UPLOAD_FRAME_INTERVAL_SEC, new int[]{1, 600}),
            // 포털 보존기간 3종 — 하한 1 은 "즉시 삭제" 차단이다(0·음수 금지, 위 상수 javadoc 참조).
            Map.entry(PORTAL_DATAMART_RETENTION_DAYS,      new int[]{1, 3650}),
            Map.entry(PORTAL_UPLOAD_RETENTION_DAYS,        new int[]{1, 3650}),
            Map.entry(PORTAL_UPLOAD_FAILED_RETENTION_DAYS, new int[]{1, 3650}),
            Map.entry(AUTOLABEL_POLYGON_MAX_BOXES, new int[]{1, 100})
    );

    /**
     * NUMBER(정수) 키별 <b>허용값 집합</b> — "범위"가 아니라 "목록"인 코드값 키에 쓴다.
     *
     * <p>{@link #NUMBER_RANGE} 로 대신할 수 없다. 예를 들어 마스킹 방식은 {0, 2, 3} 이라
     * {@code [0,3]} 범위로 두면 <b>벤더가 할당하지 않은 1 이 통과</b>한다.
     *
     * <p>{@code SystemConfigService.validateNumberRange} 는 이 맵과 {@link #NUMBER_RANGE} 를
     * <b>둘 다</b> 적용한다(등록된 쪽만 검사 — 한 키가 양쪽에 등록되면 둘 다 통과해야 한다).
     */
    public static final Map<String, Set<Integer>> NUMBER_ALLOWED_VALUES = Map.of(
            KPST_DEID_MASKING_TYPE, Set.of(0, 2, 3),
            KPST_DEID_DB_SAVE,      Set.of(0, 1)
    );

    /** DECIMAL(소수) 키별 허용 범위 [min, max]. */
    public static final Map<String, double[]> DECIMAL_RANGE = Map.of(
            POLYGON_SIMPLIFY_TOLERANCE, new double[]{0.0, 50.0},
            KPST_DEID_MASKING_RANGE,    new double[]{0.5, 2.0}
    );

    private ConfigKeys() {}
}
