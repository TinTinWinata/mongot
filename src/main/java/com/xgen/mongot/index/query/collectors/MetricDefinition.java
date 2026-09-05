package com.xgen.mongot.index.query.collectors;

import com.xgen.mongot.util.bson.parser.BsonDocumentBuilder;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import com.xgen.mongot.util.bson.parser.DocumentEncodable;
import com.xgen.mongot.util.bson.parser.DocumentParser;
import com.xgen.mongot.util.bson.parser.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.BsonDocument;

/**
 * A single metric requested alongside a {@link FacetDefinition.StringFacetDefinition}. Metrics are
 * accumulated per facet bucket over a numeric field, so that callers can obtain e.g. the average of
 * a field grouped by a string facet without transferring the matching documents out of mongot.
 *
 * <p>The MQL representation names the accumulator and the numeric path it reads, for example {@code
 * {avg: "detection.vehicleSpeed"}}. Exactly one accumulator must be specified.
 *
 * @param path The numeric path the accumulator reads. Must be indexed as {@code number} or {@code
 *     numberFacet}.
 */
public record MetricDefinition(Type type, String path) implements DocumentEncodable {

  /** All metric accumulator types, with their associated name in MQL. */
  public enum Type {
    AVG("avg"),
    MAX("max"),
    MIN("min"),
    SUM("sum");

    private final String name;

    Type(String name) {
      this.name = name;
    }

    public String getName() {
      return this.name;
    }
  }

  private static class Fields {
    private static Field.Optional<String> accumulatorField(Type type) {
      return Field.builder(type.getName())
          .stringField()
          .mustNotBeEmpty()
          .optional()
          .noDefault();
    }

    private static final Field.Optional<String> AVG = accumulatorField(Type.AVG);
    private static final Field.Optional<String> MAX = accumulatorField(Type.MAX);
    private static final Field.Optional<String> MIN = accumulatorField(Type.MIN);
    private static final Field.Optional<String> SUM = accumulatorField(Type.SUM);
  }

  private static Field.Optional<String> fieldFor(Type type) {
    return switch (type) {
      case AVG -> Fields.AVG;
      case MAX -> Fields.MAX;
      case MIN -> Fields.MIN;
      case SUM -> Fields.SUM;
    };
  }

  /** Deserializes a metric from BSON, requiring exactly one accumulator to be present. */
  public static MetricDefinition fromBson(DocumentParser parser) throws BsonParseException {
    List<MetricDefinition> specified = new ArrayList<>();
    for (Type type : Type.values()) {
      Optional<String> path = parser.getField(fieldFor(type)).unwrap();
      if (path.isPresent()) {
        specified.add(new MetricDefinition(type, path.get()));
      }
    }

    if (specified.size() != 1) {
      throw new BsonParseException(
          String.format(
              "must specify exactly one of the metric accumulators: %s",
              String.join(", ", Type.AVG.getName(), Type.MAX.getName(), Type.MIN.getName(),
                  Type.SUM.getName())),
          Optional.empty());
    }

    return specified.get(0);
  }

  @Override
  public BsonDocument toBson() {
    return BsonDocumentBuilder.builder()
        .field(fieldFor(this.type), Optional.of(this.path))
        .build();
  }
}
