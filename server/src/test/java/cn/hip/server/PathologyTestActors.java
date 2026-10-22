package cn.hip.server;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.function.Supplier;

/** Existing clinical fixtures must invoke secured writes as their actual supplied employee. */
final class PathologyTestActors {
    private PathologyTestActors() {}

    static <T> T as(Authentication actor, Supplier<T> action) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        try {
            SecurityContextHolder.getContext().setAuthentication(actor);
            return action.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
}
