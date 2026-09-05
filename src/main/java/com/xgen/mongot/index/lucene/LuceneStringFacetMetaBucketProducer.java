package com.xgen.mongot.index.lucene;

import static com.xgen.mongot.util.Check.checkState;

import com.xgen.mongot.index.IntermediateFacetBucket;
import com.xgen.mongot.index.MetricAccumulator;
import com.xgen.mongot.index.lucene.explain.explainers.FacetFeatureExplainer;
import com.xgen.mongot.index.query.collectors.FacetDefinition;
import com.xgen.mongot.util.Check;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.bson.BsonString;

/**
 * LuceneStringFacetMetaBucketProducer is responsible for formatting buckets for a single {@link
 * FacetDefinition.StringFacetDefinition} facet. It uses a {@link SortedSetDocValuesFacetCounts} to
 * get a {@link LabelAndValue} for each ordinal in the given {@link
 * org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState.OrdRange}. nextBucket() should be
 * called to output buckets for each ordinal until isExhausted() is true. Responsible for one index
 * partition.
 */
class LuceneStringFacetMetaBucketProducer implements LuceneMetaBucketProducer {

  private final List<IntermediateFacetBucket> buckets;

  private int position;

  public LuceneStringFacetMetaBucketProducer(List<IntermediateFacetBucket> buckets) {
    this.buckets = buckets;

    this.position = 0;
  }

  /**
   * Creates a producer for buckets that carry counts only. Used by the drill sideways path, where
   * metrics are rejected at query validation because each facet is collected over a different set
   * of matching documents.
   */
  public static LuceneStringFacetMetaBucketProducer create(
      FacetResult facetCounts, String facetName) throws IOException {
    return create(
        facetCounts, facetName, new FacetDefinition.StringFacetDefinition(facetName, 1), Map.of());
  }

  public static LuceneStringFacetMetaBucketProducer create(
      FacetResult facetCounts,
      String facetName,
      FacetDefinition.StringFacetDefinition definition,
      Map<String, Map<String, MetricAccumulator>> metricsByLabel)
      throws IOException {
    List<IntermediateFacetBucket> buckets =
        Stream.of(facetCounts.labelValues)
            // If the value is 0, that means that docs with this ordinal
            // were filtered out be the search.  Do not return a bucket.
            .filter(child -> child.value.intValue() > 0 && !child.label.isEmpty())
            .map(
                child ->
                    new IntermediateFacetBucket(
                        IntermediateFacetBucket.Type.FACET,
                        facetName,
                        new BsonString(child.label),
                        child.value.longValue(),
                        LuceneFacetMetrics.accumulatorsFor(
                            definition, metricsByLabel, child.label)))
            .toList();

    Optional<FacetFeatureExplainer> explainer = LuceneFacetResultUtil.getFacetFeatureExplainer();
    explainer.ifPresent(
        exp -> exp.addIntermediateQueriedStringFacetCardinalities(facetName, buckets));

    return new LuceneStringFacetMetaBucketProducer(buckets);
  }

  @Override
  public IntermediateFacetBucket peek() throws IOException {
    checkState(!isExhausted(), "Can't call peek() when BucketProducer is exhausted");

    if (this.position < this.buckets.size()) {
      return this.buckets.get(this.position);
    }

    return Check.unreachable("Position is beyond buckets size");
  }

  @Override
  public void acceptAndAdvance() {
    checkState(!isExhausted(), "Can't call accept() when BucketProducer is exhausted");

    this.position++;
  }

  @Override
  public boolean isExhausted() {
    return this.position >= this.buckets.size();
  }
}
