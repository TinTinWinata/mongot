package com.xgen.mongot.index.lucene;

import static com.xgen.mongot.util.Check.checkState;

import com.google.common.collect.ImmutableList;
import com.xgen.mongot.index.definition.DateFacetFieldDefinition;
import com.xgen.mongot.index.definition.DateFieldDefinition;
import com.xgen.mongot.index.definition.DatetimeFieldDefinition;
import com.xgen.mongot.index.definition.FacetableStringFieldDefinition;
import com.xgen.mongot.index.definition.FieldDefinition;
import com.xgen.mongot.index.definition.FieldTypeDefinition;
import com.xgen.mongot.index.definition.NumberFacetFieldDefinition;
import com.xgen.mongot.index.definition.NumberFieldDefinition;
import com.xgen.mongot.index.definition.NumericFieldDefinition;
import com.xgen.mongot.index.definition.NumericFieldOptions;
import com.xgen.mongot.index.definition.SearchFieldDefinitionResolver;
import com.xgen.mongot.index.definition.SearchIndexCapabilities;
import com.xgen.mongot.index.lucene.field.FieldName;
import com.xgen.mongot.index.lucene.util.LuceneDoubleConversionUtils;
import com.xgen.mongot.index.query.InvalidQueryException;
import com.xgen.mongot.index.query.ReturnScope;
import com.xgen.mongot.index.query.collectors.FacetCollector;
import com.xgen.mongot.index.query.collectors.FacetDefinition;
import com.xgen.mongot.index.query.collectors.MetricDefinition;
import com.xgen.mongot.util.Check;
import com.xgen.mongot.util.CheckedStream;
import com.xgen.mongot.util.FieldPath;
import java.util.List;
import java.util.Optional;
import java.util.function.LongToDoubleFunction;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.range.LongRange;
import org.bson.BsonNumber;
import org.bson.BsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LuceneFacetContext {
  private static final Logger logger = LoggerFactory.getLogger(LuceneFacetContext.class);

  private final SearchFieldDefinitionResolver fieldDefinitionResolver;
  private final SearchIndexCapabilities searchIndexCapabilities;
  private static final ImmutableList<FieldName.TypeField> v2Types =
      ImmutableList.of(FieldName.TypeField.NUMBER_INT64_V2, FieldName.TypeField.NUMBER_DOUBLE_V2);
  public static final FacetsConfig FACETS_CONFIG = new FacetsConfig();

  LuceneFacetContext(
      SearchFieldDefinitionResolver fieldDefinitionResolver,
      SearchIndexCapabilities searchIndexCapabilities) {
    this.fieldDefinitionResolver = fieldDefinitionResolver;
    this.searchIndexCapabilities = searchIndexCapabilities;
  }

  void validateQuery(FacetCollector collector, Optional<ReturnScope> returnScope)
      throws InvalidQueryException {
    CheckedStream.from(collector.facetDefinitions().values())
        .forEachChecked(s -> this.validateDefinition(s, returnScope.map(ReturnScope::path)));

    if (collector.drillSidewaysInfo().isPresent() && hasMetrics(collector)) {
      // Drill sideways runs a separate collector per facet, so the matching documents a metric
      // would have to read are not the ones the facet counts were produced from. Rejecting is
      // better than silently returning metrics computed over the wrong document set.
      throw new InvalidQueryException(
          "Facet metrics are not supported alongside 'doesNotAffect'. Remove 'doesNotAffect' from"
              + " the query, or remove 'metrics' from the facet definitions.");
    }
  }

  private static boolean hasMetrics(FacetCollector collector) {
    return collector.facetDefinitions().values().stream()
        .anyMatch(
            definition ->
                definition instanceof FacetDefinition.StringFacetDefinition stringFacetDefinition
                    && !stringFacetDefinition.metrics().isEmpty());
  }

  /**
   * Returns a field definition that can be faceted on, either {@link
   * com.xgen.mongot.index.definition.TokenFieldDefinition} or {@link
   * com.xgen.mongot.index.definition.StringFacetFieldDefinition}. Defaults to StringFacet when both
   * are present. The ability to facet over `token` was introduced later, so users that have both
   * fields defined will expect `StringFacet` to be used for faceting.
   *
   * @param stringFacetDefinition the stringFacet definition from user's query
   * @return FacetableStringFieldDefinition from the Lucene index definition
   */
  FacetableStringFieldDefinition getStringFacetFieldDefinition(
      FacetDefinition.StringFacetDefinition stringFacetDefinition, Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    FieldPath path = FieldPath.parse(stringFacetDefinition.path());
    var def = this.fieldDefinitionResolver.getFieldDefinition(path, returnScope);

    Optional<FacetableStringFieldDefinition> stringFacetFieldDef =
        def.flatMap(FieldDefinition::stringFacetFieldDefinition);
    Optional<FacetableStringFieldDefinition> tokenFieldDef =
        def.flatMap(FieldDefinition::tokenFieldDefinition);

    if (returnScope.isPresent()) {
      // faceting using stringFacet field not supported in embedded documents
      if (tokenFieldDef.isEmpty()) {
        throwPathNotIndexedWithNumberOrDateFieldEmbedded(
            stringFacetDefinition, returnScope.get(), FieldTypeDefinition.Type.TOKEN);
      }
      return tokenFieldDef.get();
    }

    return stringFacetFieldDef
        .or(() -> tokenFieldDef)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    path + " is neither a stringFacet nor token indexed for string faceting"));
  }

  String getBoundaryFacetPath(
      FacetDefinition.BoundaryFacetDefinition<? extends BsonValue> facetDefinition,
      Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    FieldPath fieldPath = FieldPath.parse(facetDefinition.path());
    FieldName.TypeField type = getFacetFieldType(facetDefinition, returnScope);
    return type.getLuceneFieldName(fieldPath, returnScope);
  }

  FieldName.TypeField getFacetFieldType(
      FacetDefinition.BoundaryFacetDefinition<? extends BsonValue> facetDefinition,
      Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    switch (facetDefinition) {
      case FacetDefinition.NumericFacetDefinition numericFacetDefinition:
        return numericTypeField(
            getNumberFacetFieldDefinition(numericFacetDefinition, returnScope), returnScope);
      case FacetDefinition.DateFacetDefinition dateFacetDefinition:
        DatetimeFieldDefinition dateTypeDef =
            dateFacetFieldDefinition(dateFacetDefinition, returnScope);
        return switch (dateTypeDef) {
          case DateFacetFieldDefinition dateFacetFieldDefinition -> FieldName.TypeField.DATE_FACET;
          case DateFieldDefinition dateFieldDefinition -> FieldName.TypeField.DATE_V2;
        };
    }
  }

  /**
   * Maps a numeric field definition onto the lucene field that carries its doc values. This is the
   * same field numeric faceting reads, so metrics and facets stay consistent with one another.
   */
  private FieldName.TypeField numericTypeField(
      NumericFieldDefinition numericTypeDef, Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    if (numericTypeDef.options().representation() == NumericFieldOptions.Representation.DOUBLE) {
      return switch (numericTypeDef) {
        case NumberFacetFieldDefinition numberFacetFieldDefinition ->
            FieldName.TypeField.NUMBER_DOUBLE_FACET;
        case NumberFieldDefinition numberFieldDefinition ->
            FieldName.TypeField.NUMBER_DOUBLE_V2;
      };
    }

    return switch (numericTypeDef) {
      case NumberFacetFieldDefinition numberFacetFieldDefinition -> {
        if (returnScope.isPresent()) {
          throwEmbeddedNumberDateV2NotSupported();
        }
        yield FieldName.TypeField.NUMBER_INT64_FACET;
      }
      case NumberFieldDefinition numberFieldDefinition -> FieldName.TypeField.NUMBER_INT64_V2;
    };
  }

  /**
   * The lucene field a metric reads, together with the decoder that turns the indexed long back
   * into the number it was derived from. The encoding depends on the representation the field was
   * indexed with, so it has to be resolved from the index definition rather than assumed.
   */
  record NumericMetricField(String luceneFieldName, LongToDoubleFunction decoder) {}

  /** Resolves the doc values field and decoder for a metric over the given numeric path. */
  NumericMetricField getNumericMetricField(String path, Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    FieldPath fieldPath = FieldPath.parse(path);
    NumericFieldDefinition numericTypeDef = getNumberFieldDefinitionForPath(path, returnScope);
    FieldName.TypeField typeField = numericTypeField(numericTypeDef, returnScope);

    LongToDoubleFunction decoder =
        switch (typeField) {
          case NUMBER_DOUBLE_V2 -> LuceneDoubleConversionUtils::fromMqlSortableLong;
          case NUMBER_DOUBLE_FACET -> LuceneDoubleConversionUtils::fromLong;
          default -> value -> (double) value;
        };

    return new NumericMetricField(
        typeField.getLuceneFieldName(fieldPath, returnScope), decoder);
  }

  /** Validates that every metric on a string facet reads a path indexed for numeric faceting. */
  void validateMetrics(
      FacetDefinition.StringFacetDefinition facetDefinition, Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    for (MetricDefinition metric : facetDefinition.metrics().values()) {
      getNumberFieldDefinitionForPath(metric.path(), returnScope);
    }
  }

  LongRange[] getRanges(
      FacetDefinition.BoundaryFacetDefinition<? extends BsonValue> boundaryFacetDefinition,
      Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    List<? extends BsonValue> boundaries = boundaryFacetDefinition.boundaries();
    FieldName.TypeField typeField = getFacetFieldType(boundaryFacetDefinition, returnScope);
    checkState(boundaries.size() > 1, "facet boundaries must have at least 2 boundaries.");
    LongRange[] result = new LongRange[boundaries.size() - 1];
    for (int i = 1; i < boundaries.size(); i++) {
      result[i - 1] =
          new LongRange(
              boundaries.get(i - 1).toString(),
              getRangeValue(boundaries.get(i - 1), boundaryFacetDefinition, typeField, returnScope),
              true,
              getRangeValue(boundaries.get(i), boundaryFacetDefinition, typeField, returnScope),
              isOverflowingDouble(boundaries.get(i), boundaryFacetDefinition, returnScope));
    }

    return result;
  }

  private long getRangeValue(
      BsonValue value,
      FacetDefinition facetDefinition,
      FieldName.TypeField typeField,
      Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    return switch (value.getBsonType()) {
      case DATE_TIME -> value.asDateTime().getValue();
      case INT32, INT64, DOUBLE ->
          getNumericRangeValue(
              value.asNumber(),
              getNumberFacetFieldDefinition(
                      (FacetDefinition.NumericFacetDefinition) facetDefinition, returnScope)
                  .options()
                  .representation(),
              typeField);
      default -> {
        logger.error("Unexpected facet type for range value: {}", facetDefinition.getType());
        yield Check.unreachable("Unexpected facet type for range value");
      }
    };
  }

  /** Returns the numeric range value based on representation */
  private long getNumericRangeValue(
      BsonNumber number,
      NumericFieldOptions.Representation representation,
      FieldName.TypeField typeField) {
    return switch (representation) {
      case DOUBLE -> getDoubleNumericRangeValue(number, typeField);
      case INT64 -> getInt64NumericRangeValue(number);
    };
  }

  /** Returns numeric range value for a given number depending on the typeField */
  private long getDoubleNumericRangeValue(BsonNumber number, FieldName.TypeField typeField) {
    return switch (number.getBsonType()) {
      case INT32, INT64 ->
          v2Types.contains(typeField)
              ? LuceneDoubleConversionUtils.toMqlIndexedLong(number.longValue())
              : LuceneDoubleConversionUtils.toIndexedLong(number.longValue());
      case DOUBLE ->
          v2Types.contains(typeField)
              ? LuceneDoubleConversionUtils.toMqlSortableLong(number.asDouble().getValue())
              : LuceneDoubleConversionUtils.toLong(number.asDouble().getValue());
      default -> {
        logger.error("Unexpected BsonType for double numeric range: {}", number.getBsonType());
        yield Check.unreachable("Unexpected BsonType for double numeric range");
      }
    };
  }

  private long getInt64NumericRangeValue(BsonNumber number) {
    return switch (number.getBsonType()) {
      case INT32 -> number.asInt32().longValue();
      case INT64 -> number.asInt64().getValue();
      case DOUBLE ->
          // This conversion does not overflow, but will cap large doubles
          // (see section on int64 representation in index.md for more details)
          (long) number.asDouble().getValue();
      default -> {
        logger.error("Unexpected BsonType for int64 numeric range: {}", number.getBsonType());
        yield Check.unreachable("Unexpected BsonType for int64 numeric range");
      }
    };
  }

  private boolean isOverflowingDouble(
      BsonValue value,
      FacetDefinition.BoundaryFacetDefinition<? extends BsonValue> facetDefinition,
      Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    return facetDefinition.getType() == FacetDefinition.Type.NUMBER
        && getNumberFacetFieldDefinition(
                    (FacetDefinition.NumericFacetDefinition) facetDefinition, returnScope)
                .options()
                .representation()
            == NumericFieldOptions.Representation.INT64
        && value.isDouble()
        && value.asDouble().getValue() > Long.MAX_VALUE;
  }

  private void validateDefinition(FacetDefinition facetDefinition, Optional<FieldPath> embeddedPath)
      throws InvalidQueryException {
    Optional<FieldDefinition> maybeFieldDef =
        this.fieldDefinitionResolver.getFieldDefinition(
            FieldPath.parse(facetDefinition.path()), embeddedPath);
    switch (facetDefinition) {
      case FacetDefinition.StringFacetDefinition stringFacetDefinition:
        if (maybeFieldDef.flatMap(FieldDefinition::stringFacetFieldDefinition).isEmpty()
            && maybeFieldDef.flatMap(FieldDefinition::tokenFieldDefinition).isEmpty()) {
          throwFieldNotIndexed(
              facetDefinition.path(), "token", embeddedPath.map(FieldPath::toString));
        }
        validateMetrics(stringFacetDefinition, embeddedPath);
        break;
      case FacetDefinition.NumericFacetDefinition numericFacetDefinition:
        if (embeddedPath.isPresent()
            && !this.searchIndexCapabilities.supportsEmbeddedNumericAndDateV2()) {
          throwEmbeddedNumberDateV2NotSupported();
        }

        if (maybeFieldDef.flatMap(FieldDefinition::numberFacetFieldDefinition).isEmpty()
            && maybeFieldDef.flatMap(FieldDefinition::numberFieldDefinition).isEmpty()) {
          throwFieldNotIndexed(
              facetDefinition.path(), "number", embeddedPath.map(FieldPath::toString));
        }
        assertNoDuplicateResolvedBoundaries(numericFacetDefinition, embeddedPath);
        break;
      case FacetDefinition.DateFacetDefinition dateFacetDefinition:
        if (embeddedPath.isPresent()
            && !this.searchIndexCapabilities.supportsEmbeddedNumericAndDateV2()) {
          throwEmbeddedNumberDateV2NotSupported();
        }

        if (maybeFieldDef.flatMap(FieldDefinition::dateFacetFieldDefinition).isEmpty()
            && maybeFieldDef.flatMap(FieldDefinition::dateFieldDefinition).isEmpty()) {
          throwFieldNotIndexed(
              facetDefinition.path(), "date", embeddedPath.map(FieldPath::toString));
        }
        break;
    }
  }

  private void assertNoDuplicateResolvedBoundaries(
      FacetDefinition.NumericFacetDefinition facetDefinition, Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    NumericFieldOptions.Representation numericRepresentation =
        getNumberFacetFieldDefinition(facetDefinition, returnScope).options().representation();
    FieldName.TypeField typeField = getFacetFieldType(facetDefinition, returnScope);
    List<Long> resolvedBoundaries =
        facetDefinition.boundaries().stream()
            .map((val) -> getNumericRangeValue(val, numericRepresentation, typeField))
            .distinct()
            .toList();
    if (resolvedBoundaries.size() < facetDefinition.boundaries().size()) {
      throw new InvalidQueryException(
          "Cannot use multiple consecutive boundaries that resolve to the same number.  This can "
              + "happen when resolving very large or very small numbers, depending on the chosen "
              + "representation for the field.");
    }
  }

  /**
   * Returns the field definition to use for number faceting.
   *
   * <p>If a `numberFacet` definition is present for this field, and no `number` definition is
   * present, this will return the `numberFacet` field definition.
   *
   * <p>If both `number` and `numberFacet` definitions are present, there are two options.
   *
   * <p>1) If both fields have the same options: This will return the `number` field definition, as
   * we will only store one lucene field under the `number` field name.
   *
   * <p>2) If the fields have different options: This will return the `numberFacet` field
   * definition.
   */
  private NumericFieldDefinition getNumberFacetFieldDefinition(
      FacetDefinition.NumericFacetDefinition facetDefinition, Optional<FieldPath> returnScope)
      throws InvalidQueryException {
    return getNumberFieldDefinitionForPath(facetDefinition.path(), returnScope);
  }

  /**
   * Resolves the numeric field definition to read for a path, following the same {@code
   * number}/{@code numberFacet} precedence as numeric faceting so that a metric and a facet over
   * the same path always read the same lucene field.
   */
  private NumericFieldDefinition getNumberFieldDefinitionForPath(
      String path, Optional<FieldPath> returnScope) throws InvalidQueryException {
    FieldPath fieldPath = FieldPath.parse(path);

    Optional<NumberFacetFieldDefinition> numberFacetFieldDef =
        returnScope.isPresent()
            ? Optional.empty()
            : this.fieldDefinitionResolver
                .getFieldDefinition(fieldPath, returnScope)
                .flatMap(FieldDefinition::numberFacetFieldDefinition);

    Optional<NumberFieldDefinition> numberFieldDef =
        this.fieldDefinitionResolver
            .getFieldDefinition(fieldPath, returnScope)
            .flatMap(FieldDefinition::numberFieldDefinition);

    if (returnScope.isPresent()) {
      if (numberFieldDef.isEmpty()) {
        throwPathNotIndexedWithNumberFieldEmbedded(path, returnScope.get());
      }
      validateIfvForEmbeddedNumericDateFacets();
      return numberFieldDef.get();
    }

    if (numberFacetFieldDef.isPresent()
        && (numberFieldDef.isEmpty()
            || !numberFieldDef.get().hasSameOptionsAs(numberFacetFieldDef.get()))) {
      return numberFacetFieldDef.get();
    }

    if (numberFacetFieldDef.isEmpty() && numberFieldDef.isEmpty()) {
      throwFieldNotIndexed(path, "number", Optional.empty());
    }

    return numberFieldDef.orElseThrow(
        () -> new IllegalStateException("Numeric field is not indexed for faceting"));
  }

  /**
   * Returns the field definition to use for number faceting. If only one of `date` or `dateFacet`
   * is defined in the index, this will return whichever is present. If both `date` and `dateFacet`
   * definitions are present, this will return the `date` definition. Because `date` and `dateFacet`
   * have no options, if both are defined, we always only store a `date` field.
   */
  private DatetimeFieldDefinition dateFacetFieldDefinition(
      FacetDefinition.DateFacetDefinition facetDefinition, Optional<FieldPath> returnScope)
      throws InvalidQueryException {

    Optional<DateFacetFieldDefinition> dateFacetFieldDef =
        returnScope.isPresent()
            ? Optional.empty()
            : this.fieldDefinitionResolver
                .getFieldDefinition(FieldPath.parse(facetDefinition.path()), Optional.empty())
                .flatMap(FieldDefinition::dateFacetFieldDefinition);

    Optional<DateFieldDefinition> dateFieldDef =
        this.fieldDefinitionResolver
            .getFieldDefinition(FieldPath.parse(facetDefinition.path()), returnScope)
            .flatMap(FieldDefinition::dateFieldDefinition);

    if (returnScope.isPresent()) {
      if (dateFieldDef.isEmpty()) {
        throwPathNotIndexedWithNumberOrDateFieldEmbedded(
            facetDefinition, returnScope.get(), FieldTypeDefinition.Type.DATE);
      }
      validateIfvForEmbeddedNumericDateFacets();
      return dateFieldDef.get();
    }

    if (dateFacetFieldDef.isPresent() && dateFieldDef.isEmpty()) {
      return dateFacetFieldDef.get();
    }

    return dateFieldDef.orElseThrow(
        () -> new IllegalStateException("Date field is not indexed for faceting"));
  }

  private void validateIfvForEmbeddedNumericDateFacets() throws InvalidQueryException {
    if (!this.searchIndexCapabilities.supportsEmbeddedNumericAndDateV2()) {
      throw new InvalidQueryException(
          "This index does not support faceting over dates in embeddedDocuments. "
              + "Upgrade your index in Atlas or perform a no-op index "
              + "definition update to use this feature.");
    }
  }

  private static void throwPathNotIndexedWithNumberOrDateFieldEmbedded(
      FacetDefinition facetDefinition, FieldPath returnScope, FieldTypeDefinition.Type type)
      throws InvalidQueryException {
    // numberFacet/dateFacet datatype not supported in embeddedDocuments, so number/date
    throw new InvalidQueryException(
        String.format(
            "Field '%s' at embeddedDocument path '%s' must be "
                + "indexed as type '%s' to perform %s faceting.",
            facetDefinition.path(),
            returnScope,
            type.toString().toLowerCase(),
            facetDefinition.getType().toString().toLowerCase()));
  }

  private static void throwPathNotIndexedWithNumberFieldEmbedded(String path, FieldPath returnScope)
      throws InvalidQueryException {
    // numberFacet datatype is not supported in embeddedDocuments, so number is required there.
    throw new InvalidQueryException(
        String.format(
            "Field '%s' at embeddedDocument path '%s' must be indexed as type 'number'.",
            path, returnScope));
  }

  private static void throwEmbeddedNumberDateV2NotSupported() throws InvalidQueryException {
    throw new InvalidQueryException(
        "This index does not support faceting over numbers and dates in embeddedDocuments. "
            + "Upgrade or recreate your index to use this feature.");
  }

  private static void throwFieldNotIndexed(
      String path, String fieldType, Optional<String> returnScopePath)
      throws InvalidQueryException {
    String returnScopeSubstring =
        returnScopePath.isPresent()
            ? String.format("at returnScope %s", returnScopePath.get())
            : "";
    throw new InvalidQueryException(
        String.format(
            "Cannot facet on field \"%s\" \"%s\" because it was not indexed as a \"%s\" field.",
            path, returnScopeSubstring, fieldType));
  }
}
