package org.mockserver.imports;

import com.google.common.hash.Hashing;
import org.mockserver.mock.Expectation;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ids for imported expectations, derived from each expectation's request matcher rather than its position in the file.
 * <p>
 * Re-importing the same file upserts the same ids, and so does a file whose responses changed, so it updates the
 * mocks in place. A different file, whose requests differ, gets different ids and so adds to what an earlier import
 * created instead of replacing it. Requests repeated within one file are numbered in file order ({@code -2}, ...).
 */
final class ImportIds {

    private static final int HASH_LENGTH = 12;

    private ImportIds() {
    }

    /**
     * Replaces each expectation's id, which on entry holds its readable prefix (for example {@code har} or
     * {@code postman-get-users}), with that prefix plus a hash of its request matcher.
     */
    static List<Expectation> fromRequestMatchers(List<Expectation> expectations) {
        Map<String, Integer> occurrences = new HashMap<>();
        for (Expectation expectation : expectations) {
            String requestMatcher = String.valueOf(expectation.getHttpRequest());
            String id = expectation.getId() + "-" + Hashing.sha256().hashString(requestMatcher, StandardCharsets.UTF_8).toString().substring(0, HASH_LENGTH);
            int occurrence = occurrences.merge(id, 1, Integer::sum);
            expectation.withId(occurrence == 1 ? id : id + "-" + occurrence);
        }
        return expectations;
    }
}
