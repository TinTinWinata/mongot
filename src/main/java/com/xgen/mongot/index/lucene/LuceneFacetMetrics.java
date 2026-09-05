package com.xgen.mongot.index.lucene;

import com.xgen.mongot.index.MetricAccumulator;
import com.xgen.mongot.index.lucene.facet.SortedSetDocValuesFacetMetrics;
import com.xgen.mongot.index.query.InvalidQueryException;
import com.xgen.mongot.index.query.collectors.FacetDefinition;
import com.xgen.mongot.index.query.collectors.MetricDefinition;
import com.xgen.mongot.util.FieldPath;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.lucene.facet.FacetsCollector;
import org.bson.BsonValue;

/**
 * Evaluates the {@link MetricDefinition}s attached to a string facet, and resolves the accumulated
 * state onto the buckets that are returned to the caller.
 *
 * <p>Metrics are read from the same matching documents the facet counts are read from, so a metric
 * costs one extra columnar pass over the metric's doc values field and nothing else. Nothing is
 * fetched from stored fields and no documents are materialized.
 */
final class LuceneFacetMetrics {

  private LuceneFacetMetrics() {}

  /** Accumulator state per bucket label, keyed by the name each metric was requested under. */
  static Map<String, Map<String, MetricAccumulator>> compute(
      FacetDefinition.StringFacetDefinition definition,
      LuceneFacetContext facetContext,
      FacetsCollector collector,
      String groupField,
      Optional<String> dimension,
      Optional<FieldPath> returnScope)
      throws IOException, InvalidQueryException {
    if (definition.metrics().isEmpty()) {
      return Map.of();
    }

    Map<String, Map<String, MetricAccumulator>> byLabel = new HashMap<>();
    for (Map.Entry<String, MetricDefinition> entry : definition.metrics().entrySet()) {
      LuceneFacetContext.NumericMetricField metricField =
          facetContext.getNumericMetricField(entry.getValue().path(), returnScope);

      Map<String, MetricAccumulator> perLabel =
          SortedSetDocValuesFacetMetrics.compute(
              collector,
              groupField,
              dimension,
              metricField.luceneFieldName(),
              metricField.decoder());

      perLabel.forEach(
          (label, accumulator) ->
              byLabel.computeIfAbsent(label, unused -> new HashMap<>())
                  .put(entry.getKey(), accumulator));
    }
    return byLabel;
  }

  /**
   * Returns the mergeable accumulator state for one bucket, or empty when the facet requested no
   * metrics. A requested metric that no document in the bucket contributed to is present with an
   * empty accumulator, so that merging across partitions and shards stays well defined.
   */
  static Optional<Map<String, MetricAccumulator>> accumulatorsFor(
      FacetDefinition.StringFacetDefinition definition,
      Map<String, Map<String, MetricAccumulator>> byLabel,
      String label) {
    if (definition.metrics().isEmpty()) {
      return Optional.empty();
    }

    Map<String, MetricAccumulator> forLabel = byLabel.getOrDefault(label, Map.of());
    Map<String, MetricAccumulator> result = new HashMap<>();
    for (String metricName : definition.metrics().keySet()) {
      result.put(metricName, forLabel.getOrDefault(metricName, MetricAccumulator.EMPTY));
    }
    return Optional.of(result);
  }

  /**
   * Resolves accumulator state to the values returned to the caller. Metrics whose bucket held no
   * contributing document are omitted rather than reported as zero, matching how {@code $avg}
   * reports a group with no numeric values.
   */
  static Optional<Map<String, BsonValue>> resolve(
      FacetDefinition.StringFacetDefinition definition,
      Map<String, Map<String, MetricAccumulator>> byLabel,
      String label) {
    if (definition.metrics().isEmpty()) {
      return Optional.empty();
    }

    Map<String, MetricAccumulator> forLabel = byLabel.getOrDefault(label, Map.of());
    return Optional.of(resolve(definition.metrics(), forLabel));
  }

  /** Resolves already merged accumulator state against the metric definitions that produced it. */
  static Map<String, BsonValue> resolve(
      Map<String, MetricDefinition> metrics, Map<String, MetricAccumulator> accumulators) {
    Map<String, BsonValue> result = new HashMap<>();
    metrics.forEach(
        (metricName, metric) ->
            accumulators
                .getOrDefault(metricName, MetricAccumulator.EMPTY)
                .resolve(metric.type())
                .ifPresent(value -> result.put(metricName, value)));
    return result;
  }
}
