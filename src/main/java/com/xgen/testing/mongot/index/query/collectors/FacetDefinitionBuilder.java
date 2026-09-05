package com.xgen.testing.mongot.index.query.collectors;

import com.xgen.mongot.index.query.collectors.FacetDefinition;
import com.xgen.mongot.index.query.collectors.MetricDefinition;
import com.xgen.mongot.util.Check;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.BsonDateTime;
import org.bson.BsonNumber;
import org.bson.BsonValue;

public abstract class FacetDefinitionBuilder {

  protected Optional<String> path = Optional.empty();

  public FacetDefinitionBuilder path(String path) {
    this.path = Optional.of(path);
    return this;
  }

  public abstract FacetDefinition build();

  public static StringFacetDefinitionBuilder string() {
    return new StringFacetDefinitionBuilder();
  }

  public static NumericFacetDefinitionBuilder numeric() {
    return new NumericFacetDefinitionBuilder();
  }

  public static DateFacetDefinitionBuilder date() {
    return new DateFacetDefinitionBuilder();
  }

  public static class StringFacetDefinitionBuilder extends FacetDefinitionBuilder {

    private Optional<Integer> numBuckets = Optional.empty();
    private Map<String, MetricDefinition> metrics = Map.of();

    public StringFacetDefinitionBuilder numBuckets(Integer numBuckets) {
      this.numBuckets = Optional.of(numBuckets);
      return this;
    }

    public StringFacetDefinitionBuilder metrics(Map<String, MetricDefinition> metrics) {
      this.metrics = metrics;
      return this;
    }

    public StringFacetDefinitionBuilder metric(
        String name, MetricDefinition.Type type, String path) {
      Map<String, MetricDefinition> updated = new HashMap<>(this.metrics);
      updated.put(name, new MetricDefinition(type, path));
      this.metrics = updated;
      return this;
    }

    @Override
    public FacetDefinition.StringFacetDefinition build() {
      Check.isPresent(this.path, "path");
      return new FacetDefinition.StringFacetDefinition(
          this.path.get(), this.numBuckets.orElse(10), this.metrics);
    }
  }

  public abstract static class BoundaryFacetDefinitionBuilder<T extends BsonValue>
      extends FacetDefinitionBuilder {

    protected Optional<String> defaultBucketName = Optional.empty();
    protected Optional<List<T>> boundaries = Optional.empty();

    public BoundaryFacetDefinitionBuilder<T> defaultBucketName(String defaultBucketName) {
      this.defaultBucketName = Optional.of(defaultBucketName);
      return this;
    }

    public BoundaryFacetDefinitionBuilder<T> boundaries(List<T> boundaries) {
      this.boundaries = Optional.of(boundaries);
      return this;
    }

    public void checkRequiredFields() {
      Check.isPresent(this.path, "path");
      Check.isPresent(this.boundaries, "boundaries");
    }
  }

  public static class NumericFacetDefinitionBuilder
      extends BoundaryFacetDefinitionBuilder<BsonNumber> {

    @Override
    public FacetDefinition build() {
      this.checkRequiredFields();
      return new FacetDefinition.NumericFacetDefinition(
          this.path.get(), this.defaultBucketName, this.boundaries.get());
    }
  }

  public static class DateFacetDefinitionBuilder
      extends BoundaryFacetDefinitionBuilder<BsonDateTime> {

    @Override
    public FacetDefinition build() {
      this.checkRequiredFields();
      return new FacetDefinition.DateFacetDefinition(
          this.path.get(), this.defaultBucketName, this.boundaries.get());
    }
  }
}
