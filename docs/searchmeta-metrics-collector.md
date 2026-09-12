# `metrics` collector for `$searchMeta`

This fork adds a `metrics` collector to mongot so that `$searchMeta` can compute
**avg / sum / min / max** over a numeric field of every document matched by a search
query, entirely inside mongot. It is a sibling of the existing `facet` collector.

```js
db.detections.aggregate([{ $searchMeta: {
  index: "DetectionAtlasSearch",
  metrics: {
    operator: { compound: {
      filter:  [{ equals: { path: "Detection.DetectionType", value: 0 } }],
      mustNot: [{ equals: { path: "IsDuplicate", value: true } }]
    } },
    metrics: {
      avgSpeed: { type: "avg", path: "Detection.VehicleSpeed" },
      maxSpeed: { type: "max", path: "Detection.VehicleSpeed" }
    }
  }
}}])
// => { count: { lowerBound: 128431 }, metrics: { avgSpeed: 41.7, maxSpeed: 132.0 } }
```

## 1. Why

### The problem

Our copilot feature generates search queries with an LLM. A typical request such as
"average vehicle speed for detection type 0" was executed as

```
$search (filter) -> mongod $_internalSearchIdLookup -> $group { $avg }
```

For every matching document mongot returns an `_id`, and mongod then performs a random
`_id` lookup in WiredTiger to fetch the value before `$group` can average it. On large
match sets this per-document lookup dominates latency; the query effectively streams the
whole result set from mongot to mongod just to add up one number.

Creating a regular MongoDB index per aggregation is not an option: the queries are
generated, so the set of possible filters and fields is open-ended.

### Why solving it in mongot works

Three facts about the existing code base make a mongot-side aggregation cheap and safe:

1. **The numbers are already indexed in a column store.** Dynamic mapping indexes every
   numeric path as a `number` field with `representation: double`
   (`FieldDefinition.DYNAMIC_FIELD_DEFINITION`). For every root document that writes a
   Lucene `LongField` under `FieldName.TypeField.NUMBER_DOUBLE_V2`, which in Lucene 10 is a
   point value **plus `SortedNumericDocValues`** - a per-document columnar store. The double
   is stored via `LuceneDoubleConversionUtils.toMqlSortableLong`, which is exactly
   invertible. No index-definition change and no reindex is required.
2. **mongot already visits every matching document for `$searchMeta`.** Facets use a
   `FacetsCollector` and `count: {type: "total"}` needs an exact hit count. Reading one
   doc value per visited document is a small incremental cost - and it is sequential,
   cache-friendly I/O, not random `_id` reads.
3. **mongot owns the sharded merge.** On a sharded cluster mongod first calls mongot's
   `planShardedSearch`; mongot returns the aggregation pipeline that mongod runs over the
   per-shard partial results (`ShardedSearchPlanner`). mongod treats the `$searchMeta`
   spec and the metadata document as opaque BSON. **No mongod change is needed.**

avg, sum, min and max are *algebraic* aggregates: they can be merged exactly from partial
`(sum, count, min, max)` components. Median, percentiles and distinct counts are not, and
are deliberately out of scope.

### Goals

- `$searchMeta` returns avg/sum/min/max without streaming documents to mongod.
- Works on any dynamically mapped index with no schema work.
- Correct on replica sets, on mongot index partitions, and on sharded clusters.
- Minimal, additive change that stays easy to rebase onto upstream mongot releases.

## 2. Design decisions

| Topic | Decision | Rationale |
|---|---|---|
| Surface | New top-level collector `metrics`, mutually exclusive with `facet` | Combining them needs a composite collector; can be added later without breaking this wire format |
| Scope | Global metrics only (no per-facet-bucket metrics) | Per-bucket requires ordinal-keyed accumulation; separate follow-up |
| Types | `avg`, `sum`, `min`, `max` | All algebraic; min/max are almost free once sum/count exist |
| Result type | Always a BSON double | One type keeps the sharded merge trivial |
| Empty result | `avg`/`min`/`max` -> `null`, `sum` -> `0.0` | Mirrors MQL `$avg`/`$min`/`$max`/`$sum` over zero values |
| Missing / `null` field | Skipped (no doc value exists) | Mirrors `$avg` |
| Arrays | Every element contributes | That is what the doc values hold; MQL `$group` would instead ignore the document - documented divergence |
| NaN | Skipped | Stored as a sentinel long; would decode to garbage |
| `representation: "int64"` | Rejected with an error | It stores `41.7` as `41`; an average over it would be silently wrong |
| `numberFacet`-only or non-number paths | Rejected with an error | They lack the `NUMBER_DOUBLE_V2` doc values |
| Decimal128 | Not supported (unchanged) | mongot does not index Decimal128 at all (documented Atlas limitation); use a view converting to double |
| Summation | Neumaier-compensated | Results depend as little as possible on segment / shard visiting order |
| Feature flag | None | Fork-only feature; avoids threading another flag through query parsing |

### Known divergences from `$search` + `$group`

- **Staleness.** Values come from the search index, which is eventually consistent with
  the collection. `$group` reads live documents.
- **Arrays.** See above.
- **Floating point.** Summation order differs from mongod's; expect equality up to
  rounding, not bit-identical output. Do not write tests that compare the two exactly.
- **Large integers.** Everything is accumulated as double; int64 values above 2^53 lose
  precision (`$sum` in mongod keeps them exact).

## 3. Wire formats

### Query (inside `$searchMeta` / `$search`)

```json
{
  "metrics": {
    "operator": { "...": "optional; defaults to all documents" },
    "metrics": {
      "<name>": { "type": "avg | sum | min | max", "path": "a.b.c" }
    }
  }
}
```

`vectorSearch` is not allowed as the inner operator (same rule as facets).

### Result (`$$SEARCH_META`)

```json
{ "count": { "lowerBound": 128431 }, "metrics": { "<name>": 41.7 } }
```

### Intermediate documents (per shard / per index partition)

Each metric is decomposed into components so partial results merge exactly. `sum` and
`count` are always emitted; `min`/`max` only when at least one value was seen, so an empty
shard cannot distort the merged extremes.

```
{ type: "count",  count: <long> }                                   // existing
{ type: "metric", tag: "<name>", bucket: "sum",   value: <double> }
{ type: "metric", tag: "<name>", bucket: "count", count: <long> }
{ type: "metric", tag: "<name>", bucket: "min",   value: <double> }
{ type: "metric", tag: "<name>", bucket: "max",   value: <double> }
```

`IntermediateMetricBucket` is the single definition of these field names, used by both the
producer and the sharded planner.

### Sharded merge pipeline (generated by `ShardedSearchPlanner`)

Only differs from the facet/operator pipelines when the collector is `metrics`:

1. `$group` by `{type, tag, bucket}` with `value: {$sum: "$count"}` (existing) plus
   `metricSum: {$sum: "$value"}`, `metricMin: {$min: "$value"}`, `metricMax: {$max: "$value"}`.
2. `$facet` with the existing `count` sub-pipeline plus one sub-pipeline per needed
   `(metric, component)`, keyed `<name>_<component>`.
3. `$replaceWith` producing `count` (existing) and `metrics`:
   - `sum` -> `{$first: "$<n>_sum.metricSum"}`
   - `min`/`max` -> `{$first: "$<n>_min.metricMin"}` / `{$first: "$<n>_max.metricMax"}`
   - `avg` -> `{$cond: {if: {$gt: [count, 0]}, then: {$divide: [sum, count]}, else: null}}`

## 4. How a request flows

```
$searchMeta { metrics: ... }
  |
  v
Collector.atMostOneFromBson  -> MetricsCollector { operator, Map<name, MetricDefinition> }
  |
  v
LuceneSearchIndexReader.collectorQuery  (case MetricsCollector)
  |-- LuceneFacetContext.getMetricLuceneFields   validate + resolve name -> "$type:doubleV2/<path>"
  |-- LuceneSearchManagerFactory.newMetricsCollectorManager
  |-- LuceneMetricsCollectorSearchManager.initialSearch
  |     MultiCollectorManager( LuceneMetricsCollectorManager , top-docs collector )
  |       per leaf: SortedNumericDocValues.advanceExact(doc) -> decode -> NumericMetricAccumulator.add
  |       reduce(): merge one accumulator per Lucene field across slices
  |-- re-key accumulators from Lucene field -> metric name
  v
single node:   LuceneMetaResultsBuilder.buildMetricsMetaResults -> MetaResults{count, metrics}
partitions:    LuceneMetricsCollectorMetaBatchProducer x N -> MetricsMergingBatchProducer
shards:        LuceneMetricsCollectorMetaBatchProducer -> intermediate docs -> mongod runs planner pipeline
```

## 5. Code map

New files

| File | Role |
|---|---|
| `index/query/collectors/MetricsCollector.java` | Query model: `{operator, metrics}` |
| `index/query/collectors/MetricDefinition.java` | `{type, path}` |
| `index/IntermediateMetricBucket.java` | Intermediate wire format (single source of truth) |
| `index/lucene/NumericMetricAccumulator.java` | sum / count / min / max with compensated summation; `resultFor(type)` |
| `index/lucene/LuceneMetricsCollectorManager.java` | Lucene `CollectorManager` reading doc values; dedupes fields; concurrent-segment safe |
| `index/lucene/LuceneMetricsCollectorSearchManager.java` | Runs the search (exact hit count) alongside top docs |
| `index/lucene/LuceneMetricsCollectorMetaBatchProducer.java` | Emits intermediate documents |
| `index/lucene/MetricsMergingBatchProducer.java` | Merges index partitions; drains to `MetaResults` |
| `testing/.../MetricsCollectorBuilder.java`, `MetricDefinitionBuilder.java` | Test builders |

Modified files

| File | Change |
|---|---|
| `Collector.java` | `permits MetricsCollector`, `Type.METRICS`, parse/encode; `atMostOneOf(facet, metrics)` |
| `Operator.java` | `parseForMetricsCollector` (rejects `vectorSearch`) |
| `MetaResults.java` | Optional `metrics` document (parsed whole so `null` values survive) |
| `LuceneFacetContext.java` | `getMetricLuceneFields` validation / resolution |
| `LuceneMetaResultsBuilder.java` | `buildMetricsMetaResults`; exhaustive switches |
| `LuceneSearchManagerFactory.java` | `newMetricsCollectorManager` |
| `LuceneSearchIndexReader.java` | `case MetricsCollector` in `collectorQuery` / `intermediateCollectorQuery`; `runMetricsSearch` |
| `MultiLuceneSearchIndexReader.java` | Routes metrics producers to `MetricsMergingBatchProducer` |
| `LuceneFacet*MetaBatchProducerFactory.java` | Exhaustive switch (unreachable for metrics) |
| `ShardedSearchPlanner.java` | Metrics merge pipeline |
| `QueryMetricsRecorder.java` | Collector-type counters |
| `IndexMetrics` fixtures (`src/test/unit/resources/**`) | `collectorTypeCount` gains `metrics` |
| `BUILD` files | New sources registered |

`Collector` is a sealed interface, so adding `MetricsCollector` made the compiler point at
every `switch` that needed a new case - that is how the touched-file list above was found.

## 6. Validation errors a user can hit

| Situation | Error |
|---|---|
| Path not indexed as `number` (static mapping without it, string field, ...) | `Cannot compute metric "avg" on field "x" because it was not indexed as a "number" field.` |
| `number` field with `representation: "int64"` | `... indexed with representation "int64"; metrics require representation "double".` |
| Metric on an `embeddedDocuments` path on an old index format | `This index does not support metrics over numbers in embeddedDocuments. ...` |
| `vectorSearch` as the inner operator | `Metrics are not supported with the 'vectorSearch' operator.` |
| Both `facet` and `metrics` in one query | parser rejects (at most one collector) |

## 7. Tests and how to debug them

Bottom-up, one test per layer (all runnable from IntelliJ via the Bazel plugin gutter icon):

| Layer | Test | Bazel target |
|---|---|---|
| Query parsing | `MetricsCollectorTest` (fixtures: `resources/index/query/collectors/metrics.json`), `CollectorTest` | `//src/test/unit/java/com/xgen/mongot/index/query/collectors:MetricsCollectorTest` |
| Result shape | `MetaResultsTest` (`withMetrics`, incl. null) | `//src/test/unit/java/com/xgen/mongot/index:MetaResultsTest` |
| Doc-value collection | `LuceneMetricsCollectorSearchManagerTest` (missing field, NaN, arrays, multi-segment, duplicate-field regression) | `//src/test/unit/java/com/xgen/mongot/index/lucene:LuceneMetricsCollectorSearchManagerTest` |
| Partition merge | `MetricsMergingBatchProducerTest` | `//src/test/unit/java/com/xgen/mongot/index/lucene:MetricsMergingBatchProducerTest` |
| Sharded pipeline | `ShardedSearchPlannerTest.testMetrics` | `//src/test/unit/java/com/xgen/mongot/server/command/search:ShardedSearchPlannerTest` |
| End to end | `LuceneSearchIndexReaderTest.testMetricsCollector*` | `//src/test/unit/java/com/xgen/mongot/index/lucene:LuceneSearchIndexReaderTest` |

Full verification used for this change:

```bash
make build   # compiles the whole tree incl. errorprone
bazel test //src/test/unit/java/com/xgen/mongot/index/query/collectors/... \
  //src/test/unit/java/com/xgen/mongot/server/command/search/... \
  //src/test/unit/java/com/xgen/mongot/index:MetaResultsTest \
  //src/test/unit/java/com/xgen/mongot/index:IndexMetricsTest \
  //src/test/unit/java/com/xgen/mongot/index/lucene:LuceneMetricsCollectorSearchManagerTest \
  //src/test/unit/java/com/xgen/mongot/index/lucene:MetricsMergingBatchProducerTest \
  //src/test/unit/java/com/xgen/mongot/index/lucene:LuceneSearchIndexReaderTest \
  //src/test/unit/java/com/xgen/mongot/index/lucene:MultiLuceneSearchIndexReaderTest \
  //src/main/java/com/xgen/mongot/index:index-checkstyle \
  //src/main/java/com/xgen/mongot/index/lucene:lucene-checkstyle \
  //src/main/java/com/xgen/mongot/index/query/collectors:collectors-checkstyle \
  //src/main/java/com/xgen/mongot/server/command/search:search-checkstyle
```

Manual end-to-end check against a running deployment (`make build.deploy.community` +
`community-quick-start/`): run the `$searchMeta` query from the top of this page and the
equivalent `$search` + `$group` pipeline; expect equality up to floating-point rounding.

## 8. Limitations and possible follow-ups

- **Per-facet-bucket metrics** ("avg speed per detection type") - the most valuable next
  step; needs accumulation keyed by facet ordinal inside the facet collector.
- **`facet` and `metrics` in one query** - requires a composite collector.
- **`stddev`** is algebraic too (`sum`, `sumSq`, `count`) and would fit the same
  decomposition. Median / percentiles / distinct count do not.
- **Doc-visit cap** - a broad filter visits every matching document, like
  `count: {type: "total"}` today. Consider a limit if LLM-generated queries become a
  resource concern.
- **Zero-code mitigation for other aggregations**: configuring `storedSource` for the
  needed fields and querying `$search` with `returnStoredSource: true` removes the
  per-document `_id` lookup for *any* `$group`, at the cost of still streaming the
  (small) documents.

## 9. Maintaining the fork

This is a change on top of upstream mongot. On each rebase:

- Every `switch` over `Collector` upstream adds must gain a `case MetricsCollector`
  (the compiler enforces it).
- Keep `IntermediateMetricBucket` field names in sync with `ShardedSearchPlanner`.
- Fixtures enumerating `collectorTypeCount` must list `metrics`.
- Use the google-java-format style (continuation indent 8); IntelliJ's default Java
  formatter and the Bazel plugin's `mod tidy` can produce unrelated diffs
  (`java.MODULE.bazel`, indentation) - review `git status` before committing.
