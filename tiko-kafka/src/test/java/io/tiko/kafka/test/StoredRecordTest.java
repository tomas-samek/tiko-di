package io.tiko.kafka.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.kafka.test.FakeKafkaBroker.StoredRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code FakeKafkaBroker.StoredRecord}'s hand-written {@code equals}/{@code hashCode}/
 * {@code toString} (#236): they exist because the {@code byte[] payload} component otherwise gets
 * array-identity semantics, so the contract worth testing is content-based comparison.
 */
class StoredRecordTest {

    @Test
    void equalsAndHashCodeComparePayloadByContent() {
        var headers = new RecordHeaders();
        var a = new StoredRecord(0, new byte[] {1, 2, 3}, headers, 100L);
        // Distinct array instance, identical contents — default record equals would say "not equal".
        var b = new StoredRecord(0, new byte[] {1, 2, 3}, headers, 100L);

        assertThat(a).isEqualTo(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void notEqualWhenAnyComponentDiffers() {
        var headers = new RecordHeaders();
        var base = new StoredRecord(0, new byte[] {1, 2, 3}, headers, 100L);

        assertThat(base)
                .isNotEqualTo(new StoredRecord(0, new byte[] {9}, headers, 100L)) // payload
                .isNotEqualTo(new StoredRecord(1, new byte[] {1, 2, 3}, headers, 100L)) // offset
                .isNotEqualTo(new StoredRecord(0, new byte[] {1, 2, 3}, headers, 999L)) // timestamp
                .isNotEqualTo("not a StoredRecord"); // instanceof-false branch
    }

    @Test
    void toStringShowsPayloadContentsNotArrayIdentity() {
        var rec = new StoredRecord(7, new byte[] {1, 2}, new RecordHeaders(), 42L);

        assertThat(rec.toString()).contains("offset=7").contains("[1, 2]").contains("timestamp=42");
    }
}
