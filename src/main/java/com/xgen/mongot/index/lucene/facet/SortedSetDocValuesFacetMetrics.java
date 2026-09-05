package com.xgen.mongot.index.lucene.facet;

import com.google.errorprone.annotations.Var;
import com.xgen.mongot.index.MetricAccumulator;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongToDoubleFunction;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollector.MatchingDocs;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.ConjunctionUtils;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRef;

/**
 * Accumulates a {@link MetricAccumulator} per string facet bucket over the documents matched by a
 * {@link FacetsCollector}, reading the bucket from a {@link SortedSetDocValues} field and the
 * metric value from a numeric doc values field.
 *
 * <p>This is the metric analogue of {@link SortedSetDocValuesFacetCounts}: it walks the same
 * matching documents over the same columnar doc values, so no stored fields are decompressed and no
 * documents are materialized. Unlike the counting implementation it accumulates in segment-ordinal
 * space and resolves ordinals to labels once per segment, which avoids needing a global {@link
 * org.apache.lucene.index.OrdinalMap} at the cost of one hash lookup per distinct bucket per
 * segment.
 *
 * <p>Documents that do not have exactly one numeric value at the metric path contribute nothing,
 * which matches how MQL's {@code $avg} ignores a field holding an array. Numeric fields reach the
 * index either as {@code NUMERIC} doc values (facet fields) or as {@code SORTED_NUMERIC} (the
 * sortable "v2" fields, written through lucene's {@code LongField}), so both are read here.
 */
public final class SortedSetDocValuesFacetMetrics {

  private SortedSetDocValuesFacetMetrics() {}

  /**
   * Computes one accumulator per facet bucket label.
   *
   * @param groupField the lucene field holding the facet ordinals
   * @param dimension present when {@code groupField} is a {@link FacetsConfig} encoded field, in
   *     which case ordinals are labelled {@code dimension + DELIM_CHAR + value} and ordinals
   *     belonging to other dimensions are skipped. Empty for {@code token} fields, whose ordinals
   *     are the bucket labels verbatim.
   * @param valueField the lucene field holding the numeric metric values
   * @param valueDecoder converts the indexed long back to the value it was derived from, which
   *     depends on the numeric representation the field was indexed with
   */
  public static Map<String, MetricAccumulator> compute(
      FacetsCollector hits,
      String groupField,
      Optional<String> dimension,
      String valueField,
      LongToDoubleFunction valueDecoder)
      throws IOException {
    Map<String, MetricAccumulator> results = new HashMap<>();
    for (MatchingDocs matchingDocs : hits.getMatchingDocs()) {
      computeOneSegment(results, matchingDocs, groupField, dimension, valueField, valueDecoder);
    }
    return results;
  }

  private static void computeOneSegment(
      Map<String, MetricAccumulator> results,
      MatchingDocs hits,
      String groupField,
      Optional<String> dimension,
      String valueField,
      LongToDoubleFunction valueDecoder)
      throws IOException {
    if (hits.totalHits() == 0) {
      return;
    }

    LeafReader reader = hits.context().reader();
    SortedSetDocValues groupValues = DocValues.getSortedSet(reader, groupField);
    if (groupValues == null || groupValues.getValueCount() == 0) {
      return;
    }

    // Numeric fields are written either as NUMERIC (facet fields) or SORTED_NUMERIC (the sortable
    // v2 fields, via lucene's LongField). getSortedNumeric transparently adapts the former.
    SortedNumericDocValues metricValues = DocValues.getSortedNumeric(reader, valueField);

    int segmentCardinality = (int) groupValues.getValueCount();
    long[] counts = new long[segmentCardinality];
    double[] sums = new double[segmentCardinality];
    double[] mins = new double[segmentCardinality];
    double[] maxes = new double[segmentCardinality];
    Arrays.fill(mins, Double.POSITIVE_INFINITY);
    Arrays.fill(maxes, Double.NEGATIVE_INFINITY);

    // It is slightly more efficient to work against SortedDocValues when the field is actually
    // single-valued (see: LUCENE-5309).
    SortedDocValues singleGroupValue = DocValues.unwrapSingleton(groupValues);
    DocIdSetIterator groupIterator = singleGroupValue != null ? singleGroupValue : groupValues;
    DocIdSetIterator it =
        ConjunctionUtils.intersectIterators(
            Arrays.asList(hits.bits().iterator(), groupIterator));

    @Var boolean anyValues = false;
    for (@Var int doc = it.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = it.nextDoc()) {
      if (!metricValues.advanceExact(doc) || metricValues.docValueCount() != 1) {
        // The document has no value, or holds an array, at the metric path.
        continue;
      }
      double value = valueDecoder.applyAsDouble(metricValues.nextValue());
      anyValues = true;

      if (singleGroupValue != null) {
        accumulate(counts, sums, mins, maxes, singleGroupValue.ordValue(), value);
      } else {
        for (int i = 0; i < groupValues.docValueCount(); i++) {
          accumulate(counts, sums, mins, maxes, (int) groupValues.nextOrd(), value);
        }
      }
    }

    if (!anyValues) {
      return;
    }

    // Resolve segment ordinals to labels once, after accumulating, and fold into the global map.
    for (int ord = 0; ord < segmentCardinality; ord++) {
      if (counts[ord] == 0) {
        continue;
      }
      BytesRef term = groupValues.lookupOrd(ord);
      Optional<String> label = toLabel(term, dimension);
      if (label.isEmpty()) {
        continue;
      }
      MetricAccumulator accumulator =
          new MetricAccumulator(counts[ord], sums[ord], mins[ord], maxes[ord]);
      results.merge(label.get(), accumulator, MetricAccumulator::merge);
    }
  }

  private static void accumulate(
      long[] counts, double[] sums, double[] mins, double[] maxes, int ord, double value) {
    counts[ord]++;
    sums[ord] += value;
    mins[ord] = Math.min(mins[ord], value);
    maxes[ord] = Math.max(maxes[ord], value);
  }

  /**
   * Returns the bucket label for an ordinal, or empty when the ordinal belongs to a different
   * dimension of a shared {@link FacetsConfig} field.
   */
  private static Optional<String> toLabel(BytesRef term, Optional<String> dimension) {
    String value = term.utf8ToString();
    if (dimension.isEmpty()) {
      return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    String prefix = dimension.get() + FacetsConfig.DELIM_CHAR;
    if (!value.startsWith(prefix)) {
      return Optional.empty();
    }

    String label = value.substring(prefix.length());
    return label.isEmpty() ? Optional.empty() : Optional.of(label);
  }
}
