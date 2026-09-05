package com.xgen.mongot.index.lucene;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.xgen.mongot.featureflag.FeatureFlags;
import com.xgen.mongot.index.FacetBucket;
import com.xgen.mongot.index.MetaResults;
import com.xgen.mongot.index.MetricAccumulator;
import com.xgen.mongot.index.definition.IndexDefinition;
import com.xgen.mongot.index.definition.NumericFieldOptions;
import com.xgen.mongot.index.definition.SearchIndexCapabilities;
import com.xgen.mongot.index.definition.SearchIndexDefinition;
import com.xgen.mongot.index.lucene.codec.LuceneCodec;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.lucene.searcher.LuceneSearcherFactory;
import com.xgen.mongot.index.lucene.searcher.LuceneSearcherManager;
import com.xgen.mongot.index.lucene.searcher.QueryCacheProvider;
import com.xgen.mongot.index.query.CollectorQuery;
import com.xgen.mongot.index.query.InvalidQueryException;
import com.xgen.mongot.index.query.collectors.FacetCollector;
import com.xgen.mongot.index.query.collectors.FacetDefinition;
import com.xgen.mongot.index.query.collectors.MetricDefinition;
import com.xgen.mongot.index.query.counts.Count;
import com.xgen.mongot.index.query.operators.AllDocumentsOperator;
import com.xgen.mongot.index.version.IndexFormatVersion;
import com.xgen.mongot.util.FieldPath;
import com.xgen.testing.mongot.index.definition.DocumentFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.FieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.NumericFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.SearchIndexDefinitionBuilder;
import com.xgen.testing.mongot.index.definition.StringFacetFieldDefinitionBuilder;
import com.xgen.testing.mongot.index.query.CollectorQueryBuilder;
import com.xgen.testing.mongot.index.query.collectors.FacetCollectorBuilder;
import com.xgen.testing.mongot.index.query.counts.CountBuilder;
import com.xgen.testing.mongot.mock.index.SearchIndex;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.bson.BsonDouble;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.Test;

/** Covers facet metrics: accumulation over doc values, resolution, and merging. */
public class LuceneFacetMetricsTest {

  private static final String CATEGORY_FIELD = "category";
  private static final String PRICE_FIELD = "price";
  private static final String GROUP_FIELD = "group";

  private static final String PRICE_LUCENE_FIELD =
      FieldName.TypeField.NUMBER_INT64_V2.getLuceneFieldName(
          FieldPath.parse(PRICE_FIELD), Optional.empty());

  @Test
  public void buildFacetMetaResults_avgMetric_averagesPerBucket() throws Exception {
    FacetDefinition.StringFacetDefinition facet =
        new FacetDefinition.StringFacetDefinition(
            CATEGORY_FIELD,
            10,
            Map.of("avgPrice", new MetricDefinition(MetricDefinition.Type.AVG, PRICE_FIELD)));

    // electronics: 10, 20, 30 -> avg 20. books: 5, 15 -> avg 10.
    // The "toys" document does not match the query and must not contribute.
    MetaResults results =
        runFacetQuery(
            facet,
            List.of(
                doc("electronics", 10, "hit"),
                doc("electronics", 20, "hit"),
                doc("electronics", 30, "hit"),
                doc("books", 5, "hit"),
                doc("books", 15, "hit"),
                doc("toys", 1000, "miss")));

    assertThat(results.count().getTotal()).hasValue(5L);
    List<FacetBucket> buckets = results.facet().orElseThrow().get("categoryFacet").buckets();

    assertEquals(2, buckets.size());
    assertEquals(new BsonString("electronics"), buckets.get(0).getId());
    assertEquals(3L, buckets.get(0).getCount());
    assertEquals(OptionalDouble.of(20.0), metric(buckets.get(0), "avgPrice"));

    assertEquals(new BsonString("books"), buckets.get(1).getId());
    assertEquals(2L, buckets.get(1).getCount());
    assertEquals(OptionalDouble.of(10.0), metric(buckets.get(1), "avgPrice"));
  }

  @Test
  public void buildFacetMetaResults_multipleMetrics_resolvesEachIndependently() throws Exception {
    FacetDefinition.StringFacetDefinition facet =
        new FacetDefinition.StringFacetDefinition(
            CATEGORY_FIELD,
            10,
            Map.of(
                "avgPrice", new MetricDefinition(MetricDefinition.Type.AVG, PRICE_FIELD),
                "minPrice", new MetricDefinition(MetricDefinition.Type.MIN, PRICE_FIELD),
                "maxPrice", new MetricDefinition(MetricDefinition.Type.MAX, PRICE_FIELD),
                "sumPrice", new MetricDefinition(MetricDefinition.Type.SUM, PRICE_FIELD)));

    MetaResults results =
        runFacetQuery(
            facet,
            List.of(doc("books", 5, "hit"), doc("books", 15, "hit"), doc("books", 40, "hit")));

    FacetBucket bucket = results.facet().orElseThrow().get("categoryFacet").buckets().get(0);
    assertEquals(OptionalDouble.of(20.0), metric(bucket, "avgPrice"));
    assertEquals(OptionalDouble.of(5.0), metric(bucket, "minPrice"));
    assertEquals(OptionalDouble.of(40.0), metric(bucket, "maxPrice"));
    assertEquals(OptionalDouble.of(60.0), metric(bucket, "sumPrice"));
  }

  @Test
  public void buildFacetMetaResults_bucketWithNoNumericValues_omitsMetric() throws Exception {
    FacetDefinition.StringFacetDefinition facet =
        new FacetDefinition.StringFacetDefinition(
            CATEGORY_FIELD,
            10,
            Map.of("avgPrice", new MetricDefinition(MetricDefinition.Type.AVG, PRICE_FIELD)));

    // "clothing" documents carry no price at all, so the bucket exists but the metric does not.
    MetaResults results =
        runFacetQuery(
            facet,
            List.of(
                doc("books", 10, "hit"),
                docWithoutPrice("clothing", "hit"),
                docWithoutPrice("clothing", "hit")));

    Map<String, FacetBucket> byId =
        results.facet().orElseThrow().get("categoryFacet").buckets().stream()
            .collect(
                Collectors.toMap(
                    bucket -> bucket.getId().asString().getValue(), bucket -> bucket));

    assertEquals(2L, byId.get("clothing").getCount());
    assertThat(byId.get("clothing").getMetrics()).isPresent();
    assertThat(byId.get("clothing").getMetrics().orElseThrow()).doesNotContainKey("avgPrice");

    assertEquals(OptionalDouble.of(10.0), metric(byId.get("books"), "avgPrice"));
  }

  @Test
  public void buildFacetMetaResults_noMetricsRequested_leavesBucketsUnchanged() throws Exception {
    MetaResults results =
        runFacetQuery(
            new FacetDefinition.StringFacetDefinition(CATEGORY_FIELD, 10),
            List.of(doc("books", 10, "hit")));

    FacetBucket bucket = results.facet().orElseThrow().get("categoryFacet").buckets().get(0);
    assertEquals(1L, bucket.getCount());
    assertThat(bucket.getMetrics()).isEmpty();
  }

  @Test
  public void buildFacetMetaResults_metricOverUnindexedPath_isRejected() {
    FacetDefinition.StringFacetDefinition facet =
        new FacetDefinition.StringFacetDefinition(
            CATEGORY_FIELD,
            10,
            Map.of("avgWeight", new MetricDefinition(MetricDefinition.Type.AVG, "weight")));

    assertThrows(
        InvalidQueryException.class,
        () -> runFacetQuery(facet, List.of(doc("books", 10, "hit"))));
  }

  @Test
  public void merge_isAssociativeAcrossPartitions() {
    // Averaging averages is only correct for equally sized groups, so the accumulator has to carry
    // the sum and count instead. Two partitions of unequal size prove the difference.
    MetricAccumulator left = MetricAccumulator.of(10).merge(MetricAccumulator.of(20));
    MetricAccumulator right = MetricAccumulator.of(60);

    MetricAccumulator merged = left.merge(right);

    assertEquals(3L, merged.count());
    assertEquals(30.0, merged.resolve(MetricDefinition.Type.AVG).orElseThrow().asDouble()
        .getValue(), 0.0);
    // The naive merge of per-partition averages would have produced 37.5.
    assertEquals(10.0, merged.resolve(MetricDefinition.Type.MIN).orElseThrow().asDouble()
        .getValue(), 0.0);
    assertEquals(60.0, merged.resolve(MetricDefinition.Type.MAX).orElseThrow().asDouble()
        .getValue(), 0.0);
  }

  @Test
  public void merge_withEmptyAccumulator_isIdentity() {
    MetricAccumulator accumulator = MetricAccumulator.of(7);

    assertEquals(accumulator, MetricAccumulator.EMPTY.merge(accumulator));
    assertEquals(accumulator, accumulator.merge(MetricAccumulator.EMPTY));
    assertThat(MetricAccumulator.EMPTY.resolve(MetricDefinition.Type.AVG)).isEmpty();
  }

  private static OptionalDouble metric(FacetBucket bucket, String name) {
    Map<String, BsonValue> metrics = bucket.getMetrics().orElseThrow();
    BsonValue value = metrics.get(name);
    return value == null
        ? OptionalDouble.empty()
        : OptionalDouble.of(((BsonDouble) value).getValue());
  }

  private static MetaResults runFacetQuery(
      FacetDefinition.StringFacetDefinition facet, List<Document> documents)
      throws IOException, InterruptedException, InvalidQueryException {
    LuceneFacetContext facetContext = createFacetContext();

    FacetCollector collector =
        new FacetCollectorBuilder()
            .operator(AllDocumentsOperator.INSTANCE)
            .facetDefinitions(Map.of("categoryFacet", facet))
            .build();
    CollectorQuery collectorQuery =
        CollectorQueryBuilder.builder()
            .collector(collector)
            .count(CountBuilder.builder().type(Count.Type.TOTAL).build())
            .returnStoredSource(false)
            .build();

    var facetsConfig = new FacetsConfig();
    try (var directory = new ByteBuffersDirectory();
        var writer =
            new IndexWriter(directory, new IndexWriterConfig().setCodec(new LuceneCodec()))) {
      for (Document document : documents) {
        writer.addDocument(facetsConfig.build(document));
      }
      writer.commit();

      var searcherManager =
          LuceneSearcherManager.create(
              writer,
              new LuceneSearcherFactory(
                  SearchIndex.MOCK_FACET_INDEX_DEFINITION,
                  false,
                  new QueryCacheProvider.DefaultQueryCacheProvider(),
                  Optional.empty(),
                  SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.SEARCH)),
              SearchIndex.mockMetricsFactory(),
              () -> false);
      var searcherReference =
          LuceneIndexSearcherReference.create(
              searcherManager,
              SearchIndex.mockQueryMetricsUpdater(IndexDefinition.Type.SEARCH),
              FeatureFlags.getDefault());

      FacetsCollectorManager.FacetsResult result =
          FacetsCollectorManager.search(
              searcherReference.getIndexSearcher(),
              new TermQuery(new Term(GROUP_FIELD, "hit")),
              100,
              new FacetsCollectorManager());

      return new LuceneMetaResultsBuilder(facetContext, Optional.empty())
          .buildFacetMetaResults(
              searcherReference,
              result.topDocs(),
              result.facetsCollector(),
              collectorQuery,
              false);
    }
  }

  private static Document doc(String category, long price, String group) {
    Document document = docWithoutPrice(category, group);
    document.add(new NumericDocValuesField(PRICE_LUCENE_FIELD, price));
    return document;
  }

  private static Document docWithoutPrice(String category, String group) {
    Document document = new Document();
    document.add(new SortedSetDocValuesFacetField(CATEGORY_FIELD, category));
    document.add(new StringField(GROUP_FIELD, group, Field.Store.NO));
    return document;
  }

  private static LuceneFacetContext createFacetContext() {
    SearchIndexDefinition indexDefinition =
        SearchIndexDefinitionBuilder.builder()
            .defaultMetadata()
            .indexFeatureVersion(SearchIndexCapabilities.CURRENT_FEATURE_VERSION)
            .mappings(
                DocumentFieldDefinitionBuilder.builder()
                    .field(
                        CATEGORY_FIELD,
                        FieldDefinitionBuilder.builder()
                            .stringFacet(StringFacetFieldDefinitionBuilder.builder().build())
                            .build())
                    .field(
                        PRICE_FIELD,
                        FieldDefinitionBuilder.builder()
                            .number(
                                NumericFieldDefinitionBuilder.builder()
                                    .representation(NumericFieldOptions.Representation.INT64)
                                    .buildNumberField())
                            .build())
                    .build())
            .build();

    return new LuceneFacetContext(
        indexDefinition.createFieldDefinitionResolver(IndexFormatVersion.CURRENT),
        indexDefinition.getIndexCapabilities(IndexFormatVersion.CURRENT));
  }
}
