/**
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.management.entity;

import org.terracotta.management.registry.action.AbstractActionManagementProvider;
import org.terracotta.management.registry.action.ExposedObject;
import org.terracotta.management.registry.action.Named;
import org.terracotta.management.registry.action.RequiredContext;

/**
 * @author Mathieu Carbou
 */
@Named("TheActionProvider")
@RequiredContext({@Named("cacheManagerName"), @Named("cacheName")})
public class MyManagementProvider extends AbstractActionManagementProvider<MyObject> {
  public MyManagementProvider() {
    super(MyObject.class);
  }

  @Override
  protected ExposedObject<MyObject> wrap(MyObject managedObject) {
    return managedObject;
  }
}