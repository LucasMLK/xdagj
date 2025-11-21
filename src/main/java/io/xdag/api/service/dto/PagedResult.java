package io.xdag.api.service.dto;

import java.util.Collections;
import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * Generic pagination result for service layer.
 *
 * @param <T> element type
 */
@Value
@Builder
public class PagedResult<T> {

    @Builder.Default
    List<T> items = Collections.emptyList();

    long total;

    public static <T> PagedResult<T> empty() {
        return PagedResult.<T>builder()
                .items(Collections.emptyList())
                .total(0)
                .build();
    }

    public static <T> PagedResult<T> of(List<T> items, long total) {
        return PagedResult.<T>builder()
                .items(items)
                .total(total)
                .build();
    }
}
