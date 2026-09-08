package com.apimarketplace.orchestrator.domain.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FileRefScanner}.
 *
 * <p>This decides which files a step claims as its own, so two things matter beyond finding the
 * obvious ref: it must find refs wherever a tool actually puts them (nested, in lists, several
 * levels down), and it must never throw - it runs on the output of a step that has already
 * succeeded, and nothing here is worth failing that step over.</p>
 */
@DisplayName("FileRefScanner")
class FileRefScannerTest {

    private static Map<String, Object> fileRef(String id) {
        Map<String, Object> ref = new HashMap<>();
        ref.put("_type", "file");
        ref.put("path", "1/general/catalog-binary/x.mp3");
        ref.put("name", "x.mp3");
        ref.put("mimeType", "audio/mpeg");
        ref.put("size", 218800);
        if (id != null) {
            ref.put("id", id);
        }
        return ref;
    }

    @Nested
    @DisplayName("finds refs where tools actually put them")
    class Finds {

        @Test
        @DisplayName("a ref projected under a field - the shape a binary catalog response produces")
        void refUnderAField() {
            // BinaryResponseHandler projects the upload under the schema's file-ref key.
            Map<String, Object> output = Map.of("audio", fileRef("11111111-1111-1111-1111-111111111111"));

            assertThat(FileRefScanner.collectFileIds(output))
                    .containsExactly("11111111-1111-1111-1111-111111111111");
        }

        @Test
        @DisplayName("a ref at the top level")
        void refAtTopLevel() {
            assertThat(FileRefScanner.collectFileIds(fileRef("aaa"))).containsExactly("aaa");
        }

        @Test
        @DisplayName("refs inside a list of results")
        void refsInsideAList() {
            Map<String, Object> output = Map.of("results", List.of(fileRef("a"), fileRef("b")));

            assertThat(FileRefScanner.collectFileIds(output)).containsExactly("a", "b");
        }

        @Test
        @DisplayName("a ref several levels down")
        void refDeeplyNested() {
            Map<String, Object> output = Map.of("data", Map.of("items", List.of(
                    Map.of("attachment", fileRef("deep")))));

            assertThat(FileRefScanner.collectFileIds(output)).containsExactly("deep");
        }

        @Test
        @DisplayName("the same id twice yields one entry - adoption is per row, not per mention")
        void deduplicates() {
            Map<String, Object> output = Map.of("a", fileRef("same"), "b", fileRef("same"));

            assertThat(FileRefScanner.collectFileIds(output)).containsExactly("same");
        }
    }

    @Nested
    @DisplayName("ignores what is not an adoptable ref")
    class Ignores {

        @Test
        @DisplayName("a ref with no id - a legacy ref predating the opaque handle")
        void refWithoutId() {
            assertThat(FileRefScanner.collectFileIds(Map.of("audio", fileRef(null)))).isEmpty();
        }

        @Test
        @DisplayName("a map that merely looks file-ish but is not typed as a file")
        void untypedMap() {
            Map<String, Object> notARef = Map.of("path", "some/path", "name", "x.mp3", "id", "nope");

            assertThat(FileRefScanner.collectFileIds(Map.of("thing", notARef))).isEmpty();
        }

        @Test
        @DisplayName("a different _type")
        void otherType() {
            assertThat(FileRefScanner.collectFileIds(Map.of("x", Map.of("_type", "table", "id", "nope")))).isEmpty();
        }

        @Test
        @DisplayName("an output with no refs at all, and the empty cases")
        void noRefs() {
            assertThat(FileRefScanner.collectFileIds(Map.of("status", "ok", "count", 3))).isEmpty();
            assertThat(FileRefScanner.collectFileIds(Map.of())).isEmpty();
            assertThat(FileRefScanner.collectFileIds(null)).isEmpty();
            assertThat(FileRefScanner.collectFileIds("just a string")).isEmpty();
        }

        @Test
        @DisplayName("a blank id is not an id")
        void blankId() {
            Map<String, Object> ref = fileRef(null);
            ref.put("id", "   ");

            assertThat(FileRefScanner.collectFileIds(Map.of("audio", ref))).isEmpty();
        }
    }

    @Nested
    @DisplayName("never throws on a hostile payload")
    class Survives {

        @Test
        @DisplayName("a self-referencing structure terminates instead of spinning")
        void handlesCycles() {
            Map<String, Object> outer = new HashMap<>();
            outer.put("self", outer);
            outer.put("audio", fileRef("cyclic"));

            assertThat(FileRefScanner.collectFileIds(outer)).containsExactly("cyclic");
        }

        @Test
        @DisplayName("a list that contains itself terminates")
        void handlesListCycles() {
            List<Object> list = new ArrayList<>();
            list.add(fileRef("in-list"));
            list.add(list);

            assertThat(FileRefScanner.collectFileIds(Map.of("items", list))).containsExactly("in-list");
        }

        @Test
        @DisplayName("nulls inside maps and lists are stepped over")
        void handlesNulls() {
            Map<String, Object> output = new HashMap<>();
            output.put("nothing", null);
            List<Object> items = new ArrayList<>();
            items.add(null);
            items.add(fileRef("present"));
            output.put("items", items);

            assertThat(FileRefScanner.collectFileIds(output)).containsExactly("present");
        }

        @Test
        @DisplayName("a ref buried below the depth cap is given up on, not chased forever")
        void boundedDepth() {
            // 30 levels: past the cap. Giving up costs a file at the root of the Files browser;
            // not giving up would cost the step.
            Map<String, Object> node = fileRef("too-deep");
            for (int i = 0; i < 30; i++) {
                node = new HashMap<>(Map.of("wrap", node));
            }

            assertThat(FileRefScanner.collectFileIds(node)).isEmpty();
        }
    }

    @Nested
    @DisplayName("collectRefs - the refs themselves, for the showcase snapshot")
    class CollectRefs {

        @Test
        @DisplayName("keeps the display fields a file pill renders, not just the id")
        void keepsDisplayFields() {
            // The showcase canvas shows the file's NAME and human SIZE before anything is
            // expanded; an id-only scan could not have fed it.
            Map<String, Object> output = new HashMap<>();
            output.put("file", fileRef("st-1"));

            List<Map<String, Object>> refs = FileRefScanner.collectRefs(output);

            assertThat(refs).hasSize(1);
            assertThat(refs.get(0))
                    .containsEntry("_type", "file")
                    .containsEntry("path", "1/general/catalog-binary/x.mp3")
                    .containsEntry("name", "x.mp3")
                    .containsEntry("mimeType", "audio/mpeg")
                    .containsEntry("size", 218800L)
                    .containsEntry("id", "st-1");
        }

        @Test
        @DisplayName("size is normalized to a long whatever number type the payload used")
        void sizeIsAlwaysLong() {
            // A ref parsed from JSON hands back an Integer, one built in-process a long. A
            // consumer comparing across the two would otherwise see values that are never equal.
            Map<String, Object> intSize = fileRef("st-int");
            intSize.put("size", 42);

            List<Map<String, Object>> refs = FileRefScanner.collectRefs(Map.of("file", intSize));

            assertThat(refs.get(0).get("size")).isEqualTo(42L);
        }

        @Test
        @DisplayName("keeps a ref that carries no id - it still has a path, and a path still renders")
        void keepsIdLessRef() {
            // collectFileIds drops these (there is no storage row to adopt); the showcase needs
            // them, because the path is what gets copied into the publication and signed.
            List<Map<String, Object>> refs = FileRefScanner.collectRefs(Map.of("file", fileRef(null)));

            assertThat(refs).hasSize(1);
            assertThat(refs.get(0)).doesNotContainKey("id");
            assertThat(refs.get(0)).containsEntry("path", "1/general/catalog-binary/x.mp3");
        }

        @Test
        @DisplayName("reads a FileRef record, not only a deserialized map")
        void readsTheRecord() {
            // A node that builds its result in-process hands back the record itself.
            FileRef record = FileRef.of("2/run/out.png", "out.png", "image/png", 900, "st-rec");

            List<Map<String, Object>> refs = FileRefScanner.collectRefs(Map.of("generated", record));

            assertThat(refs).hasSize(1);
            assertThat(refs.get(0))
                    .containsEntry("path", "2/run/out.png")
                    .containsEntry("name", "out.png")
                    .containsEntry("size", 900L)
                    .containsEntry("id", "st-rec");
        }

        @Test
        @DisplayName("returns refs in encounter order and does NOT deduplicate them")
        void keepsEveryEncounter() {
            // Unlike collectFileIds, whose caller wants a set of storage rows: here two
            // encounters of the same ref are two places the UI could show it, and the caller
            // decides what that means.
            Map<String, Object> first = fileRef("st-1");
            Map<String, Object> second = fileRef("st-2");
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("a", first);
            output.put("b", List.of(second, fileRef("st-1")));

            List<Map<String, Object>> refs = FileRefScanner.collectRefs(output);

            assertThat(refs).hasSize(3);
            assertThat(refs).extracting(r -> r.get("id")).containsExactly("st-1", "st-2", "st-1");
        }

        @Test
        @DisplayName("a self-referencing payload terminates instead of spinning")
        void survivesACycle() {
            Map<String, Object> output = new HashMap<>();
            output.put("file", fileRef("st-1"));
            output.put("self", output);

            List<Map<String, Object>> refs = FileRefScanner.collectRefs(output);

            assertThat(refs).hasSize(1);
        }

        @Test
        @DisplayName("no ref anywhere yields an empty list, never null")
        void emptyWhenNoRef() {
            assertThat(FileRefScanner.collectRefs(Map.of("status", "ok"))).isEmpty();
            assertThat(FileRefScanner.collectRefs(null)).isEmpty();
        }

        /**
         * The scanner is unchanged; what changed is what reaches it. A table read hands back media
         * cells as file OBJECTS now, and this pins the consequence of that: an object IS collected
         * where the equivalent JSON text is not, because a string is a scalar and this walker never
         * descends into one. It is what lets a published workflow's showcase preview and serve the
         * images of a table it reads, which {@code ShowcaseFileRefRewriter} could not do while the
         * cell was text. Not a regression test - it passes either way - but the record of a
         * decision that is otherwise invisible.
         */
        @Test
        @DisplayName("an object-shaped media cell is collected where the same cell as text is not")
        void findsAMediaCellInATableRow() {
            Map<String, Object> hydratedCell = new HashMap<>();
            hydratedCell.put("_type", "file");
            hydratedCell.put("path", "tenant-1/general/cover.png");
            // A UUID, the only id shape a hydrated cell can carry: the normaliser drops any other.
            hydratedCell.put("id", "c7963596-ab99-46af-9cb5-fccb64461702");
            hydratedCell.put("name", "cover.png");
            Map<String, Object> tableOutput = Map.of("items", List.of(
                Map.of("title", "Set: miniature city", "cover", hydratedCell)));

            assertThat(FileRefScanner.collectRefs(tableOutput))
                .singleElement()
                .satisfies(ref -> assertThat(ref).containsEntry("path", "tenant-1/general/cover.png"));

            // The shape the same cell had before: a scalar the walker cannot see into.
            Map<String, Object> textOutput = Map.of("items", List.of(
                Map.of("title", "Set: miniature city",
                    "cover", "{\"_type\":\"file\",\"path\":\"tenant-1/general/cover.png\"}")));
            assertThat(FileRefScanner.collectRefs(textOutput)).isEmpty();
        }
    }
}
