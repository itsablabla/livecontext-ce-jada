package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.seed.CatalogDataBootstrapService.CopyBlockReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Streaming a {@code COPY ... FROM stdin} block straight into Postgres.
 *
 * <p><b>Why the loader stopped buffering.</b> Every block used to be accumulated into a
 * {@code StringBuilder}, concatenated into a {@code String} and then encoded into a {@code byte[]}:
 * three copies of the same data alive at once. That held while the CE seed carried 16k endpoints.
 * Refreshed from a catalog that had since doubled (31,772 endpoints, 134,208 parameters, ~204 MB of
 * SQL) it threw {@code OutOfMemoryError} on the parameters block - and the failure is silent, since
 * it kills only the seed-import thread: the app reports healthy and the install is left with its
 * APIs and not a single tool. Verified on a real CE container before the fix.
 *
 * <p>What is pinned here is the part that can lose data rather than merely be slow: where the block
 * ENDS. Run past the terminator and the tables after it are parsed as SQL and lost; stop early and
 * the remainder of the dump is read as rows.
 */
@DisplayName("Catalog seed - streamed COPY blocks")
class CatalogSeedCopyStreamingTest {

    private static BufferedReader dump(String... lines) {
        return new BufferedReader(new StringReader(String.join("\n", lines)));
    }

    private static String readFully(CopyBlockReader reader) throws Exception {
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[8];
        int n;
        while ((n = reader.read(buffer, 0, buffer.length)) != -1) {
            out.append(buffer, 0, n);
        }
        return out.toString();
    }

    @Test
    @DisplayName("serves the block's rows with the newline COPY needs, and stops at the terminator")
    void servesRowsAndStopsAtTheTerminator() throws Exception {
        // readLine() drops the separator; without putting it back every row of the catalog would
        // arrive as one line and COPY would reject the block - or worse, take it as a single row.
        BufferedReader source = dump("row-1\tA", "row-2\tB", "\\.", "COPY catalog.next (id) FROM stdin;");

        assertThat(readFully(new CopyBlockReader(source))).isEqualTo("row-1\tA\nrow-2\tB\n");
    }

    @Test
    @DisplayName("leaves the dump positioned on the statement AFTER the block")
    void leavesTheSourceAfterTheBlock() throws Exception {
        // The whole point of ending at the terminator rather than at EOF: everything after this
        // block still has to be read by the caller. Overrun it and every later table is lost.
        BufferedReader source = dump("row-1\tA", "\\.", "COPY catalog.next (id) FROM stdin;", "42");

        readFully(new CopyBlockReader(source));

        assertThat(source.readLine()).isEqualTo("COPY catalog.next (id) FROM stdin;");
    }

    @Test
    @DisplayName("a row longer than the read buffer arrives whole, across several reads")
    void longRowsSurviveChunkedReads() throws Exception {
        // COPY asks for whatever size it likes. A row handed out in pieces must reassemble exactly,
        // or a JSONB column silently arrives truncated and the tool ships a broken schema.
        String wide = "x".repeat(200) + "\t" + "y".repeat(200);
        BufferedReader source = dump(wide, "\\.");

        assertThat(readFully(new CopyBlockReader(source))).isEqualTo(wide + "\n");
    }

    @Test
    @DisplayName("a zero-length read does not swallow a row")
    void zeroLengthReadKeepsTheRow() throws Exception {
        // Guards the obvious way to lose the first row: pull a line to satisfy a request that can
        // hand nothing back, then drop it.
        BufferedReader source = dump("row-1\tA", "\\.");
        CopyBlockReader reader = new CopyBlockReader(source);

        assertThat(reader.read(new char[4], 0, 0)).isZero();
        assertThat(readFully(reader)).isEqualTo("row-1\tA\n");
    }

    @Test
    @DisplayName("a dump that ends without a terminator stops at end of file rather than hanging")
    void truncatedBlockEndsCleanly() throws Exception {
        // A dump cut short is a corrupt dump, but the loader must end rather than block forever.
        BufferedReader source = dump("row-1\tA");

        assertThat(readFully(new CopyBlockReader(source))).isEqualTo("row-1\tA\n");
    }

    @Test
    @DisplayName("draining a half-read block resumes on the next statement, so one failure costs "
            + "one table and not the whole catalog")
    void drainingResumesAfterTheBlock() throws Exception {
        // The failure path: COPY refuses a block, and the reader is left mid-block. Without the
        // drain, the caller would parse the remaining rows as SQL statements and every later table
        // would be lost behind a wall of warnings.
        BufferedReader source = dump("row-1\tA", "row-2\tB", "row-3\tC", "\\.", "COPY catalog.next (id) FROM stdin;");
        CopyBlockReader reader = new CopyBlockReader(source);
        reader.read(new char[4], 0, 4);

        reader.drainToTerminator();

        assertThat(source.readLine()).isEqualTo("COPY catalog.next (id) FROM stdin;");
    }

    @Test
    @DisplayName("closing the block reader leaves the dump open for the rest of the file")
    void closeDoesNotCloseTheDump() throws Exception {
        // COPY closes what it is given. If that closed the dump, the first streamed table would be
        // the last one loaded.
        BufferedReader source = dump("row-1\tA", "\\.", "COPY catalog.next (id) FROM stdin;");
        CopyBlockReader reader = new CopyBlockReader(source);
        readFully(reader);

        reader.close();

        assertThat(source.readLine()).isEqualTo("COPY catalog.next (id) FROM stdin;");
    }

    @Test
    @DisplayName("only the three rewritten tables are excluded from streaming")
    void onlyRewrittenTablesAreBuffered() {
        // The three that are buffered need the block in hand: a dropped column, filtered rows,
        // de-duplicated rows. They are also the small ones. Adding a table here silently returns it
        // to the path that ran out of heap, so the list is asserted rather than assumed.
        assertThat(CatalogDataBootstrapService.needsLegacyAdaptation(
                "COPY catalog.apis (id, api_name) FROM stdin;")).isTrue();
        assertThat(CatalogDataBootstrapService.needsLegacyAdaptation(
                "COPY catalog.credentials (id, credential_name) FROM stdin;")).isTrue();
        assertThat(CatalogDataBootstrapService.needsLegacyAdaptation(
                "COPY catalog.tool_credentials (id, api_tool_id) FROM stdin;")).isTrue();

        // The three biggest tables - the ones that exhausted the heap - must stream.
        assertThat(CatalogDataBootstrapService.needsLegacyAdaptation(
                "COPY catalog.api_tools (id, api_id) FROM stdin;")).isFalse();
        assertThat(CatalogDataBootstrapService.needsLegacyAdaptation(
                "COPY catalog.api_tool_parameters (id, api_tool_id) FROM stdin;")).isFalse();
        assertThat(CatalogDataBootstrapService.needsLegacyAdaptation(
                "COPY catalog.tool_responses (id, tool_id) FROM stdin;")).isFalse();
    }
}
