package com.apimarketplace.catalog.util;

import com.apimarketplace.catalog.domain.ApiToolParameterEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Which catalogue row owns the place a descriptor writes to.
 *
 * <p><b>Why this is one class and not two lookups.</b> A generation descriptor
 * addresses a provider field by the PATH it writes; the catalogue stores that
 * field as a row under its own NAME, sometimes declaring the path as its
 * {@code bodyPath}. Two things then need the same answer: the values that field
 * accepts, and the source that can list them for this account. Answering them
 * separately is how a parameter came to be described with one row's enumeration
 * and another row's provider source, with each half looking correct on its own.
 *
 * <p>The rule, in order:
 *
 * <ol>
 *   <li>A row NAMED for the path owns it. Whatever shape the name has: plenty
 *       of rows are named for the nested place they fill ({@code input.prompt}),
 *       and there the name is the write path exactly.</li>
 *   <li>Otherwise, and only for a nested path, a row CLAIMING it as its
 *       {@code bodyPath} owns it. A flat path is a parameter's own name and
 *       nothing else, so honouring a foreign flat claim would let a row named
 *       {@code length_ms} answer for a field that means seconds.</li>
 *   <li>Ambiguity owns nothing. Two rows of the same name, or two claimants,
 *       and nothing says which is right: the repository query has no
 *       {@code ORDER BY}, so picking one is picking whichever the database
 *       happened to return first, and the same request would answer differently
 *       on two different days. A field with no list is a smaller lie than a
 *       field showing another parameter's values.</li>
 * </ol>
 */
public final class ParameterOwner {

    private ParameterOwner() {}

    /**
     * The row that owns {@code path}, or empty when none does or more than one
     * might.
     *
     * @param rows every parameter row of the endpoint, in whatever order the
     *             repository returned them
     * @param path the descriptor's write path
     */
    public static Optional<ApiToolParameterEntity> of(List<ApiToolParameterEntity> rows, String path) {
        if (rows == null || rows.isEmpty() || path == null || path.isBlank()) return Optional.empty();

        // A row owns its name only while it still fills it. One that declares a
        // DIFFERENT bodyPath has said where its value goes, and it is not here:
        // offering its values under this path would describe a field the request
        // never writes.
        Optional<ApiToolParameterEntity> named = single(rows, p -> path.equals(p.getName())
                && fillsItsOwnName(p));
        if (named.isPresent()) return named;
        if (contested(rows, p -> path.equals(p.getName()) && fillsItsOwnName(p))) {
            return Optional.empty();
        }

        if (!isNested(path)) return Optional.empty();
        return single(rows, p -> path.equals(ParameterBodyPath.of(p.getExtras())));
    }

    /** True when the row writes its value where its name says, not elsewhere. */
    private static boolean fillsItsOwnName(ApiToolParameterEntity p) {
        String declared = ParameterBodyPath.of(p.getExtras());
        return declared == null || declared.equals(p.getName());
    }

    /** True for a write path that addresses a place INSIDE a body. */
    public static boolean isNested(String path) {
        return path != null && (path.indexOf('.') >= 0 || path.indexOf('[') >= 0);
    }

    private static Optional<ApiToolParameterEntity> single(
            List<ApiToolParameterEntity> rows, Function<ApiToolParameterEntity, Boolean> matches) {
        List<ApiToolParameterEntity> hits = new ArrayList<>(2);
        for (ApiToolParameterEntity p : rows) {
            if (Boolean.TRUE.equals(matches.apply(p))) {
                hits.add(p);
                if (hits.size() > 1) return Optional.empty();
            }
        }
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    private static boolean contested(
            List<ApiToolParameterEntity> rows, Function<ApiToolParameterEntity, Boolean> matches) {
        int seen = 0;
        for (ApiToolParameterEntity p : rows) {
            if (Boolean.TRUE.equals(matches.apply(p)) && ++seen > 1) return true;
        }
        return false;
    }
}
