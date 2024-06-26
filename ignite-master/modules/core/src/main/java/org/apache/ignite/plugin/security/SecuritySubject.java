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

package org.apache.ignite.plugin.security;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.security.PermissionCollection;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.UUID;
import org.apache.ignite.internal.processors.security.SecurityUtils;

/**
 * Security subject representing authenticated node with a set of permissions.
 */
public interface SecuritySubject extends Serializable {
    /**
     * Gets subject ID.
     *
     * @return Subject ID.
     */
    public UUID id();

    /**
     * Gets subject type for node.
     *
     * @return Subject type.
     */
    public SecuritySubjectType type();

    /**
     * Login provided via subject security credentials.
     *
     * @return Login object.
     */
    public Object login();

    /**
     * Gets subject connection address. Usually {@link InetSocketAddress} representing connection IP and port.
     *
     * @return Subject connection address.
     */
    public InetSocketAddress address();

    /**
     * Gets subject client certificates, or {@code null} if SSL were not used or client certificate checking not enabled.
     *
     * @return Subject client certificates.
     */
    public default Certificate[] certificates() {
        return null;
    }

    /**
     * Authorized permission set for the subject.
     *
     * @return Authorized permission set for the subject.
     * @deprecated {@link SecuritySubject} must contain only immutable set of
     * information that represents a security principal. Security permissions are part of authorization process
     * and have nothing to do with {@link SecuritySubject}. This method will be removed in the future releases.
     */
    @Deprecated
    public SecurityPermissionSet permissions();

    /**
     * @return Permissions for SecurityManager checks.
     * @deprecated {@link SecuritySubject} must contain only immutable set of
     * information that represents a security principal. Security permissions are part of authorization process
     * and have nothing to do with {@link SecuritySubject}. This method will be removed in the future releases.
     */
    @Deprecated
    public default PermissionCollection sandboxPermissions() {
        ProtectionDomain pd = SecurityUtils.doPrivileged(() -> getClass().getProtectionDomain());

        return pd != null ? pd.getPermissions() : SecurityUtils.ALL_PERMISSIONS;
    }
}
