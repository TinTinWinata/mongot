package com.xgen.mongot.index.query.collectors;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.xgen.mongot.util.bson.parser.BsonDocumentParser;
import com.xgen.mongot.util.bson.parser.BsonParseException;
import java.util.Map;
import org.bson.BsonDocument;
import org.junit.Test;

/** Covers parsing, validation, and round tripping of the {@code metrics} facet option. */
public class FacetMetricsParsingTest {

  @Test
  public void fromBson_stringFacetWithMetrics_parsesEachAccumulator() throws Exception {
    FacetDefinition.StringFacetDefinition definition =
        parse(
            "{type: 'string', path: 'vehicleType', numBuckets: 25, metrics: {"
                + "avgSpeed: {avg: 'vehicleSpeed'},"
                + "topSpeed: {max: 'vehicleSpeed'}"
                + "}}");

    assertEquals("vehicleType", definition.path());
    assertEquals(25, definition.numBuckets());
    assertEquals(
        Map.of(
            "avgSpeed", new MetricDefinition(MetricDefinition.Type.AVG, "vehicleSpeed"),
            "topSpeed", new MetricDefinition(MetricDefinition.Type.MAX, "vehicleSpeed")),
        definition.metrics());
  }

  @Test
  public void fromBson_stringFacetWithoutMetrics_defaultsToEmpty() throws Exception {
    assertThat(parse("{type: 'string', path: 'vehicleType'}").metrics()).isEmpty();
  }

  @Test
  public void toBson_roundTrips() throws Exception {
    BsonDocument original =
        BsonDocument.parse(
            "{type: 'string', path: 'vehicleType', numBuckets: 10,"
                + " metrics: {avgSpeed: {avg: 'vehicleSpeed'}}}");

    assertEquals(original, parse(original).toBson());
  }

  @Test
  public void toBson_withoutMetrics_omitsTheField() throws Exception {
    assertThat(parse("{type: 'string', path: 'vehicleType'}").toBson().containsKey("metrics"))
        .isFalse();
  }

  @Test
  public void fromBson_metricWithNoAccumulator_isRejected() {
    assertThrows(
        BsonParseException.class,
        () -> parse("{type: 'string', path: 'vehicleType', metrics: {avgSpeed: {}}}"));
  }

  @Test
  public void fromBson_metricWithMultipleAccumulators_isRejected() {
    assertThrows(
        BsonParseException.class,
        () ->
            parse(
                "{type: 'string', path: 'vehicleType',"
                    + " metrics: {speed: {avg: 'vehicleSpeed', max: 'vehicleSpeed'}}}"));
  }

  @Test
  public void fromBson_metricNameThatIsNotAValidFieldName_isRejected() {
    // Metric names are echoed back as field names and read by path in the sharded merge pipeline.
    assertThrows(
        BsonParseException.class,
        () ->
            parse(
                "{type: 'string', path: 'vehicleType',"
                    + " metrics: {'avg.speed': {avg: 'vehicleSpeed'}}}"));
    assertThrows(
        BsonParseException.class,
        () ->
            parse(
                "{type: 'string', path: 'vehicleType',"
                    + " metrics: {'$speed': {avg: 'vehicleSpeed'}}}"));
  }

  private static FacetDefinition.StringFacetDefinition parse(String json) throws Exception {
    return parse(BsonDocument.parse(json));
  }

  private static FacetDefinition.StringFacetDefinition parse(BsonDocument document)
      throws Exception {
    try (var parser = BsonDocumentParser.fromRoot(document).build()) {
      return (FacetDefinition.StringFacetDefinition) FacetDefinition.fromBson(parser);
    }
  }
}
