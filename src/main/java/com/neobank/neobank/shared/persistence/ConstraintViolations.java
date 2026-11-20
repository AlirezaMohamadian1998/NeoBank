package com.neobank.neobank.shared.persistence;

import org.hibernate.exception.ConstraintViolationException;

public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    public static boolean isViolationOf(Throwable throwable, String constraintName) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                String violatedConstraint = violation.getConstraintName();

                if (violatedConstraint == null) {
                    return false;
                }

                int lastDot = violatedConstraint.lastIndexOf('.');
                String unqualifiedName = violatedConstraint.substring(lastDot + 1);

                return constraintName.equalsIgnoreCase(unqualifiedName);
            }

            current = current.getCause();
        }

        return false;
    }
}
