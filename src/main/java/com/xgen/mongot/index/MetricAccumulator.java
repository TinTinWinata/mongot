package com.xgen.mongot.index;

import com.xgen.mongot.index.query.collectors.MetricDefinition;
import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentEncodable;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonValue;

/**
 * The mergeable state backing a single {@link MetricDefinition}, accumulated over the documents in
 * one facet bucket.
 *
 * <p>Every merge boundary - lucene segment, index partition, and shard - transports {@code count}
 * and {@code sum} rather than a resolved average, because an average is not associative: merging
 * per-shard averages is only correct when the shards contribute equal numbers of documents. The
 * division happens exactly once, in {@link #resolve}, after all merging is complete.
 *
 * @param count The number of documents that contributed a value. Documents missing the metric path,
 *     or holding an array at it, contribute nothing.
 */
public record MetricAccumulator(long count, double sum, double min, double max)
    implements DocumentEncodable {

  public static final MetricAccumulator EMPTY =
      new MetricAccumulator(0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);

  private static class Fields {
    static final Field.Required<Long> COUNT = Field.builder("count").longField().required();

    static final Field.Required<Double> SUM = Field.builder("sum").doubleField().required();

    static final Field.Required<Double> MIN = Field.builder("min").doubleField().required();

    static final Field.Required<Double> MAX = Field.builder("max").doubleField().required();
  }

  public static MetricAccumulator of(double value) {
    return new MetricAccumulator(1, value, value, value);
  }

  /** Combines two accumulators over disjoint sets of documents. */
  public MetricAccumulator merge(MetricAccumulator other) {
    return new MetricAccumulator(
        this.count + other.count,
        this.sum + other.sum,
        Math.min(this.min, other.min),
        Math.max(this.max, other.max));
  }

  public boolean isEmpty() {
    return this.count == 0;
  }

  /**
   * Resolves this accumulator to the value of the given metric type, or empty when no document in
   * the bucket contributed a value.
   */
  public Optional<BsonValue> resolve(MetricDefinition.Type type) {
    if (isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        switch (type) {
          case AVG -> new BsonDouble(this.sum / this.count);
          case MAX -> new BsonDouble(this.max);
          case MIN -> new BsonDouble(this.min);
          case SUM -> new BsonDouble(this.sum);
        });
  }

  public static MetricAccumulator fromBson(DocumentParser parser) throws BsonParseException {
    return new MetricAccumulator(
        parser.getField(Fields.COUNT).unwrap(),
        parser.getField(Fields.SUM).unwrap(),
        parser.getField(Fields.MIN).unwrap(),
        parser.getField(Fields.MAX).unwrap());
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder()
        .field(Fields.COUNT, this.count)
        .field(Fields.SUM, this.sum)
        .field(Fields.MIN, this.min)
        .field(Fields.MAX, this.max)
        .build();
  }
}
