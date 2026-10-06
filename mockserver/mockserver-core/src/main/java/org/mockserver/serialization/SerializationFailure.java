package org.mockserver.serialization;

import com.fasterxml.jackson.databind.JsonMappingException;

import java.util.List;
import java.util.function.Function;

/**
 * A bounded description of a failed serialisation of several values, for an exception message or a log
 * entry. It says how many values there were and, when Jackson reports it, which one failed. It never
 * renders the values: rendering a large log a second time can itself exhaust the heap.
 */
public final class SerializationFailure {

    private SerializationFailure() {
    }

    /**
     * @return the index of the top-level array element being written when {@code throwable} was thrown,
     * or {@code -1} when Jackson recorded none (for example a failure while building the DTOs)
     */
    public static int failedIndex(Throwable throwable) {
        if (throwable instanceof JsonMappingException) {
            List<JsonMappingException.Reference> path = ((JsonMappingException) throwable).getPath();
            if (!path.isEmpty()) {
                return path.get(0).getIndex();
            }
        }
        return -1;
    }

    public static String describe(Object[] values, Throwable throwable) {
        return describe(values, throwable, null);
    }

    /**
     * @param identify a short, bounded identification of the failing value (never its rendering), or {@code null}
     */
    public static <T> String describe(T[] values, Throwable throwable, Function<T, String> identify) {
        int count = values == null ? 0 : values.length;
        StringBuilder description = new StringBuilder().append(count).append(count == 1 ? " value" : " values");
        int index = failedIndex(throwable);
        if (index >= 0 && index < count) {
            description.append(", failed at index ").append(index);
            if (identify != null && values[index] != null) {
                description.append(" (").append(identify.apply(values[index])).append(')');
            }
        }
        return description.toString();
    }
}
