package com.self.multi_currency_household_ledger.common.exception;

public final class DatabaseConstraints {

    public static final String LEDGER_ENTRY_MEMBER_FOREIGN_KEY = "fk_ledger_entry_member";
    public static final String CATEGORY_OWNER_MEMBER_FOREIGN_KEY = "fk_category_owner_member";

    private DatabaseConstraints() {}

    // 깊이 상한을 두는 이유: cause 사슬은 우리가 만드는 것이 아니라 드라이버·ORM 이 조립한다.
    // 순환이 섞이면 이 순회가 요청 스레드를 영구 점유한다. 실제 사슬은 서너 단계라 16 이면 충분하다.
    private static final int MAX_CAUSE_DEPTH = 16;

    public static boolean isLedgerEntryMemberForeignKeyViolation(Throwable exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintViolation
                    && LEDGER_ENTRY_MEMBER_FOREIGN_KEY.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    public static boolean isCategoryOwnerMemberForeignKeyViolation(Throwable exception) {
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintViolation
                    && CATEGORY_OWNER_MEMBER_FOREIGN_KEY.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
