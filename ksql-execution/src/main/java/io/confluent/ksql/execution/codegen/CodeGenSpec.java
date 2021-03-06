/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"; you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.execution.codegen;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.Immutable;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.function.udf.Kudf;
import io.confluent.ksql.name.FunctionName;
import io.confluent.ksql.schema.ksql.ColumnRef;
import io.confluent.ksql.testing.EffectivelyImmutable;
import io.confluent.ksql.util.KsqlException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Immutable
public final class CodeGenSpec {

  private final ImmutableList<ArgumentSpec> arguments;
  private final ImmutableMap<ColumnRef, String> columnToCodeName;
  private final ImmutableListMultimap<FunctionName, String> functionToCodeName;

  private CodeGenSpec(
      ImmutableList<ArgumentSpec> arguments, ImmutableMap<ColumnRef, String> columnToCodeName,
      ImmutableListMultimap<FunctionName, String> functionToCodeName
  ) {
    this.arguments = arguments;
    this.columnToCodeName = columnToCodeName;
    this.functionToCodeName = functionToCodeName;
  }

  public String[] argumentNames() {
    return arguments.stream().map(ArgumentSpec::name).toArray(String[]::new);
  }

  public Class<?>[] argumentTypes() {
    return arguments.stream().map(ArgumentSpec::type).toArray(Class[]::new);
  }

  public List<ArgumentSpec> arguments() {
    return arguments;
  }

  public String getCodeName(ColumnRef columnRef) {
    return columnToCodeName.get(columnRef);
  }

  public String getUniqueNameForFunction(FunctionName functionName, int index) {
    List<String> names = functionToCodeName.get(functionName);
    if (names.size() <= index) {
      throw new KsqlException("Cannot get name for " + functionName + " " + index + " times");
    }
    return names.get(index);
  }

  public void resolve(GenericRow row, Object[] parameters) {
    for (int paramIdx = 0; paramIdx < arguments.size(); paramIdx++) {
      parameters[paramIdx] = arguments.get(paramIdx).resolve(row);
    }
  }

  static class Builder {

    private final ImmutableList.Builder<ArgumentSpec> argumentBuilder = ImmutableList.builder();
    private final Map<ColumnRef, String> columnRefToName = new HashMap<>();
    private final ImmutableListMultimap.Builder<FunctionName, String> functionNameBuilder =
        ImmutableListMultimap.builder();

    private int argumentCount = 0;

    void addParameter(
        final ColumnRef columnRef,
        final Class<?> type,
        final int colIndex
    ) {
      final String codeName = CodeGenUtil.paramName(argumentCount++);
      columnRefToName.put(columnRef, codeName);
      argumentBuilder.add(new ValueArgumentSpec(codeName, type, colIndex));
    }

    void addFunction(FunctionName functionName, Kudf function) {
      final String codeName = CodeGenUtil.functionName(functionName, argumentCount++);
      functionNameBuilder.put(functionName, codeName);
      argumentBuilder.add(new FunctionArgumentSpec(codeName, function.getClass(), function));
    }

    CodeGenSpec build() {
      return new CodeGenSpec(
          argumentBuilder.build(),
          ImmutableMap.copyOf(columnRefToName),
          functionNameBuilder.build()
      );
    }
  }

  /**
   * Represents either a named reference to a column in a generic row, or a function wrapped in a
   * {@code Kudf}.
   */
  @Immutable
  public interface ArgumentSpec {

    String name();

    Class<?> type();

    Object resolve(GenericRow value);
  }

  @Immutable
  private abstract static class BaseArgumentSpec implements ArgumentSpec {

    private final String name;
    private final Class<?> type;

    BaseArgumentSpec(
        final String name,
        final Class<?> type
    ) {
      this.name = requireNonNull(name, "name");
      this.type = requireNonNull(type, "type");
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Class<?> type() {
      return type;
    }
  }

  @Immutable
  private static final class FunctionArgumentSpec extends BaseArgumentSpec {

    @EffectivelyImmutable
    private final Kudf kudf;

    FunctionArgumentSpec(
        final String name,
        final Class<?> type,
        final Kudf kudf
    ) {
      super(name, type);
      this.kudf = requireNonNull(kudf, "kudf");
    }

    @Override
    public Object resolve(final GenericRow value) {
      return kudf;
    }

    @Override
    public String toString() {
      return "FunctionArgumentSpec{"
          + "name='" + name() + '\''
          + ", type=" + type()
          + ", kudf=" + kudf
          + '}';
    }
  }

  @Immutable
  public static final class ValueArgumentSpec extends BaseArgumentSpec {

    private final int columnIndex;

    ValueArgumentSpec(
        final String name,
        final Class<?> type,
        final int columnIndex
    ) {
      super(name, type);
      this.columnIndex = columnIndex;
    }

    @Override
    public Object resolve(final GenericRow value) {
      return value.getColumns().get(columnIndex);
    }

    @Override
    public String toString() {
      return "ValueArgumentSpec{"
          + "name='" + name() + '\''
          + ", type=" + type()
          + ", columnIndex=" + columnIndex
          + '}';
    }
  }
}
