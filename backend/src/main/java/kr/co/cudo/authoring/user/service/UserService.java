package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.user.dto.UserProfileResponse;
import kr.co.cudo.authoring.user.dto.UserSummaryResponse;
import kr.co.cudo.authoring.user.dto.UserUpdateRequest;
import kr.co.cudo.authoring.user.dto.WorkerSummaryResponse;
import kr.co.cudo.authoring.user.entity.LsUserRole;
import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class UserService {

    /**
     * 감사 주체({@code MDFR_ID}) 최대 길이 — 표준도메인 <b>식별자V30</b> 이 정한다.
     * 값을 바꾸려면 컬럼 정의(V22)와 함께 바꿔야 한다.
     */
    private static final int MAX_ACTOR_LENGTH = 30;

    private final UserRepository userRepository;
    private final LsUserRoleRepository lsUserRoleRepository;
    private final UserRoleResolver userRoleResolver;

    public UserProfileResponse getProfile(TokenClaims claims) {
        Long userNo = parseUserNo(claims.sub());
        LsAcntUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        // Phase 3 — LS 미배정 INTERNAL 사용자는 role 이 null 일 수 있어 null-safe roleName() 사용(NPE 방어).
        return UserProfileResponse.of(user, claims.roleName(), claims.channel().name());
    }

    /**
     * 사용자 프로필 단건 조회 (REVIEWER 의 관리 화면).
     *
     * <p>버그 수정(역할 분리 Phase 3 DEV_FIX) — 응답 역할/채널을 호출자({@code claims})가 아니라
     * <b>피조회 사용자</b>의 실제 LS 역할로 채운다. 이전 구현은 호출자(REVIEWER)의 역할/채널을 그대로
     * 응답에 넣어 관리 화면에서 모든 사용자가 호출자 역할로 오표시되는 결함이 있었다.
     *
     * <p>역할 출처는 {@code searchUsers} 와 동일하게 {@link LsUserRoleRepository#findByUserNo(Long)} 다.
     * 미배정이면 fail-closed 로 null(미배정). 채널은 피조회 사용자의 컨텍스트가 없어 빈 값으로 둔다
     * ({@code update} 응답과 동일 컨벤션).
     */
    public UserProfileResponse getById(Long userNo, TokenClaims claims) {
        LsAcntUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        String role = lsUserRoleRepository.findByUserNo(userNo)
                .map(LsUserRole::getRoleCd)
                .orElse(null);
        return UserProfileResponse.of(user, role, "");
    }

    public List<WorkerSummaryResponse> listWorkersWithTaskCount() {
        return userRepository.findAllWorkersWithTaskCount().stream()
                .map(WorkerSummaryResponse::from)
                .toList();
    }

    /**
     * 사용자 마스터 페이징 검색 (/v1/users) — 사용자 관리 화면. [@design AC-1018]
     *
     * <h3>★ 역할 필터는 조회 계층에서 걸린다 (구 인메모리 필터 폐기)</h3>
     * <p>구 구현은 {@code searchByKeyword} 로 <b>한 페이지를 먼저 가져온 뒤</b> 그 안에서 역할을
     * 걸렀다. 증상이 둘이었다 — ①<b>1페이지 밖의 해당 역할 사용자에게 도달할 수 없다</b>(20건씩
     * 끊어 오는데 그 안에 없으면 결과가 비고 뒤 페이지로도 갈 수 없다) ②<b>총건수가 필터 이전
     * 합계</b>라 "3건 표시 / 총 87건" 이 된다. 「작업자 모수가 적어 실용상 허용」이라는 구 전제는
     * 역할이 4종이 되면서(ADR-055) 성립하지 않는다 — 역할별로 거르는 동선 자체가 그때 생겼다.
     *
     * <p>이제 필터·페이징·총건수가 모두 {@link UserRepository#searchByKeywordAndRole} 한 문장에서
     * 나온다. <b>여기서 다시 거르지 않는다</b> — 거르면 그 순간 총건수와 목록이 또 갈린다.
     *
     * <h3>바뀌지 않은 것</h3>
     * <ul>
     *   <li><b>N+1 방지</b> — 표시용 역할 코드는 페이지 결과의 userNo 를 모아 한 번에 조회한다
     *       (페이지당 1회). 필터가 조회로 내려갔다고 이 조회를 없앨 수 없다: 필터가 없는 경우에도
     *       각 행의 역할을 <b>표시</b>해야 한다.</li>
     *   <li><b>미배정(LS 역할 없음)은 null 로 매핑</b>하고 기본 역할을 부여하지 않는다(인가와 정합).
     *       어떤 역할 필터에도 걸리지 않는 것도 그대로다.</li>
     *   <li>{@code role} 미지정(null/blank)이면 전체 반환 — 기본 동선 무변경.</li>
     * </ul>
     *
     * <p>보안: {@code role} 은 Controller 의 {@code @Pattern} 화이트리스트로 사전 검증된 값만
     * 도달하고 리포지토리도 파라미터 바인딩만 쓴다(CWE-89).
     */
    public Page<UserSummaryResponse> searchUsers(String keyword, String role, Pageable pageable) {
        Page<LsAcntUser> page = userRepository.searchByKeywordAndRole(keyword, role, pageable);
        List<Long> userNos = page.getContent().stream().map(LsAcntUser::getUserNo).toList();
        Map<Long, String> roleByUserNo = new HashMap<>();
        if (!userNos.isEmpty()) {
            for (LsUserRole r : lsUserRoleRepository.findByUserNoIn(userNos)) {
                roleByUserNo.put(r.getUserNo(), r.getRoleCd());
            }
        }
        // Page.map — 페이징 메타(총건수·페이지수·정렬)를 조회 결과 그대로 물려받는다.
        //   여기서 PageImpl 을 새로 만들면 총건수를 다시 정해야 하고, 그 자리가 곧 구 결함이었다.
        return page.map(u -> UserSummaryResponse.from(u, roleByUserNo.get(u.getUserNo())));
    }

    /**
     * 사용자 역할 변경 (REVIEWER 의 사용자 관리 화면).
     *
     * <p>저작도구 소유 {@code LS_USER_ROLE} 만 갱신한다 — 저작도구 인가 역할의 단일 진실원이다.
     * 구 구조에서 쓰던 관제 권한 매핑 테이블(delete/insert)과 사용자 마스터의 활성여부(UPDATE)
     * 쓰기는 제거됐고, 그 테이블 2종은 V165 로 스키마에서도 삭제됐다.
     *
     * <p>사용자 마스터({@code LS_ACNT_USER}) 는 V169 로 저작도구 소유가 됐지만 <b>이 화면은 여전히
     * 쓰지 않는다</b> — 표시 정보를 채우는 주체는 역할 클레임 시점의 자동등록 하나로 유지한다
     * (쓰기 주체가 둘이면 관제 인계값과 화면 수정값이 서로를 덮는다).
     *
     * <ul>
     *   <li>{@code role}: 제공된 경우만 변경. {@code LsUserRoleRepository.upsertRole} 원자 upsert 로
     *       동시성(PK race) 안전하게 부여/변경한다.</li>
     *   <li>{@code role} 미제공: 기존 LS 역할(없으면 null 미배정)을 그대로 응답한다.</li>
     * </ul>
     *
     * <p>보안: role 은 Controller/DTO {@code @Pattern} 화이트리스트(ADMIN|REVIEWER|WORKER|PORTAL_USER)로
     * 사전 검증된 값만 도달하며 native upsert 는 파라미터 바인딩만 사용한다 (SQL Injection 차단).
     * 역할 변경(특히 강등)은 감사 추적을 위해 변경 전/후 역할을 info 로그로 남긴다 (PII·토큰 미출력).
     *
     * <h3>★ 바꾼 사람이 남는다 (@design AC-1018)</h3>
     * <p>그동안 이 경로는 <b>대상만 남기고 주체를 남기지 않았다</b> — 누가 누구를 관리자로 올렸는지
     * 알려면 별도 유효창 발급 로그와 시각으로 이어 붙여야 했다. 이제 주체를
     * {@code LS_USER_ROLE.MDFR_ID} 에 <b>역할과 같은 문장에서</b> 쓰고 로그에도 함께 싣는다.
     *
     * <p>이 진입점은 주체를 인자로 <b>받지 않고</b> 인증 컨텍스트에서 취한다
     * ({@link #currentSubject()}) — 시험 하네스·내부 호출처럼 요청 컨텍스트가 없는 자리는 null 이
     * 되어 그 사실 그대로 남는다.
     *
     * @design API-004
     * @design AC-124
     * @design AC-1018
     */
    @Transactional("controlTransactionManager")
    public UserProfileResponse update(Long userNo, UserUpdateRequest req) {
        return update(userNo, req, currentSubject());
    }

    /**
     * 역할 변경 — <b>주체를 명시로 받는 진입점</b>. 상세 계약은 {@link #update(Long, UserUpdateRequest)}.
     *
     * <p>운영 창구(Controller)는 이 쪽을 쓰며 인증 주체({@code TokenClaims.sub})를 실어 준다.
     *
     * <h3>★ actor 는 요청 바디에서 오지 않는다</h3>
     * <p>바디 값은 위조 가능하다 — 그것을 받으면 감사 기록이 "본인이 주장한 사람" 이 된다. 그래서
     * {@code UserUpdateRequest} 에는 주체 필드가 없고(있어도 안 되고), 값의 출처는 인계 토큰
     * subject 하나다. 같은 이유로 관리자 유효창도 요청이 실어 보낸 subject 를 쓰지 않는다
     * ({@code AdminSessionGate}).
     *
     * <p>자기호출(2-인자 → 3-인자)이라 이 메서드의 {@code @Transactional} 은 프록시를 타지 않지만,
     * 진입점 양쪽에 같은 선언이 있어 <b>어느 쪽으로 들어와도 트랜잭션이 열린다</b>.
     *
     * <h3>★ 표시 이름은 별개 축이다 — 주체를 남기지 않는다</h3>
     * <p>{@code actor} 는 <b>역할 축 전용</b>이다. 이름 수정의 감사는 {@code MDFCN_DT}(수정일시)
     * 하나로 끝내고 수정자 칸을 두지 않는다(@design AC-1018). 이름을 로그로도 남기지 않는다 —
     * 사람 이름은 개인정보라 로그에 실을 값이 아니다.
     *
     * <h3>★ 검증은 어느 쓰기보다 앞이다 — 롤백에 기대지 않는다</h3>
     * <p>순서는 <b>존재 확인(404) → 이름 검증(400) → 역할 쓰기 → 이름 쓰기</b> 다. 이름 검증을 역할
     * 분기 <i>뒤</i>에 두면 "역할은 유효하고 이름만 무효" 인 요청이 <b>역할 upsert 를 먼저 실행한 뒤</b>
     * 400 을 던진다. 그 경우에도 결과는 안전하다 — {@code CustomException} 이 unchecked 라 트랜잭션이
     * 롤백되고 캐시 무효화도 {@code AFTER_COMMIT} 이라 발화하지 않는다. 그러나 <b>그 안전이 롤백이라는
     * 바깥 장치에 매달려 있다</b>: 트랜잭션 경계를 옮기거나 진입점을 하나 늘리면 아무 신호 없이 깨진다.
     * 애초에 쓰지 않으면 그 의존이 사라진다.
     *
     * <p><b>404 는 검증보다 앞을 지킨다</b> — 없는 사용자에게 400 을 주면 응답이 존재 여부를 알려주는
     * 창이 된다(CWE-203).
     *
     * <p>이름을 보내지 않은 요청({@code userNm == null})은 검증 자체를 타지 않는다. 저장된 이름이
     * 무효하다는 이유로 <b>역할만 고치는 저장이 막히면 안 된다</b> — 인계 토큰에 이름 클레임이 없는
     * 사용자의 저장값은 빈 문자열이라, 보내지 않은 축을 판정하면 그 사용자의 역할을 영영 못 바꾼다.
     *
     * @param actor 역할을 바꾼 주체(인계 토큰 subject). 인증 컨텍스트가 없으면 null
     * @design AC-1018
     * @design AC-1019
     */
    @Transactional("controlTransactionManager")
    public UserProfileResponse update(Long userNo, UserUpdateRequest req, String actor) {
        // 존재 여부 확인 — 갱신 전 검증으로 404 분기 및 응답 빌드용 baseline 확보.
        LsAcntUser user = userRepository.findByUserNo(userNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        // ★이름 검증은 <어떤 쓰기보다도 앞>이다 — 역할 쓰기가 일어난 뒤 400 을 던지고 롤백에
        //   기대는 구조를 만들지 않는다(메서드 javadoc 「검증은 어느 쓰기보다 앞이다」).
        //   보내지 않은 축(null)은 판정하지 않는다 — 그래야 역할만 고치는 저장이 막히지 않는다.
        //   requireValidUserName 은 null 을 돌려주지 않고 던지므로, 아래에서 이 값의 null 여부가
        //   곧 「이름을 보냈는가」와 같다 — 분기 조건을 두 번 적지 않기 위한 구조다.
        String validatedName = req.userNm() == null ? null : requireValidUserName(req.userNm());

        String currentRole = lsUserRoleRepository.findByUserNo(userNo)
                .map(LsUserRole::getRoleCd)
                .orElse(null);

        String nextRole = currentRole;
        // 동일 역할 재적용은 skip — 불필요한 UPD_DT 갱신/Phase3 캐시 churn 방지(멱등).
        // LS 행이 없으면(currentRole=null) 신규 부여는 진행한다.
        if (req.role() != null && !req.role().equals(currentRole)) {
            String modifier = normalizeActor(actor);
            guardLastAdmin(userNo, currentRole, req.role());
            lsUserRoleRepository.upsertRole(userNo, req.role(), modifier);
            nextRole = req.role();
            // Phase 3 — 인가 역할 캐시 무효화. 커밋 후(AFTER_COMMIT)에 evict 하여 강등이 즉시(≤다음요청)
            // 반영되게 한다. 커밋 전 evict 는 동시 요청이 old value 를 재캐싱할 수 있어 금지.
            evictRoleCacheAfterCommit(userNo);
            // 감사 로그 — 강등 포함 변경 추적. userNo 는 Long, 역할은 화이트리스트 코드값,
            //   actor 는 제어문자를 제거한 토큰 subject 다(PII·토큰 본문 없음, CWE-117 차단).
            log.info("[User] role changed userNo={}, before={}, after={}, actor={}",
                    userNo, currentRole, nextRole, modifier);
        }

        // ★표시 이름 축 — 역할 축과 독립이다. 이름만 보낸 요청은 위 역할 분기에 애초에 들어가지
        //   않으므로 마지막 관리자 보호에도 걸리지 않는다(@design AC-1019). 그 성질은 「이름만 보낸
        //   요청을 따로 알아본다」가 아니라 <구조>에서 나온다 — 분기 조건을 풀어 쓰지 말 것.
        String nextName = user.getUserNm();
        if (validatedName != null) {
            nextName = validatedName;
            // 「같은 값이면 아무것도 바꾸지 않는다(수정일시 포함)」의 판정은 <문장 안>에 있다.
            //   여기서 미리 비교해 거르지 않는 이유는 2노드가 같은 옛 값을 읽고 각각 쓰면 수정일시가
            //   두 번 밀리기 때문이다. 반환값(바꿨는지)은 쓰지 않는다 — 이름은 로그에 싣지 않고
            //   주체도 남기지 않는다(감사 주체는 역할 축이 소유한다).
            userRepository.updateUserNm(userNo, nextName);
        }

        // 응답 — 갱신된 role·이름 반영. channel 은 변경 대상 사용자의 컨텍스트 정보가 없어 빈 값.
        //   ★이름은 엔티티가 아니라 nextName 에서 읽는다: 위 native UPDATE 는 영속성 컨텍스트를
        //   우회하므로 엔티티 스냅샷은 옛 이름 그대로다.
        return new UserProfileResponse(
                user.getUserNo(),
                user.getUserId(),
                nextName,
                user.getUserEmlAddr(),
                nextRole,
                ""
        );
    }

    /**
     * <b>마지막 관리자 보호</b> — 관리자가 한 명뿐일 때 그 사람을 다른 역할로 내리면 409.
     *
     * <h3>왜 조회 후 판정이 아닌가 (CWE-362 write skew)</h3>
     * <p>두 관리자가 서로를 <b>동시에</b> 내리면, 각자 조회 시점에는 둘 다 "관리자가 둘" 이라고
     * 보아 통과하고 결과적으로 0명이 된다. 위 {@code currentRole} 조회값만으로 판정하면 정확히 그
     * 결함이다. 그래서 판정 직전에 <b>관리자 행 집합을 잠그고</b>({@code FOR UPDATE}) 그 잠금이
     * 돌려준 목록으로 센다 — 뒤늦은 트랜잭션은 앞선 강등이 커밋된 뒤의 목록을 보므로 "이제 한
     * 명뿐" 을 정확히 관측한다(리포지토리 javadoc 참조).
     *
     * <h3>대가</h3>
     * <p>관리자 계정을 전부 잃으면 저장소를 직접 고치는 것이 유일한 복구 경로다 — 인지하고 받아들인
     * 대가이며 운영 문서에 복구 절차를 남긴다.
     *
     * @param userNo      대상 사용자번호
     * @param currentRole 변경 전 역할(없으면 null)
     * @param nextRole    변경 후 역할 — 관리자 유지면 보호가 필요 없다
     * @design AC-124
     */
    private void guardLastAdmin(Long userNo, String currentRole, String nextRole) {
        // 관리자를 내리는 경우에만 판정한다 — 그 외에는 관리자 수가 줄지 않으므로 잠글 이유가 없다
        // (모든 역할 변경이 관리자 행 전체를 잠그면 불필요한 직렬화가 생긴다).
        if (!Role.ADMIN.name().equals(currentRole) || Role.ADMIN.name().equals(nextRole)) {
            return;
        }
        List<Long> admins = lsUserRoleRepository.lockUserNosByRoleCd(Role.ADMIN.name());
        // 목록에 없으면 그 사이 이미 강등됐다는 뜻이라 보호 대상이 아니다(남은 관리자를 줄이지 않는다).
        if (admins.contains(userNo) && admins.size() <= 1) {
            log.warn("[User] last admin demotion denied userNo={}", userNo);
            throw new CustomException(ErrorCode.CONFLICT,
                    "마지막 관리자는 다른 역할로 변경할 수 없습니다. 다른 사용자를 관리자로 지정한 뒤 다시 시도하세요.");
        }
    }

    /**
     * <b>역할 변경 주체를 인증 컨텍스트에서 취한다 — 요청이 실어 보낸 값을 쓰지 않는다.</b>
     *
     * <p>{@code AdminSessionGate} 가 유효창 결박 대상을 정할 때와 같은 원칙이다: 요청이 준 값을
     * 쓰면 남의 이름을 붙여 제시하는 것만으로 기록이 거짓이 된다.
     *
     * <p>인증 주체를 알 수 없으면 {@code null} 을 돌려준다 — 없는 사실을 지어내지 않는다.
     * 그 경우 {@code MDFR_ID} 는 null 로 남고 "주체를 알 수 없는 경로" 를 뜻한다.
     *
     * @design AC-1018
     */
    private static String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TokenClaims claims)) {
            return null;
        }
        return claims.sub();
    }

    /**
     * 저장·로그에 싣기 전 주체 문자열을 다듬는다.
     *
     * <ul>
     *   <li><b>제어문자 제거</b> — 이 값은 로그에 그대로 실린다. 개행이 섞이면 로그 위조가 된다
     *       (CWE-117).</li>
     *   <li><b>길이 절단</b> — {@code MDFR_ID} 는 {@code varchar(30)}(표준도메인 식별자V30)이다.
     *       입구에서 자르지 않으면 INSERT 시점의 DB 오류(500)가 된다. DB 길이는 최종 방어선이지
     *       1차 방어선이 아니다.</li>
     *   <li><b>빈 값은 null</b> — 빈 문자열을 남기면 "주체가 있는데 이름이 없다" 로 읽힌다.
     *       모른다는 사실은 null 로 남긴다.</li>
     * </ul>
     */
    private static String normalizeActor(String actor) {
        if (actor == null) {
            return null;
        }
        String sanitized = actor.replaceAll("\\p{Cntrl}", "").trim();
        if (sanitized.isEmpty()) {
            return null;
        }
        return sanitized.length() > MAX_ACTOR_LENGTH
                ? sanitized.substring(0, MAX_ACTOR_LENGTH)
                : sanitized;
    }

    /**
     * <b>표시 이름 — 정규화한 <i>뒤</i> 판정한다. 폭을 넘으면 자르지 않고 거절한다.</b>
     *
     * <h3>왜 선언적 검증(@Size/@NotBlank)이 아닌가</h3>
     * <p>판정 대상이 요청 원문이 아니라 <b>공백·제어문자를 걷어낸 결과</b>이기 때문이다. 원문을 보는
     * 검증은 공백뿐인 이름을 통과시키고(정규화하면 빈 값이 된다), 반대로 앞뒤 공백 때문에 정상 이름을
     * 거절한다. 부수 효과로 거부 응답이 필드 경로를 담지 않는다(@design AC-1019).
     *
     * <h3>★ 왜 상한을 {@link Integer#MAX_VALUE} 로 넘기나</h3>
     * <p>정규화 규칙의 단일 진실원은 {@link UserDisplayNames#normalize} 다 — 여기에 다시 적으면 두
     * 번째 진실원이 된다. 그런데 그 함수는 상한을 넘으면 <b>자른다</b>. 자른 값을 받으면 길이가 늘
     * 상한 이하라 <b>초과 자체를 알아볼 수 없고</b>, 정확히 상한 길이인 정상 이름과도 구분되지 않는다.
     * 그래서 절단이 개입하지 않는 상한으로 불러 <b>정규화만</b> 받고 길이는 여기서 직접 잰다. 그
     * 결과 {@code normalize} 의 절단은 이 경로에서 <b>도달하지 않는 마지막 방어선</b>으로 남는다.
     *
     * <h3>왜 절단이 아니라 거절인가</h3>
     * <p>이름은 사람을 알아보는 값이라 조용히 잘리면 <b>다른 사람으로 보인다</b>. 상위 시스템에서
     * 이름을 인계받는 경로도 이미 입구에서 길이로 되돌려 보내므로, 두 입구의 판정이 갈리면 안 된다.
     *
     * @return 저장해도 되는 정규화된 이름 (null 아님)
     * @throws CustomException 정규화 결과가 비었거나({@code INVALID_INPUT}) 저장 폭을 넘을 때
     * @design API-004
     * @design AC-1019
     */
    private static String requireValidUserName(String raw) {
        String normalized = UserDisplayNames.normalize(raw, Integer.MAX_VALUE);
        if (normalized == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "사용자 이름은 공백일 수 없습니다.");
        }
        if (normalized.length() > UserDisplayNames.MAX_USER_NM_LENGTH) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "사용자 이름이 저장할 수 있는 길이를 넘습니다.");
        }
        return normalized;
    }

    private Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }

    /**
     * 역할 변경 후 인가 역할 캐시(userRole)를 트랜잭션 커밋 후 무효화한다.
     * 트랜잭션 동기화가 없는(테스트 등) 경우 즉시 evict 하여 fallback.
     */
    private void evictRoleCacheAfterCommit(Long userNo) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    userRoleResolver.evict(userNo);
                }
            });
        } else {
            userRoleResolver.evict(userNo);
        }
    }
}
