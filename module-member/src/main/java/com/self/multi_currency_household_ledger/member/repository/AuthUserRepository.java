package com.self.multi_currency_household_ledger.member.repository;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuthUserRepository {

    private final EntityManager entityManager;

    public int deleteById(UUID memberId) {
        return entityManager
                .createNativeQuery("delete from auth.users where id = :id")
                .setParameter("id", memberId)
                .executeUpdate();
    }
}
