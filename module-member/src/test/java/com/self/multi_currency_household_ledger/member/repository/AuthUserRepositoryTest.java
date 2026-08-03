package com.self.multi_currency_household_ledger.member.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthUserRepositoryTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    @InjectMocks
    private AuthUserRepository authUserRepository;

    @Test
    @DisplayName("auth.users 삭제는 id 술어와 JWT subject 파라미터를 사용하고 영향 행수를 반환한다")
    void deleteById_uses_id_predicate_and_binds_member_id() {
        given(entityManager.createNativeQuery(anyString())).willReturn(query);
        given(query.setParameter("id", MEMBER_ID)).willReturn(query);
        given(query.executeUpdate()).willReturn(1);

        int deletedRows = authUserRepository.deleteById(MEMBER_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        then(entityManager).should().createNativeQuery(sql.capture());
        assertThat(sql.getValue()).containsIgnoringCase("delete from auth.users");
        assertThat(sql.getValue()).containsIgnoringCase("where id = :id");
        then(query).should().setParameter("id", MEMBER_ID);
        then(query).should().executeUpdate();
        assertThat(deletedRows).isEqualTo(1);
    }
}
