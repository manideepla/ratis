/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.raft.protocol.pb;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.ipc.ProtocolInfo;
import org.apache.hadoop.security.KerberosInfo;
import org.apache.raft.hadoopRpc.HadoopConstants;
import org.apache.raft.proto.RaftClientProtocolProtos;
import org.apache.raft.server.RaftConstants;

@InterfaceAudience.Private
@InterfaceStability.Unstable
@KerberosInfo(
    serverPrincipal = HadoopConstants.RAFT_SERVER_KERBEROS_PRINCIPAL_KEY,
    clientPrincipal = HadoopConstants.RAFT_CLIENT_KERBEROS_PRINCIPAL_KEY)
@ProtocolInfo(
    protocolName = HadoopConstants.RAFT_CLIENT_PROTOCOL_NAME,
    protocolVersion = 1)
public interface RaftClientProtocolPB extends
    RaftClientProtocolProtos.RaftClientProtocolService.BlockingInterface {
}
