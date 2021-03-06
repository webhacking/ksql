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

package io.confluent.ksql.function.types;

public final class BooleanType extends ObjectType {

  public static final BooleanType INSTANCE = new BooleanType();

  private BooleanType() {
  }

  @Override
  public int hashCode() {
    return 1;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof  BooleanType;
  }

  @Override
  public String toString() {
    return "BOOLEAN";
  }
}
