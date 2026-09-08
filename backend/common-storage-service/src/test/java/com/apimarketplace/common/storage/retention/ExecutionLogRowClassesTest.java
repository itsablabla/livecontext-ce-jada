package com.apimarketplace.common.storage.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.apimarketplace.common.storage.retention.ExecutionLogRowClasses.isPurgeableExecutionLog;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExecutionLogRowClasses")
class ExecutionLogRowClassesTest {

    @Nested
    @DisplayName("Classes retention may delete")
    class Allowed {

        @Test
        @DisplayName("A skipped-node record is journal and may be deleted")
        void skippedNodeJson() {
            assertTrue(isPurgeableExecutionLog("SKIPPED_NODE", "JSON", null, null));
        }

        @Test
        @DisplayName("A step output held as JSON in the database may be deleted")
        void stepOutputJson() {
            assertTrue(isPurgeableExecutionLog("STEP_OUTPUT", "JSON", null, null));
        }

        @Test
        @DisplayName("Agent message and tool-result overflow payloads may be deleted")
        void agentPayloadText() {
            assertTrue(isPurgeableExecutionLog(null, "TEXT", "agent_message.txt", null));
            assertTrue(isPurgeableExecutionLog(null, "TEXT", "tool_call_result.txt", null));
        }
    }

    @Nested
    @DisplayName("Breakdown category")
    class BreakdownCategory {

        /**
         * Mirrors the WRITE path, not the row's appearance. StorageService.save
         * picks by source type (JSON classes land in STEP_OUTPUTS) while saveText
         * hardcodes FILES. Debiting a bucket a row was never credited to leaves the
         * original credit standing, and used_bytes is recomputed FROM the breakdown,
         * so the inflation is permanent and billed.
         */
        @Test
        @DisplayName("JSON classes were credited to STEP_OUTPUTS")
        void jsonClassesBookStepOutputs() {
            assertEquals("STEP_OUTPUTS",
                    ExecutionLogRowClasses.breakdownCategoryFor("STEP_OUTPUT", "JSON"));
            assertEquals("STEP_OUTPUTS",
                    ExecutionLogRowClasses.breakdownCategoryFor("SKIPPED_NODE", "JSON"));
        }

        @Test
        @DisplayName("The agent TEXT payloads were credited to FILES, because saveText books FILES for everything")
        void textClassBooksFiles() {
            assertEquals("FILES", ExecutionLogRowClasses.breakdownCategoryFor(null, "TEXT"));
        }

        @Test
        @DisplayName("A class outside the allow-list has no category rather than a default one")
        void unknownClassHasNoCategory() {
            assertNull(ExecutionLogRowClasses.breakdownCategoryFor("USER_AVATAR", "BINARY"));
            assertNull(ExecutionLogRowClasses.breakdownCategoryFor("S3_FILE", "S3_FILE"));
            assertNull(ExecutionLogRowClasses.breakdownCategoryFor("STEP_OUTPUT", null));
        }
    }

    @Nested
    @DisplayName("The s3_key veto")
    class S3Veto {

        /**
         * The single most important assertion here. STEP_OUTPUT names both a JSON
         * payload and a file the workflow produced (a generated mp3, image or video
         * the user still owns). Only storage_type and s3_key tell them apart, and
         * production carried 2,285 of the file kind holding about 16 GB when this
         * was written.
         */
        @Test
        @DisplayName("A STEP_OUTPUT backed by an object is a produced file, never journal")
        void stepOutputWithObjectIsAFile() {
            assertFalse(isPurgeableExecutionLog("STEP_OUTPUT", "S3_FILE", "clip.mp4", "1/abc/clip.mp4"));
        }

        @Test
        @DisplayName("An s3_key vetoes a row even when its class would otherwise be allowed")
        void s3KeyVetoesAnAllowedClass() {
            assertFalse(isPurgeableExecutionLog("STEP_OUTPUT", "JSON", null, "1/abc/payload.json"));
            assertFalse(isPurgeableExecutionLog("SKIPPED_NODE", "JSON", null, "1/abc/skip.json"));
            assertFalse(isPurgeableExecutionLog(null, "TEXT", "agent_message.txt", "1/abc/msg.txt"));
        }

        @Test
        @DisplayName("A blank s3_key is not an object reference and does not veto")
        void blankS3KeyDoesNotVeto() {
            assertTrue(isPurgeableExecutionLog("STEP_OUTPUT", "JSON", null, "   "));
        }
    }

    @Nested
    @DisplayName("Classes retention must never touch")
    class Denied {

        @Test
        @DisplayName("User files, chat attachments and folders are not journal")
        void userDataIsDenied() {
            assertFalse(isPurgeableExecutionLog("S3_FILE", "S3_FILE", "report.pdf", "1/x/report.pdf"));
            assertFalse(isPurgeableExecutionLog("CHAT_ATTACHMENT", "BINARY", "photo.png", null));
            assertFalse(isPurgeableExecutionLog("FOLDER", "JSON", null, null));
        }

        @Test
        @DisplayName("Avatars are small, permanent and unrelated to any run")
        void avatarsAreDenied() {
            assertFalse(isPurgeableExecutionLog("USER_AVATAR", "BINARY", null, null));
            assertFalse(isPurgeableExecutionLog("ORG_AVATAR", "BINARY", null, null));
        }

        @Test
        @DisplayName("Interface renders are workflow output the user can still be showing")
        void interfaceRendersAreDenied() {
            assertFalse(isPurgeableExecutionLog("INTERFACE_VIDEO", "S3_FILE", "v.mp4", "1/x/v.mp4"));
            assertFalse(isPurgeableExecutionLog("INTERFACE_SCREENSHOT", "S3_FILE", "s.png", "1/x/s.png"));
            assertFalse(isPurgeableExecutionLog("INTERFACE_PDF", "S3_FILE", "d.pdf", "1/x/d.pdf"));
        }

        @Test
        @DisplayName("SIGNAL rows are not in the allow-list, so they are kept")
        void signalIsKept() {
            assertFalse(isPurgeableExecutionLog("SIGNAL", "JSON", null, null));
        }

        /**
         * The property the allow-list exists for: a class invented after this file
         * was written is retained, with nobody having to remember to deny it.
         */
        @Test
        @DisplayName("A source type this file has never heard of is kept, in every storage type")
        void unknownSourceTypeIsKept() {
            assertFalse(isPurgeableExecutionLog("SOMETHING_SHIPPED_IN_2027", "JSON", null, null));
            assertFalse(isPurgeableExecutionLog("SOMETHING_SHIPPED_IN_2027", "TEXT", "x.txt", null));
            assertFalse(isPurgeableExecutionLog("SOMETHING_SHIPPED_IN_2027", "BINARY", null, null));
        }

        @Test
        @DisplayName("A TEXT row is kept unless its file name is one of the two agent payloads")
        void otherTextRowsAreKept() {
            assertFalse(isPurgeableExecutionLog(null, "TEXT", "notes.txt", null));
            assertFalse(isPurgeableExecutionLog(null, "TEXT", null, null));
            // A TEXT row that DOES carry a source type is something else entirely.
            assertFalse(isPurgeableExecutionLog("S3_FILE", "TEXT", "agent_message.txt", null));
        }

        @Test
        @DisplayName("A null storage type is never purgeable")
        void nullStorageTypeIsKept() {
            assertFalse(isPurgeableExecutionLog("STEP_OUTPUT", null, null, null));
        }
    }
}
