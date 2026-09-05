package com.xgen.mongot.server.command.search;

import com.xgen.mongot.index.query.OperatorQuery;
import com.xgen.mongot.index.query.SearchQuery;
import com.xgen.mongot.index.query.sort.UserFieldSortOptions;
import com.xgen.mongot.server.command.search.definition.PlanShardedSearchCommandResponseDefinition;
import com.xgen.mongot.server.command.search.definition.request.PlanShardedSearchCommandDefinition;
import com.xgen.testing.TestUtils;
import com.xgen.testing.mongot.index.query.OperatorQueryBuilder;
import com.xgen.testing.mongot.index.query.operators.OperatorBuilder;
import com.xgen.testing.mongot.index.query.sort.SortFieldBuilder;
import com.xgen.testing.mongot.index.query.sort.SortSpecBuilder;
import com.xgen.testing.mongot.server.command.search.definition.ShardedSearchPlanBuilder;
import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ShardedSearchPlannerTest {

  private static BsonDocument getOperatorQuery() {
    return new BsonDocument()
        .append(
            "text",
            new BsonDocument()
                .append("path", new BsonString("title"))
                .append("query", new BsonString("godfather")));
  }

  private static BsonDocument getOperatorQueryWithCount() {
    return new BsonDocument()
        .append(
            "text",
            new BsonDocument()
                .append("path", new BsonString("title"))
                .append("query", new BsonString("godfather")))
        .append(
            "count",
            new BsonDocument()
                .append("type", new BsonString("total"))
                .append("threshold", new BsonInt32(5000)));
  }

  private static BsonDocument getSimpleStringFacet() {
    return new BsonDocument(
        "directorFacet",
        new BsonDocument()
            .append("type", new BsonString("string"))
            .append("path", new BsonString("director")));
  }

  private static BsonDocument getStringFacetWithNumBuckets() {
    return new BsonDocument(
        "directorFacet",
        new BsonDocument()
            .append("type", new BsonString("string"))
            .append("path", new BsonString("director"))
            .append("numBuckets", new BsonInt32(1000)));
  }

  private static BsonDocument getSimpleNumericFacet() {
    return new BsonDocument(
        "ratingFacet",
        new BsonDocument()
            .append("type", new BsonString("number"))
            .append("path", new BsonString("rating"))
            .append("boundaries", new BsonArray(List.of(new BsonInt32(1), new BsonInt32(2)))));
  }

  private static BsonDocument getSimpleDateFacet() {
    return new BsonDocument(
        "eventFacet",
        new BsonDocument()
            .append("type", new BsonString("date"))
            .append("path", new BsonString("start"))
            .append(
                "boundaries",
                new BsonArray(List.of(new BsonDateTime(2021), new BsonDateTime(2022)))));
  }

  private static BsonDocument getMultipleFacets() {
    return new BsonDocument()
        .append("directorFacet", getSimpleStringFacet().getDocument("directorFacet"))
        .append("ratingFacet", getSimpleNumericFacet().getDocument("ratingFacet"))
        .append("eventFacet", getSimpleDateFacet().getDocument("eventFacet"));
  }

  private static BsonDocument getMultipleStringFacets() {
    return new BsonDocument()
        .append("directorFacet", getSimpleStringFacet().getDocument("directorFacet"))
        .append(
            "genreFacet",
            new BsonDocument()
                .append("type", new BsonString("string"))
                .append("path", new BsonString("genre"))
                .append("numBuckets", new BsonInt32(50)))
        .append(
            "languageFacet",
            new BsonDocument()
                .append("type", new BsonString("string"))
                .append("path", new BsonString("language")));
  }

  private static BsonDocument createFacetQuery(BsonDocument facets) {
    return new BsonDocument()
        .append(
            "facet",
            new BsonDocument().append("operator", getOperatorQuery()).append("facets", facets));
  }

  private static BsonDocument createStringFacetDoc(String facetName, int numBuckets) {
    return new BsonDocument(
        facetName,
        new BsonArray(
            List.of(
                new BsonDocument(
                    "$match",
                    new BsonDocument()
                        .append("_id.type", new BsonDocument("$eq", new BsonString("facet")))
                        .append("_id.tag", new BsonDocument("$eq", new BsonString(facetName)))),
                new BsonDocument(
                    "$sort",
                    new BsonDocument("value", new BsonInt32(-1)).append("_id", new BsonInt32(1))),
                new BsonDocument("$limit", new BsonInt32(numBuckets)))));
  }

  private static BsonDocument createBucketDoc(String facetName) {
    return new BsonDocument(
        "buckets",
        new BsonDocument(
            "$map",
            new BsonDocument()
                .append("input", new BsonString("$" + facetName))
                .append("as", new BsonString("bucket"))
                .append(
                    "in",
                    new BsonDocument()
                        .append("_id", new BsonString("$$bucket._id.bucket"))
                        .append("count", new BsonString("$$bucket.value")))));
  }

  private static BsonDocument createNumericFacetDoc(String facetName) {
    return new BsonDocument(
        facetName,
        new BsonArray(
            List.of(
                new BsonDocument(
                    "$match",
                    new BsonDocument()
                        .append("_id.type", new BsonDocument("$eq", new BsonString("facet")))
                        .append("_id.tag", new BsonDocument("$eq", new BsonString(facetName)))),
                new BsonDocument("$sort", new BsonDocument("_id.bucket", new BsonInt32(1))))));
  }

  private static BsonDocument createDateFacetDoc(String facetName) {
    return new BsonDocument(
        facetName,
        new BsonArray(
            List.of(
                new BsonDocument(
                    "$match",
                    new BsonDocument()
                        .append("_id.type", new BsonDocument("$eq", new BsonString("facet")))
                        .append("_id.tag", new BsonDocument("$eq", new BsonString(facetName)))),
                new BsonDocument(
                    "$addFields",
                    new BsonDocument(
                        "sortField",
                        new BsonDocument(
                            "$convert",
                            new BsonDocument()
                                .append("input", new BsonString("$_id.bucket"))
                                .append("to", new BsonString("long"))
                                .append("onError", new BsonString("$_id.bucket"))))),
                new BsonDocument("$sort", new BsonDocument("sortField", new BsonInt32(1))))));
  }

  private static BsonDocument getStringFacetWithMetrics() {
    return new BsonDocument(
        "vehicleTypeFacet",
        new BsonDocument()
            .append("type", new BsonString("string"))
            .append("path", new BsonString("vehicleType"))
            .append(
                "metrics",
                new BsonDocument()
                    .append(
                        "avgSpeed", new BsonDocument("avg", new BsonString("vehicleSpeed")))));
  }

  /**
   * The merge pipeline has to carry the sum and the count across shards and divide once at the end.
   * Merging per-shard averages is only correct when every shard contributed the same number of
   * documents, so the accumulator state, not the resolved average, is what crosses the boundary.
   */
  @Test
  public void metaPipeline_facetWithMetrics_mergesAccumulatorStateAndDividesOnce()
      throws Exception {
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan plan =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(createFacetQuery(getStringFacetWithMetrics())),
            new PlanShardedSearchCommandDefinition.SearchFeatures(0));

    BsonDocument groupStage = plan.metaPipeline.get(0).getDocument("$group");
    Assert.assertEquals(
        new BsonDocument("$sum", new BsonString("$metrics.avgSpeed.count")),
        groupStage.getDocument("m0_count"));
    Assert.assertEquals(
        new BsonDocument("$sum", new BsonString("$metrics.avgSpeed.sum")),
        groupStage.getDocument("m0_sum"));
    Assert.assertEquals(
        new BsonDocument("$min", new BsonString("$metrics.avgSpeed.min")),
        groupStage.getDocument("m0_min"));
    Assert.assertEquals(
        new BsonDocument("$max", new BsonString("$metrics.avgSpeed.max")),
        groupStage.getDocument("m0_max"));

    BsonDocument bucketProjection =
        plan.metaPipeline
            .get(2)
            .getDocument("$replaceWith")
            .getDocument("facet")
            .getDocument("vehicleTypeFacet")
            .getDocument("buckets")
            .getDocument("$map")
            .getDocument("in");

    BsonArray cond =
        bucketProjection
            .getDocument("metrics")
            .getDocument("avgSpeed")
            .getArray("$cond");

    // Guard on the merged count, divide the merged sum by it, and drop the metric entirely for
    // buckets to which no shard contributed a value.
    Assert.assertEquals(
        new BsonDocument(
            "$gt", new BsonArray(List.of(new BsonString("$$bucket.m0_count"), new BsonInt32(0)))),
        cond.get(0));
    Assert.assertEquals(
        new BsonDocument(
            "$divide",
            new BsonArray(
                List.of(new BsonString("$$bucket.m0_sum"), new BsonString("$$bucket.m0_count")))),
        cond.get(1));
    Assert.assertEquals(new BsonString("$$REMOVE"), cond.get(2));
  }

  @Test
  public void metaPipeline_facetWithoutMetrics_isUnchanged() throws Exception {
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan plan =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(createFacetQuery(getSimpleStringFacet())),
            new PlanShardedSearchCommandDefinition.SearchFeatures(0));

    Assert.assertEquals(
        new BsonDocument("$sum", new BsonString("$count")),
        plan.metaPipeline.get(0).getDocument("$group").getDocument("value"));
    Assert.assertEquals(2, plan.metaPipeline.get(0).getDocument("$group").size());
    Assert.assertFalse(
        plan.metaPipeline
            .get(2)
            .getDocument("$replaceWith")
            .getDocument("facet")
            .getDocument("directorFacet")
            .getDocument("buckets")
            .getDocument("$map")
            .getDocument("in")
            .containsKey("metrics"));
  }

  @Test
  public void testSortSpec() throws Exception {
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(getOperatorQuery()),
            new PlanShardedSearchCommandDefinition.SearchFeatures(0));
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan expected =
        ShardedSearchPlanBuilder.operator()
            .countType("lowerBound")
            .sortSpec(
                PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan.DEFAULT_SORT_SPEC)
            .build();
    Assert.assertEquals(expected, result);
  }

  @Test
  public void testSortInQueryWithSearchFeature() throws ShardedSearchPlannerException {
    OperatorQuery query =
        OperatorQueryBuilder.builder()
            .operator(OperatorBuilder.text().path("title").query("godfather").build())
            .returnStoredSource(false)
            .sort(
                SortSpecBuilder.builder()
                    .sortField(
                        SortFieldBuilder.builder()
                            .path("a")
                            .sortOption(UserFieldSortOptions.DEFAULT_ASC)
                            .build())
                    .sortField(
                        SortFieldBuilder.builder()
                            .path("b")
                            .sortOption(UserFieldSortOptions.DEFAULT_DESC)
                            .build())
                    .buildSort())
            .build();

    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            query, new PlanShardedSearchCommandDefinition.SearchFeatures(1));
    BsonDocument expected =
        new BsonDocument()
            .append("$searchSortValues._0", new BsonInt32(1))
            .append("$searchSortValues._1", new BsonInt32(-1));
    Assert.assertEquals(expected, result.sortSpec);
  }

  @Test
  public void testSortInQueryWithoutSearchFeature() {
    OperatorQuery query =
        OperatorQueryBuilder.builder()
            .operator(OperatorBuilder.text().path("title").query("godfather").build())
            .returnStoredSource(false)
            .sort(
                SortSpecBuilder.builder()
                    .sortField(
                        SortFieldBuilder.builder()
                            .path("a")
                            .sortOption(UserFieldSortOptions.DEFAULT_ASC)
                            .build())
                    .sortField(
                        SortFieldBuilder.builder()
                            .path("b")
                            .sortOption(UserFieldSortOptions.DEFAULT_DESC)
                            .build())
                    .buildSort())
            .build();

    TestUtils.assertThrows(
        "Sharded sort is not supported for this MongoDB deployment",
        ShardedSearchPlannerException.class,
        () ->
            ShardedSearchPlanner.planSearch(
                query, new PlanShardedSearchCommandDefinition.SearchFeatures(0)));
  }

  @Test
  public void testSortBetaInQuery() throws ShardedSearchPlannerException {
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            OperatorQueryBuilder.builder()
                .operator(OperatorBuilder.text().path("title").query("godfather").build())
                .returnStoredSource(false)
                .sortBetaV1(
                    SortSpecBuilder.builder()
                        .sortField(
                            SortFieldBuilder.builder()
                                .path("title")
                                .sortOption(UserFieldSortOptions.DEFAULT_DESC)
                                .build())
                        .buildSortBetaV1())
                .build(),
            new PlanShardedSearchCommandDefinition.SearchFeatures(0));

    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan expected =
        ShardedSearchPlanBuilder.operator()
            .countType("lowerBound")
            .sortSpec(
                PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan.DEFAULT_SORT_SPEC)
            .build();
    Assert.assertEquals(expected, result);
  }

  @Test
  public void testOperator() throws Exception {
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(getOperatorQuery()),
            new PlanShardedSearchCommandDefinition.SearchFeatures(0));
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan expected =
        ShardedSearchPlanBuilder.operator().countType("lowerBound").build();
    Assert.assertEquals(expected, result);
  }

  @Test
  public void testOperatorWithCount() throws Exception {
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(getOperatorQueryWithCount()),
            new PlanShardedSearchCommandDefinition.SearchFeatures(0));
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan expected =
        ShardedSearchPlanBuilder.operator().countType("total").build();
    Assert.assertEquals(expected, result);
  }

  @Test
  public void testStringFacet() throws Exception {
    var query = createFacetQuery(getSimpleStringFacet());
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(query), new PlanShardedSearchCommandDefinition.SearchFeatures(0));

    var facetDocs = List.of(createStringFacetDoc("directorFacet", 10));
    var bucketDoc = new BsonDocument("directorFacet", createBucketDoc("directorFacet"));
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan expected =
        ShardedSearchPlanBuilder.collector()
            .facetDocs(facetDocs)
            .bucketDoc(bucketDoc)
            .countType("lowerBound")
            .build();

    Assert.assertEquals(expected, result);
  }

  @Test
  public void testStringFacetWithNumBuckets() throws Exception {
    var query = createFacetQuery(getStringFacetWithNumBuckets());
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(query), new PlanShardedSearchCommandDefinition.SearchFeatures(0));

    var facetDocs = List.of(createStringFacetDoc("directorFacet", 1000));
    var bucketDoc = new BsonDocument("directorFacet", createBucketDoc("directorFacet"));
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan expected =
        ShardedSearchPlanBuilder.collector()
            .facetDocs(facetDocs)
            .bucketDoc(bucketDoc)
            .countType("lowerBound")
            .build();

    Assert.assertEquals(expected, result);
  }

  @Test
  public void testNumericFacet() throws Exception {
    var query = createFacetQuery(getSimpleNumericFacet());
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(query), new PlanShardedSearchCommandDefinition.SearchFeatures(0));

    var facetDocs = List.of(createNumericFacetDoc("ratingFacet"));
    var bucketDoc = new BsonDocument("ratingFacet", createBucketDoc("ratingFacet"));
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan expected =
        ShardedSearchPlanBuilder.collector()
            .facetDocs(facetDocs)
            .bucketDoc(bucketDoc)
            .countType("lowerBound")
            .build();

    Assert.assertEquals(expected, result);
  }

  @Test
  public void testDateFacet() throws Exception {
    var query = createFacetQuery(getSimpleDateFacet());
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(query), new PlanShardedSearchCommandDefinition.SearchFeatures(0));

    var facetDocs = List.of(createDateFacetDoc("eventFacet"));
    var bucketDoc = new BsonDocument("eventFacet", createBucketDoc("eventFacet"));
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan expected =
        ShardedSearchPlanBuilder.collector()
            .facetDocs(facetDocs)
            .bucketDoc(bucketDoc)
            .countType("lowerBound")
            .build();

    Assert.assertEquals(expected, result);
  }

  @Test
  public void testMultipleFacets() throws Exception {
    var query = createFacetQuery(getMultipleFacets());
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(query), new PlanShardedSearchCommandDefinition.SearchFeatures(0));

    var facetDocs =
        List.of(
            createStringFacetDoc("directorFacet", 10),
            createNumericFacetDoc("ratingFacet"),
            createDateFacetDoc("eventFacet"));
    var bucketDoc =
        new BsonDocument()
            .append("directorFacet", createBucketDoc("directorFacet"))
            .append("ratingFacet", createBucketDoc("ratingFacet"))
            .append("eventFacet", createBucketDoc("eventFacet"));
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan expected =
        ShardedSearchPlanBuilder.collector()
            .facetDocs(facetDocs)
            .bucketDoc(bucketDoc)
            .countType("lowerBound")
            .build();

    Assert.assertEquals(expected, result);
  }

  @Test
  public void testMultipleStringFacets() throws Exception {
    var query = createFacetQuery(getMultipleStringFacets());
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan result =
        ShardedSearchPlanner.planSearch(
            SearchQuery.fromBson(query), new PlanShardedSearchCommandDefinition.SearchFeatures(0));

    var facetDocs =
        List.of(
            createStringFacetDoc("directorFacet", 10),
            createStringFacetDoc("genreFacet", 50),
            createStringFacetDoc("languageFacet", 10));
    var bucketDoc =
        new BsonDocument()
            .append("directorFacet", createBucketDoc("directorFacet"))
            .append("genreFacet", createBucketDoc("genreFacet"))
            .append("languageFacet", createBucketDoc("languageFacet"));
    PlanShardedSearchCommandResponseDefinition.ShardedSearchPlan expected =
        ShardedSearchPlanBuilder.collector()
            .facetDocs(facetDocs)
            .bucketDoc(bucketDoc)
            .countType("lowerBound")
            .build();

    Assert.assertEquals(expected, result);
  }
}
