/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Directory;

/**
 * {@link FailbackClusterInvoker}
 *
 * 请求失败后，会自动记录在失败队列中，并由一个定时线程池定时重试，适用于一些 异步或最终一致性的请求。请求会做负载均衡
 *
 */
public class FailbackCluster implements Cluster {

    public final static String NAME = "failback";

    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
        return new FailbackClusterInvoker<T>(directory);
    }

}
