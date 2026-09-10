package archfixture;

import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code ArchitectureTest} 의 UUID 바인딩 규칙이 정당한 바인딩을 오탐하지 않는지 확인하는 <b>양성 픽스처</b>. 허용
 * 어노테이션 넷을 각각 하나씩과, 매핑이 없어 규칙 대상이 아닌 보조 메서드를 담는다.
 *
 * <p>{@link UnboundUuidControllerFixture} 와 같은 이유로 베이스 패키지 밖에 둔다.
 */
@RestController
public class BoundUuidControllerFixture {

    @GetMapping("/archfixture/current-member")
    public Object currentMember(@CurrentMemberId UUID memberId) {
        return memberId;
    }

    @GetMapping("/archfixture/path/{id}")
    public Object path(@PathVariable("id") UUID id) {
        return id;
    }

    @GetMapping("/archfixture/param")
    public Object param(@RequestParam("id") UUID id) {
        return id;
    }

    @GetMapping("/archfixture/header")
    public Object header(@RequestHeader("X-Id") UUID id) {
        return id;
    }

    /** 요청 매핑이 없으므로 규칙 대상이 아니다 — 어노테이션 없는 {@code UUID} 여도 위반이 아니어야 한다. */
    public UUID helper(UUID ignored) {
        return ignored;
    }
}
