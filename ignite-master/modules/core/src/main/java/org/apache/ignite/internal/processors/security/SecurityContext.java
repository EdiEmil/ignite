/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.security;

import org.apache.ignite.plugin.security.SecurityPermission;
import org.apache.ignite.plugin.security.SecuritySubject;

/**
 * This interface should be used to get security subject and perform checks for specific permissions.
 */
public interface SecurityContext {
    /**
     * @return Security subject.
     */
    public SecuritySubject subject();

    /**
     * Checks whether task operation is allowed.
     *
     * @param taskClsName Task class name.
     * @param perm Permission to check.
     * @return {@code True} if task operation is allowed.
     * @deprecated Use {@link IgniteSecurity#authorize(String, SecurityPermission)} instead.
     * This method will be removed in the future releases.
     */
    @Deprecated
    public boolean taskOperationAllowed(String taskClsName, SecurityPermission perm);

    /**
     * Checks whether cache operation is allowed.
     *
     * @param cacheName Cache name.
     * @param perm Permission to check.
     * @return {@code True} if cache operation is allowed.
     * @deprecated Use {@link IgniteSecurity#authorize(String, SecurityPermission)} instead.
     * This method will be removed in the future releases.
     */
    @Deprecated
    public boolean cacheOperationAllowed(String cacheName, SecurityPermission perm);

    /**
     * Checks whether service operation is allowed.
     *
     * @param srvcName Service name.
     * @param perm Permission to check.
     * @return {@code True} if task operation is allowed.
     * @deprecated Use {@link IgniteSecurity#authorize(String, SecurityPermission)} instead.
     * This method will be removed in the future releases.
     */
    @Deprecated
    public boolean serviceOperationAllowed(String srvcName, SecurityPermission perm);

    /**
     * Checks whether system-wide permission is allowed (excluding Visor task operations).
     *
     * @param perm Permission to check.
     * @return {@code True} if system operation is allowed.
     * @deprecated Use {@link IgniteSecurity#authorize(SecurityPermission)} instead.
     * This method will be removed in the future releases.
     */
    @Deprecated
    public boolean systemOperationAllowed(SecurityPermission perm);
}
