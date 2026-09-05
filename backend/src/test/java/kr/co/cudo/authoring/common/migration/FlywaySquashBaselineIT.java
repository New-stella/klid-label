package kr.co.cudo.authoring.common.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 스쿼시(2026-08-13) 형상 고정 — <b>Flyway 가 실제로 무엇을 적용했는가</b>를 이력 테이블로 확인한다.
 *
 * <h3>왜 이 가드가 필요한가</h3>
 * <p>구 마이그레이션 180개(V0~V185)는 삭제하지 않고 {@code src/test/resources/db-archive/migration/} 에
 * 원문 보존했다 — 21개 테스트 클래스가 그 파일들을 읽어 백필 로직·DROP 순서·롤백 절차 주석을 검증하기
 * 때문이다. 그런데 <b>Flyway 의 classpath 스캔은 같은 리소스 경로를 여러 클래스패스 엔트리에서 병합</b>한다
 * (실제로 {@code src/test/resources/db/migration/V9001__test_seed_user_roles.sql} 이 그 성질로 테스트에서만
 * 합류한다). 아카이브를 {@code db/migration} 아래로 되돌리거나 {@code locations} 를 넓히면 구 180개가
 * <b>베이스라인 위에 다시 적용</b>되어, 신규 설치가 "만들었다 지우는" 왕복으로 되돌아간다.
 *
 * <p>그 회귀는 스키마 최종형이 비슷해 <b>기존 테스트로는 드러나지 않는다</b>. 이력 행을 직접 세는 이 가드가
 * 유일한 탐지 수단이다.
 *
 * <h3>기대 형상</h3>
 * <ul>
 *   <li>{@code V1} — 베이스라인(스키마 전량 + 시드 14행. 기록된 예외 2건으로 미사용 7종과
 *       {@code ls_com_cd} 시드 5행을 덜어낸 뒤의 수치다)</li>
 *   <li>{@code V2} — {@code CM_CODE} → {@code LS_COM_CD} 개명(신규 설치에서는 no-op)</li>
 *   <li>{@code V3} — 사용처 0 테이블 3종 DROP(신규 설치에서는 no-op)</li>
 *   <li>{@code V4} — 사용처 0 테이블 4종 DROP 2회차(신규 설치에서는 no-op)</li>
 *   <li>{@code V5} — 배치 큐·메타복제 발신함 비표준 컬럼 11종 표준용어 개명. V1 을 고치지 않으므로
 *       <b>신규 설치도 옛 이름으로 만들어진 뒤 여기서 개명</b>된다(no-op 이 아니다)</li>
 *   <li>{@code V6} — {@code LS_DATA_LBL_AI_INFO} 를 {@code LS_DATA_LBL} 로 흡수</li>
 *   <li>{@code V7} — {@code LS_MON_NOTI_ACML.STTS_CD} 폭 20 → 16(표준도메인 정합). V5 와 같은 이유로
 *       <b>신규 설치도 20 으로 만들어진 뒤 여기서 축소</b>된다(no-op 이 아니다)</li>
 *   <li>{@code V8} — {@code LS_WEBHOOK_IDEMPOTENCY.APLY_DT} → {@code APLCN_DT} 개명(표준용어 정합).
 *       V5·V7 과 같은 이유로 <b>신규 설치도 옛 이름으로 만들어진 뒤 여기서 개명</b>된다(no-op 이 아니다)</li>
 *   <li>{@code V9} — {@code LS_TASK_ASSIGNMENT} → {@code LS_TASK_ALTMNT} ·
 *       {@code LS_TASK_EVENT_LOG} → {@code LS_TASK_EVNT_LOG} 개명(표준용어 정합). 테이블뿐 아니라
 *       시퀀스·제약·인덱스 13종을 함께 옮긴다. V5·V7·V8 과 같은 이유로 <b>신규 설치도 옛 이름으로
 *       만들어진 뒤 여기서 개명</b>된다(no-op 이 아니다)</li>
 *   <li>{@code V10} — {@code LS_ISSUE_COMMENT.DATA_ISSUE_SN} 에 FK(ON DELETE RESTRICT) 부착
 *       (설계 ERD-023 이 규정한 참조 무결성이 구현에서만 빠져 있었다). 고아 댓글을 먼저 정리한 뒤
 *       제약을 건다. V5·V7·V8·V9 와 같은 이유로 <b>신규 설치도 FK 없이 만들어진 뒤 여기서 부착</b>된다
 *       (no-op 이 아니다)</li>
 *   <li>{@code V11} — 포털 보존기간 설정 3키 시드({@code portal.datamart.retention-days} ·
 *       {@code portal.upload.retention-days} · {@code portal.upload.failed-retention-days}).
 *       이 3키를 읽는 삭제 배치는 값이 없을 때 상수로 폴백하지 않고 그 회차를 건너뛰므로
 *       (파괴적 기능의 fail-open 차단), <b>시드가 없으면 기능이 죽은 채 배포된다</b> — 즉
 *       「폴백 금지」와 이 시드는 세트다. {@code ON CONFLICT DO NOTHING} 이라 재적용이 운영자가
 *       바꿔 둔 값을 되돌리지 않는다</li>
 *   <li>{@code V12} — {@code LS_ACNT_USER.LAST_LGN_DT}(최종로그인일시) 신설. 사용자 관리 화면이
 *       「최신 로그인」 컬럼을 그리는데 그 데이터가 존재하지 않아 등록일을 폴백으로 표시하고 있었다
 *       (거짓 표기). nullable 이며 <b>기존 행을 백필하지 않는다</b> — 이 컬럼 이전의 접속 기록은
 *       어디에도 없어 무엇을 넣어도 지어낸 값이다. NULL 허용 + DEFAULT 없는 ADD COLUMN 이라
 *       하위호환이고(구 jar 는 컬럼을 모르는 SQL 을 만든다) 롤링 재기동으로 배포할 수 있다</li>
 *   <li>{@code V13} — 추론 입력 해상도 설정 키 {@code YOLO_IMGSZ} 폐지(V1 이 시드한 1행 DELETE).
 *       ai-server 의 YOLOX 로더가 입력 크기를 640 고정으로 추론해 <b>조정해도 결과가 바뀌지 않는</b>
 *       설정이었다. 화이트리스트에서 이미 뺐으므로 조회·수정 경로는 닫혔고, 이 파일은 조회에
 *       잡히지 않는 죽은 행을 남기지 않기 위한 정리다. DELETE 라 대상이 없어도 성공(멱등)</li>
 *   <li>{@code V14} — 외부 산출물 이관 도메인의 저장소 신설. 신규 테이블 둘
 *       ({@code LS_OTSD_CTGRY_MPNG} 외부 분류 대응 · {@code LS_OTSD_DATST_TRNSF_HSTRY} 이관 이력)과
 *       {@code LS_RAW_DATA_STATUS.DE_IDNTF_CMPTN_YN}(비식별화완료여부) 컬럼을 더한다. 그 컬럼은
 *       기본값이 {@code 'Y'} 라 <b>기존 전 행이 영향을 받지 않고 백필도 필요 없다</b> — 이관 경로로
 *       원본이라고 지정해 들어온 영상만 {@code 'N'} 으로 시작해 비식별이 끝날 때까지 검수 승인만
 *       막힌다(ADR-048). 신규 테이블 생성과 상수 DEFAULT ADD COLUMN 이라 하위호환이고 롤링
 *       재기동으로 배포할 수 있다</li>
 *   <li>{@code V15} — 이벤트 유형·카테고리·라벨 마스터 시드. 같은 내용이 {@code db/seed/dev-seed.sql}
 *       에만 있어 <b>온프렘 배포 스냅샷에 실리지 않았다</b> — 그 파일은 클린 DB 에 마이그레이션을
 *       전량 적용한 뒤 뜬 덤프라 마이그레이션 밖의 시드는 담기지 않는다. 실측으로
 *       {@code deploy/onprem/db/schema.sql} 의 두 표가 비어 있었고, 그대로 설치하면 라벨 마스터
 *       0건·이벤트 유형 0건으로 기동해 라벨을 고를 수 없고 이벤트 필터가 빈 채로 뜬다.
 *       전부 {@code ON CONFLICT DO NOTHING} 이라 이미 시드된 DB(로컬·dev)에서는 no-op 이고
 *       운영자가 바꿔 둔 이름·색·형태를 되돌리지 않는다</li>
 *   <li>{@code V20} — {@code LS_PORTAL_USER_LABEL} 에 {@code LBL_ID}(라벨 마스터 참조) ·
 *       {@code TRCK_ID}(트랙아이디) 신설. 포털 다운로드 산출을 검수 승인 학습데이터와 같은
 *       구조로 통일하면서, 산출 어노테이션이 싣는 분류·트랙 식별자를 담을 자리가 포털
 *       저장소에만 없다는 공백이 드러났다. 둘 다 nullable 이고 <b>기존 행을 백필하지 않는다</b>
 *       — 라벨명 소급 매칭은 동명이인·비활성 마스터에 다른 분류로 연결될 수 있어 빈 값보다
 *       나쁘다(재저장하면 자연 복구). FK 를 걸지 않아 마스터 수명과 독립이며, NULL 허용 +
 *       DEFAULT 없는 ADD COLUMN 이라 하위호환이고 롤링 재기동으로 배포할 수 있다</li>
 *   <li>{@code V21} — {@code LS_MNGR_PSWD}(관리자비밀번호) 신설. 관리자 유효창 발급에 쓰는 공유
 *       자격이 배포 설정에만 있어 <b>재배포 말고는 바꿀 길이 없었다</b>. 이 표가 그 자격을 데이터로
 *       옮긴다. <b>행은 최대 1개</b>이며 그것을 기본키 + {@code mngr_pswd_sn = 1} 체크 제약
 *       <b>두 겹</b>으로 강제한다(기본키만이면 1·2·3 이 나란히 서고, 체크만이면 값이 전부 1 인 행이
 *       여러 개 들어간다). <b>시드하지 않는다</b> — 행이 없는 것이 정상이고 그때는 배포 설정값으로
 *       폴백하므로, 이 변경 이후에도 기존 배포는 아무것도 달라지지 않는다. 세대·판수 컬럼을 두지
 *       않는 것도 결정이다(교체 시 기존 유효창 무효화는 서명이 현재 자격에 의존하게 해서 이룬다).
 *       신규 테이블 생성뿐이라 하위호환이고 롤링 재기동으로 배포할 수 있다</li>
 *   <li>{@code V22} — {@code LS_USER_ROLE.MDFR_ID}(수정자아이디) 신설. 역할 변경 기록에 <b>대상만
 *       있고 주체가 없어</b>, 누가 누구를 관리자로 올렸는지 알려면 별도 유효창 발급 로그와 시각으로
 *       이어 붙여야 했다. 권한 상승은 감사에서 가장 중요한 사건이라 그 자리를 데이터로 남긴다.
 *       값의 출처는 인계 토큰 subject 하나이며 <b>요청 바디에서 오지 않는다</b>(바디 값은 위조 가능).
 *       <b>nullable 이고 백필하지 않는다</b> — 과거 변경의 주체를 알 방법이 없고, NOT NULL + 기본값은
 *       사실이 아닌 주체를 박는다. null 이 곧 "이 컬럼이 생기기 전에 바뀐 행" 이라는 정보다.
 *       변경 <b>이력 표</b>는 만들지 않는다(보존 주기·조회 창구·정리 배치를 함께 정해야 하는 별개
 *       결정이라, 추정으로 대신하지 않는다). 기본값 없는 nullable 컬럼 추가라 표를 다시 쓰지 않고
 *       하위호환이며 롤링 재기동으로 배포할 수 있다</li>
 *   <li>{@code V23} — {@code LS_AI_SRVR}(AI서버) · {@code LS_AI_SRVR_ALTMNT}(AI서버배정) 신설.
 *       추론 서버가 장비 두 대에 이중화로 올라가는데 저작도구가 부르는 주소는 <b>설정값 하나뿐</b>이라
 *       두 대를 나눠 쓸 배선이 없었다. 이 두 표가 그 목록과 「어느 영상을 어느 노드로 보냈는가」를
 *       데이터로 옮긴다. <b>원장만 세우고 분산 자체는 아직 하지 않는다</b>. 배정은
 *       {@code raw_sn} 유니크다 — 추적기가 노드 프로세스의 로컬 메모리에 있어 한 영상의 프레임이
 *       두 노드로 흩어지면 추적이 끊기므로, 「영상당 배정 한 건」이 그 보장의 전부다.
 *       <b>시드하지 않는다</b> — 환경마다 추론 서버 주소가 다른데 마이그레이션은 애플리케이션
 *       설정을 읽지 못한다. 대신 기동 시 원장이 비어 있으면 애플리케이션이 기존 설정값으로 노드
 *       하나를 세우므로, 이 변경 이후에도 기존 배포는 <b>설정값 그대로</b> 동작한다.
 *       신규 테이블 생성뿐이라 하위호환이고(구 jar 는 이 표를 모른다) 롤링 재기동으로 배포할 수 있다</li>
 *   <li>{@code V24} — {@code LS_AI_SRVR_USG}(AI서버용도) 신설 + {@code LS_AI_SRVR.WTNG_NOCS} 제거
 *       + {@code CHCK_SCS_NOCS} 추가. V23 은 노드마다 대기건수 <b>하나</b>를 뒀는데, 추론 서버는
 *       장비 안에서 실행을 <b>용도별로</b>(일괄 처리 / 화면에서 쓰는 요청) 갈라 두었다. 노드를 고를 때
 *       보는 값은 그 요청 자신의 용도에 해당하는 부하뿐이며, 두 값을 합쳐 보면 장비 안에서 갈라 놓은
 *       것을 부르는 쪽에서 다시 붙이는 셈이 되어 <b>일괄 처리가 밀린 장비를 화면 요청이 피할 이유가
 *       없는데도 피하게</b> 된다. 그래서 저장 자리도 장비 x 용도 조합이다. 처리 중 건수를 함께 두는
 *       이유는 용도마다 동시 처리가 하나여서 <b>대기가 없어도 이미 하나를 잡고 있으면 바쁘기</b>
 *       때문이다(실효 부하 = 처리중 + 대기). 부모 컬럼을 남기지 않고 지운 것은 읽는 코드가 아직
 *       하나도 없어 지금이 가장 싸고, 남겨 두면 다음 사람이 그 값을 「노드 전체 부하」로 읽어 두 용도를
 *       다시 합치기 때문이다. ⚠ <b>하위호환이 아니다</b> — 구 jar 는 그 컬럼을 엔티티에 매핑하고 있어
 *       기동 검증에서 실패하므로 롤링이 아니라 전 노드 교체 후 적용해야 한다(관측이 꺼진 채 나가므로
 *       기능 영향은 없다)</li>
 *   <li>{@code V26} — {@code LS_AI_SRVR_ALTMNT.SRVR_ID} 의 외래키({@code ON DELETE RESTRICT}) 제거.
 *       그 배정은 <b>처리가 도는 동안</b> 프레임을 한 노드에 묶어 두는 자리이고, 처리가 끝나면 그
 *       묶음은 의미가 없다(추적기 상태가 그 프로세스 메모리에 있었고 이미 사라졌다). 끝난 이력까지
 *       삭제를 막으면 <b>한 번이라도 영상을 처리한 장비는 영구히 교체 불가</b>가 된다. 같은 이유로
 *       {@code LS_WEBHOOK_IDEMPOTENCY.SRVR_ID} 는 처음부터 외래키를 걸지 않았고 그 축에 맞춘 것이다.
 *       ⚠ <b>제약만</b> 없앤다 — 표·컬럼·행은 그대로이고 배정 이력은 남는다. 유령 장비로의 신규
 *       배정 차단은 INSERT 조건({@code WHERE EXISTS})으로 옮겼다. 순수 완화라 <b>구 jar 에 무해</b>하다</li>
 *   <li>{@code V27} — {@code LS_MARKING} 에서 {@code EVNT_NM} · {@code VIDEO_FILE_PATH_NM} 제거 +
 *       {@code REG_USER_NO} 를 {@code bigint} → {@code varchar(100)} 로 확대. 두 칸은 영상 행
 *       ({@code LS_DATA_RAW.EVNT_TYPE_CD} · {@code RAW_FILE_PATH_NM})에 있는 값을 마킹 행에 <b>베껴
 *       두던 중복</b>이라 영상 쪽이 바뀌면 어긋났고, 포털 업로드 경로에는 둘 다 채울 값이 없는데
 *       NOT NULL 이라 <b>저장 자체가 막혀</b> 있었다. 응답의 두 값은 영상 행에서 조달하므로 계약은
 *       무변경이다. 폭 확대는 포털 토큰 주체가 숫자가 아니어서다 — 숫자로 두면 파싱 실패로 조용히
 *       null 이 되어 소유자 없는 마킹이 저장된다. ⚠ <b>컬럼 제거는 되돌릴 수 없다</b>(값이 함께
 *       사라진다. 되살리려면 영상 행 조인으로 백필한다)</li>
 *   <li>{@code V28} — 포털 채널 전용 표를 공용 원장에 흡수하기 위한 스키마 정합(ADR-058):
 *       {@code LS_DATA_RAW} 에 {@code PORTAL_USER_NO varchar(100)} 신설(+부분 인덱스),
 *       {@code LS_DATA_LBL.REG_USER_NO} 를 {@code bigint} → {@code varchar(100)},
 *       {@code LS_TUS_UPLOAD.USER_NO} 를 {@code varchar(64)} → {@code varchar(100)}.
 *       셋 다 포털이 발급한 토큰 주체가 숫자가 아니라서다 — 숫자로 두면 파싱 실패로 조용히 null 이
 *       되어 <b>소유자 없는 행</b>이 저장되고 소유자 스코프 인가가 어긋난다. ⚠ <b>구 전용 표는 지우지
 *       않는다</b> — 드롭은 되돌릴 수 없어 별도 단계이고, 이 파일은 순수 확장이라 중간 상태가 멀쩡하다
 *       (V27 이 한 파일에 묶은 것은 중간 상태가 고장나는 경우였다)</li>
 *   <li>{@code V29} — {@code LS_DATA_LBL.LBL_NM} 의 NOT NULL 해제(ADR-058 코드 이관 짝). 포털 업로드
 *       자산의 수동 라벨은 마스터를 참조하지 않는 자유 입력이라 <b>이름 없이 도형만</b> 그리는 것이
 *       정상 동선인데, 흡수처인 이 원장이 NOT NULL 이라 저장 자체가 막혔다. 포털이 기본값을 지어
 *       채우는 안은 기각했다(지어낸 값이 쌓이면 진짜 라벨명과 구분되지 않는다). 내부 채널의 라벨
 *       생성 통로는 전부 이름을 채우므로 <b>관제 라벨에 NULL 이 새로 생기지 않는다</b> — 제약만 풀고
 *       값은 그대로다. 순수 완화라 구 jar 에 무해하다</li>
 *   <li>{@code V31} — 마킹 산출물 일괄 가져오기의 작업 원장 두 표 신설(ADR-053):
 *       {@code LS_EBLC_ULD_JOB}(일괄업로드작업) + {@code LS_EBLC_ULD_JOB_ARTCL}(작업 항목).
 *       적재 요청이 곧바로 반환하고 실제 적재는 뒤에서 항목별로 진행하므로 <b>어디까지 되었는지와
 *       무엇이 왜 실패했는지</b>를 담아 둘 자리가 필요하다. ⚠ <b>{@code LS_MARKING} 은 건드리지
 *       않는다</b> — 외부 마킹이 쓰는 예약 상태는 그 컬럼에 CHECK 제약이 없고 활성 부분 유니크
 *       ({@code WHERE stts_cd IN ('PENDING','VLM_REQUESTED')})가 이미 그 값을 배제해, 상태값 추가가
 *       애플리케이션 상수만으로 성립한다(제약을 새로 걸면 오히려 그 성질이 깨진다). 순수 신설이라
 *       구 jar 에 무해하다</li>
 *   <li>{@code V9001} — 테스트 전용 시드(테스트 클래스패스에만 존재)</li>
 *   <li>{@code V9002} — 테스트 전용 시드(역할 해석 표본 — 진입 시 자동 등록 이후 "시드에 없는
 *       숫자 sub" 가 더 이상 무권한을 뜻하지 않게 되어 표본을 명시적으로 심는다)</li>
 * </ul>
 *
 * <p><b>새 마이그레이션을 추가할 때</b>는 위 목록과 아래 두 단언에 <i>한 줄씩 명시적으로</i> 더한다.
 * 개수 비교나 접두 매칭으로 느슨하게 바꾸면 아카이브 유입을 못 잡아 이 가드의 존재 이유가 사라진다.
 *
 * @design D9
 */
@SpringBootTest
@ActiveProfiles("local")
class FlywaySquashBaselineIT {

    /** Flyway 가 읽는 <b>유일한</b> 마이그레이션 디렉터리(운영 배포본). */
    private static final Path LIVE_MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    /** 구 180개 원문 보존처 — Flyway 는 읽지 않고 테스트만 읽는다. */
    private static final Path ARCHIVE_DIR = Path.of("src/test/resources/db-archive/migration");

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Test
    @DisplayName("Flyway는_스쿼시본만_적용한다 — 아카이브_180개가_다시_적용되지_않는다")
    void Flyway는_스쿼시본만_적용한다() {
        // given: 컨텍스트 기동 시 Flyway 가 이미 migrate 를 마쳤다
        List<String> applied = new JdbcTemplate(controlDataSource).queryForList(
                "SELECT version FROM flyway_schema_history WHERE type = 'SQL' ORDER BY installed_rank",
                String.class);

        // then: 운영 마이그레이션 + 테스트 전용 시드 1개(9001). 아카이브가 합류하면 여기가 180+ 로 부푼다.
        //   ★ 정상적인 신규 마이그레이션을 추가할 때는 이 목록에 <의도적으로> 한 줄을 더한다.
        //     느슨하게(예: hasSizeGreaterThan) 바꾸지 말 것 — 아카이브 유입 탐지력이 사라진다.
        assertThat(applied)
                .as("Flyway 가 적용한 SQL 마이그레이션 — 아카이브가 db/migration 으로 새어 들어오면 실패한다")
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
                        "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32",
                        "9001", "9002");
    }

    @Test
    @DisplayName("운영_마이그레이션_디렉터리에는_스쿼시본과_그_이후_파일만_있다")
    void 운영_마이그레이션_디렉터리에는_스쿼시본과_그_이후_파일만_있다() throws Exception {
        // when
        List<String> live = listSql(LIVE_MIGRATION_DIR);

        // then: 여기에 구 파일(V0·V4~V185 등)이 되돌아오면 즉시 잡힌다.
        //   ★ 신규 마이그레이션 추가 시 이 목록에 <의도적으로> 한 줄을 더한다(느슨하게 바꾸지 말 것).
        assertThat(live)
                .as("배포되는 마이그레이션 파일 목록")
                .containsExactly(
                        // ⚠ 순서는 <파일명 사전순>이다(listSql 의 sorted). 'V10' 은 두 자리라
                        //   'V1__' 보다 앞에 온다('0' < '_') — 버전 번호 순이 아니다.
                        "V10__add_ls_issue_comment_issue_fk.sql",
                        "V11__seed_portal_retention_config.sql",
                        "V12__add_ls_acnt_user_last_lgn_dt.sql",
                        "V13__drop_yolo_imgsz_config.sql",
                        "V14__add_external_import_tables_and_deident_cmptn_yn.sql",
                        "V15__seed_event_type_and_label_master.sql",
                        "V16__align_control_ingest_bit_thmb_ogcd.sql",
                        "V17__replace_completed_video_thumbnail_with_deident_frame.sql",
                        "V18__add_verification_event_question_catalog.sql",
                        "V19__simplify_label_preset_to_event_and_labels.sql",
                        "V1__baseline.sql",
                        "V20__add_portal_user_label_master_and_track.sql",
                        "V21__add_ls_mngr_pswd.sql",
                        "V22__add_ls_user_role_mdfr_id.sql",
                        "V23__add_ls_ai_srvr.sql",
                        "V24__split_ai_srvr_load_by_usage.sql",
                        "V25__add_webhook_idempotency_srvr_id.sql",
                        "V26__drop_ls_ai_srvr_altmnt_fk.sql",
                        "V27__widen_ls_issue_comment_role_admin.sql",
                        "V28__drop_marking_denormalized_columns_and_widen_reg_user_no.sql",
                        "V29__absorb_portal_upload_into_common_ledgers.sql",
                        "V2__rename_cm_code_to_ls_com_cd.sql",
                        "V30__relax_ls_data_lbl_label_name_not_null.sql",
                        "V31__add_ls_eblc_uld_job.sql",
                        "V32__ls_acnt_user_no_seq_and_user_id_unique.sql",
                        "V3__drop_unused_tables.sql",
                        "V4__drop_unused_tables_round2.sql",
                        "V5__rename_queue_outbox_columns_to_std.sql",
                        "V6__absorb_lbl_ai_info_into_ls_data_lbl.sql",
                        "V7__narrow_mon_noti_acml_stts_cd_to_std_width.sql",
                        "V8__rename_webhook_idempotency_aply_dt_to_aplcn_dt.sql",
                        "V9__rename_task_tables_to_std_terms.sql");
    }

    @Test
    @DisplayName("구_마이그레이션_180개_원문이_아카이브에_보존돼_있다")
    void 구_마이그레이션_원문이_보존돼_있다() throws Exception {
        // when
        List<String> archived = listSql(ARCHIVE_DIR);

        // then: 21개 테스트 클래스가 이 파일들을 직접 읽는다 — 지우면 그 회귀 커버리지가 사라진다.
        //       (개수 자체를 고정하는 이유: 일부만 지워도 그 파일을 읽는 테스트만 깨져서
        //        "왜 없어졌는지" 를 되짚기 어렵기 때문이다.)
        assertThat(archived)
                .as("보존된 구 마이그레이션 원문 개수")
                .hasSize(180);
        assertThat(archived)
                .as("스쿼시가 접은 구간의 처음과 끝")
                .contains("V0__init.sql", "V185__align_control_ingest_contract_and_export_frme_cnt_comment.sql");
    }

    @Test
    @DisplayName("마이그레이션_파일에_Flyway_플레이스홀더_표기가_없다")
    void 마이그레이션_파일에_플레이스홀더_표기가_없다() throws Exception {
        // given: Flyway 는 ${...} 를 <주석 안이라도> 플레이스홀더로 해석한다.
        //   실측 — 베이스라인 헤더 주석의 ${DB_SCHEMA} 하나로 "No value provided for placeholder" 가 나
        //   파일 전체가 적용되지 않았다. 스키마 설명을 주석에 적을 때 재발하기 쉬워 가드로 고정한다.
        for (String name : listSql(LIVE_MIGRATION_DIR)) {
            String sql = Files.readString(LIVE_MIGRATION_DIR.resolve(name));

            // then
            assertThat(sql)
                    .as("%s 에 ${...} 표기가 있으면 Flyway 가 파싱 단계에서 실패한다", name)
                    .doesNotContain("${");
        }
    }

    private List<String> listSql(Path dir) throws Exception {
        assertThat(Files.isDirectory(dir))
                .as("디렉터리(%s)가 존재해야 한다 (테스트 작업 디렉토리=backend 모듈 루트)", dir.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }
}
