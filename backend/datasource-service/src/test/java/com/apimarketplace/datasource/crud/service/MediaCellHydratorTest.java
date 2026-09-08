package com.apimarketplace.datasource.crud.service;

import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hydrator itself, cell by cell.
 *
 * <p>The service-level tests prove the wiring; these prove the judgement calls, and in particular
 * the boundary that decides whether a cell is a file at all. That boundary matters more than it
 * looks: on the wrong side of it, a value the database still holds becomes invisible to every
 * reader.
 *
 * <p>Both storage encodings appear here on purpose. A cell written through the CRUD path is a JSON
 * STRING inside the JSONB document (the row map is stringified before the insert), while a cell
 * written through the grid is a real nested OBJECT (the whole row map is serialised at once). A
 * suite that only fed it strings would leave the branch that runs on every grid-written row
 * untested.
 */
@DisplayName("MediaCellHydrator")
class MediaCellHydratorTest {

    private final MediaCellHydrator hydrator = new MediaCellHydrator(new ColumnValueCoercer());

    private static final Map<String, ColumnMappingSpec> ONE_FILE_COLUMN =
        Map.of("video", new ColumnMappingSpec("data.video", ColumnType.FILE, null, null, null));

    private Object hydrateOne(Object storedCell) {
        Map<String, Object> row = new HashMap<>();
        row.put("video", storedCell);
        return hydrator.hydrateRow(row, ONE_FILE_COLUMN).get("video");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hydrateOneAsMap(Object storedCell) {
        return (Map<String, Object>) hydrateOne(storedCell);
    }

    // ── The two storage encodings ────────────────────────────────────────

    @Test
    @DisplayName("A cell stored as JSON text (the CRUD write path) inflates to the file object")
    void textEncodedCellInflates() {
        String storedText = "{\"_type\":\"file\",\"path\":\"t/wf/clip.mp4\",\"name\":\"clip.mp4\"}";

        assertThat(hydrateOne(storedText)).isInstanceOf(Map.class);
        assertThat(hydrateOneAsMap(storedText))
            .containsEntry("path", "t/wf/clip.mp4")
            .containsEntry("name", "clip.mp4");
    }

    @Test
    @DisplayName("A cell stored as a real object (the grid write path) is normalised, not skipped")
    void objectEncodedCellIsNormalised() {
        Map<String, Object> storedObject = new LinkedHashMap<>();
        storedObject.put("storageKey", "t/wf/clip.mp4");
        storedObject.put("fileName", "clip.mp4");

        assertThat(hydrateOneAsMap(storedObject))
            .containsEntry("_type", "file")
            .containsEntry("path", "t/wf/clip.mp4")
            .containsEntry("name", "clip.mp4");
    }

    @Test
    @DisplayName("An object-encoded cell loses the keys outside the contract, as documented")
    void objectEncodedCellLosesKeysOutsideTheContract() {
        Map<String, Object> storedObject = new LinkedHashMap<>();
        storedObject.put("_type", "file");
        storedObject.put("path", "t/wf/clip.mp4");
        storedObject.put("caption", "still in the database, not in the read");

        Map<String, Object> hydrated = hydrateOneAsMap(storedObject);

        assertThat(hydrated).containsEntry("path", "t/wf/clip.mp4");
        assertThat(hydrated).doesNotContainKey("caption");
    }

    // ── The boundary: what counts as naming a file ───────────────────────

    /**
     * The normaliser reads generic aliases - {@code key}, {@code src}, {@code href}, {@code link} -
     * that an ordinary object carries for its own reasons. Judging its output rather than the
     * stored value would claim any of these as a file and strip the rest away.
     */
    @Test
    @DisplayName("An object whose only file-ish key is a generic alias is kept exactly as stored")
    void objectWithOnlyAGenericAliasIsKept() {
        String notAFile = "{\"key\":\"abc\",\"value\":42}";

        assertThat(hydrateOne(notAFile)).isEqualTo(notAFile);
    }

    @Test
    @DisplayName("An object using src for its own purposes is kept exactly as stored")
    void objectUsingSrcIsKept() {
        String notAFile = "{\"src\":\"chapter-3\",\"title\":\"Notes\"}";

        assertThat(hydrateOne(notAFile)).isEqualTo(notAFile);
    }

    @Test
    @DisplayName("An object with no file-ish key at all is kept exactly as stored")
    void plainObjectIsKept() {
        String notAFile = "{\"caption\":\"a caption\",\"alt\":\"alt text\"}";

        assertThat(hydrateOne(notAFile)).isEqualTo(notAFile);
    }

    @Test
    @DisplayName("The file discriminator alone is enough to inflate, even with no other key")
    void discriminatorAloneIsEnough() {
        assertThat(hydrateOneAsMap("{\"_type\":\"file\",\"url\":\"https://cdn.example/a.png\"}"))
            .containsEntry("_type", "file");
    }

    /**
     * Names a file, but nothing in it can address one. Handing back a bare {@code {_type:'file'}}
     * would be worse than the text: the caller would believe it had a reference.
     */
    @Test
    @DisplayName("A value that names a file but resolves to nothing addressable is kept as stored")
    void fileShapedButUnresolvableIsKept() {
        String bare = "{\"_type\":\"file\"}";

        assertThat(hydrateOne(bare)).isEqualTo(bare);
    }

    /**
     * The one marker in the set an ordinary object commonly carries. It is kept deliberately: in a
     * column DECLARED as media, a {@code url} is what the write path itself treats as a file, so
     * refusing it here would make the read disagree with the write. The cost is real and is the
     * reason this is pinned: a non-file object stored in a media column loses its other keys.
     */
    @Test
    @DisplayName("A url in a declared media column IS treated as a file, and other keys are dropped")
    void urlAloneIsTreatedAsAFile() {
        Map<String, Object> hydrated =
            hydrateOneAsMap("{\"url\":\"https://acme.example/logo.png\",\"label\":\"Acme\"}");

        assertThat(hydrated).containsEntry("_type", "file");
        assertThat(hydrated).containsEntry("url", "https://acme.example/logo.png");
        assertThat(hydrated).doesNotContainKey("label");
    }

    /**
     * A UUID id addresses one of OUR files and nothing else does, so it names a file with no other
     * key to help. The sibling case below is the reason this is not simply "any id": a foreign id
     * is somebody else's key, and minting a URL from it would replace nothing with nothing.
     */
    @Test
    @DisplayName("A bare UUID id names a file on its own, with no other key present")
    void bareUuidIdIsEnough() {
        Map<String, Object> hydrated =
            hydrateOneAsMap("{\"id\":\"c7963596-ab99-46af-9cb5-fccb64461702\"}");

        assertThat(hydrated)
            .containsEntry("_type", "file")
            .containsEntry("id", "c7963596-ab99-46af-9cb5-fccb64461702");
    }

    @Test
    @DisplayName("A foreign id is not evidence of a file, and the cell is kept as stored")
    void foreignIdIsNotEvidence() {
        String airtableAttachment = "{\"id\":\"attABC123\",\"size\":9}";

        assertThat(hydrateOne(airtableAttachment)).isEqualTo(airtableAttachment);
    }

    // ── Values it must never touch ───────────────────────────────────────

    @Test
    @DisplayName("A JSON array keeps its stored text - one cell holds one reference")
    void jsonArrayIsKept() {
        String array = "[{\"_type\":\"file\",\"path\":\"t/a.mp4\"}]";

        assertThat(hydrateOne(array)).isEqualTo(array);
    }

    @Test
    @DisplayName("A bare URL string is not reinterpreted as an object")
    void bareUrlIsKept() {
        assertThat(hydrateOne("https://example.com/a.png")).isEqualTo("https://example.com/a.png");
    }

    @Test
    @DisplayName("A number in a media column is left alone")
    void nonTextNonMapValueIsKept() {
        assertThat(hydrateOne(42)).isEqualTo(42);
    }

    // ── Degenerate inputs ────────────────────────────────────────────────

    @Test
    @DisplayName("A null row is returned as null rather than throwing")
    void nullRowIsTolerated() {
        assertThat(hydrator.hydrateRow(null, ONE_FILE_COLUMN)).isNull();
    }

    @Test
    @DisplayName("A null or empty row collection is returned unchanged")
    void nullAndEmptyRowsAreTolerated() {
        assertThat(hydrator.hydrateRows((List<Map<String, Object>>) null, ONE_FILE_COLUMN)).isNull();
        assertThat(hydrator.hydrateRows(List.<Map<String, Object>>of(), ONE_FILE_COLUMN)).isEmpty();
    }

    @Test
    @DisplayName("A null column spec means no media columns and no work")
    void nullMappingSpecMeansNoMediaColumns() {
        assertThat(hydrator.mediaColumns(null)).isEmpty();
        assertThat(hydrator.mediaColumns(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("A spec key written with the data. prefix names the same bare row column")
    void dataPrefixedSpecKeyIsStripped() {
        Map<String, ColumnMappingSpec> spec =
            Map.of("data.video", new ColumnMappingSpec("data.video", ColumnType.FILE, null, null, null));

        assertThat(hydrator.mediaColumns(spec)).containsExactly("video");
    }

    @Test
    @DisplayName("Both file and image columns are media columns; nothing else is")
    void onlyFileAndImageColumnsAreMedia() {
        Map<String, ColumnMappingSpec> spec = new LinkedHashMap<>();
        spec.put("doc", new ColumnMappingSpec("data.doc", ColumnType.FILE, null, null, null));
        spec.put("cover", new ColumnMappingSpec("data.cover", ColumnType.IMAGE, null, null, null));
        spec.put("title", new ColumnMappingSpec("data.title", ColumnType.TEXT, null, null, null));
        spec.put("link", new ColumnMappingSpec("data.link", ColumnType.URL, null, null, null));

        assertThat(hydrator.mediaColumns(spec)).containsExactlyInAnyOrder("doc", "cover");
    }
}
