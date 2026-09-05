package com.xgen.mongot.index;

import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentEncodable;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import com.xgen.mongot.util.bson.parser.Value;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonValue;

public class FacetBucket implements DocumentEncodable {
  private static class Fields {
    static final Field.Required<BsonValue> ID =
        Field.builder("_id").unparsedValueField().required();

    static final Field.Required<Long> COUNT = Field.builder("count").longField().required();

    static final Field.Optional<Map<String, BsonValue>> METRICS =
        Field.builder("metrics")
            .mapOf(Value.builder().unparsedValueField().required())
            .optional()
            .noDefault();
  }

  private final BsonValue id;
  private final long count;
  private final Optional<Map<String, BsonValue>> metrics;

  public FacetBucket(BsonValue id, long count) {
    this(id, count, Optional.empty());
  }

  public FacetBucket(BsonValue id, long count, Optional<Map<String, BsonValue>> metrics) {
    this.id = id;
    this.count = count;
    this.metrics = metrics;
  }

  static FacetBucket fromBson(DocumentParser parser) throws BsonParseException {
    return new FacetBucket(
        parser.getField(Fields.ID).unwrap(),
        parser.getField(Fields.COUNT).unwrap(),
        parser.getField(Fields.METRICS).unwrap());
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder()
        .field(Fields.ID, this.id)
        .field(Fields.COUNT, this.count)
        .field(Fields.METRICS, this.metrics)
        .build();
  }

  /** Resolved metric values keyed by the name the query requested them under. */
  public Optional<Map<String, BsonValue>> getMetrics() {
    return this.metrics;
  }

  public long getCount() {
    return this.count;
  }

  public BsonValue getId() {
    return this.id;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.id, this.count, this.metrics);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    FacetBucket other = (FacetBucket) obj;
    return Objects.equals(this.id, other.id)
        && this.getCount() == other.getCount()
        && Objects.equals(this.metrics, other.metrics);
  }

  @Override
  public String toString() {
    return "FacetBucket(id="
        + this.id
        + ", count="
        + this.count
        + ", metrics="
        + this.metrics
        + ")";
  }
}
