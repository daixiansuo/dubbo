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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * When invoke fails, log the initial error and retry other invokers (retry n times, which means at most n different invokers will be invoked)
 * Note that retry causes latency.
 * <p>
 * 当调用失败时，记录初始错误并重试其他调用者（重试 n 次，这意味着最多会调用 n 个不同的调用者）注意重试会导致延迟。
 *
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Failover">Failover</a>
 * <p>
 * 当调用失败时，会重试其他服务器。用户可以通过retries="2" 设置重试次数。
 * 这是Dubbo的默认容错机制，会对请求做负载均衡。通常使用在读操作或幕等的写操作上，但重试会导致接口的延退增大，在下游机器负载已经达到极限时，重试容易加重下游 服务的负载
 */
public class FailoverClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailoverClusterInvoker.class);

    public FailoverClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(Invocation invocation, final List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {

        // 复制 invokers 集合
        List<Invoker<T>> copyinvokers = invokers;
        // 校验invokers是否为空
        checkInvokers(copyinvokers, invocation);
        // 方法名称
        String methodName = RpcUtils.getMethodName(invocation);
        // 重试次数 = retries + 1
        int len = getUrl().getMethodParameter(methodName, Constants.RETRIES_KEY, Constants.DEFAULT_RETRIES) + 1;
        if (len <= 0) {
            len = 1;
        }
        // retry loop.
        // 记录最后一个异常
        RpcException le = null; // last exception.
        // 记录调用的 invoker 集合
        List<Invoker<T>> invoked = new ArrayList<Invoker<T>>(copyinvokers.size()); // invoked invokers.
        // 服务提供者地址
        Set<String> providers = new HashSet<String>(len);

        // 循环调用，失败重试。 循环次数为 重试次数。
        for (int i = 0; i < len; i++) {
            //Reselect before retry to avoid a change of candidate `invokers`.
            //NOTE: if `invokers` changed, then `invoked` also lose accuracy.
            // 在进行重试前重新列举 Invoker，这样做的好处是，如果某个服务挂了，
            // 通过调用 list 可得到最新可用的 Invoker 列表
            if (i > 0) {

                // 检查是否销毁
                checkWhetherDestroyed();
                // 重新获取 invoker列表
                copyinvokers = list(invocation);
                // check again
                checkInvokers(copyinvokers, invocation);
            }

            // 通过负载均衡选择 invoker
            Invoker<T> invoker = select(loadbalance, invocation, copyinvokers, invoked);
            // 记录调用的 invoker
            invoked.add(invoker);
            // 将invoked设置到 RPC上下文对象
            RpcContext.getContext().setInvokers((List) invoked);
            try {
                // 执行调用
                Result result = invoker.invoke(invocation);
                if (le != null && logger.isWarnEnabled()) {
                    logger.warn("Although retry the method " + methodName
                            + " in the service " + getInterface().getName()
                            + " was successful by the provider " + invoker.getUrl().getAddress()
                            + ", but there have been failed providers " + providers
                            + " (" + providers.size() + "/" + copyinvokers.size()
                            + ") from the registry " + directory.getUrl().getAddress()
                            + " on the consumer " + NetUtils.getLocalHost()
                            + " using the dubbo version " + Version.getVersion() + ". Last error is: "
                            + le.getMessage(), le);
                }
                return result;
            } catch (RpcException e) {
                if (e.isBiz()) { // biz exception.
                    throw e;
                }
                le = e;
            } catch (Throwable e) {
                le = new RpcException(e.getMessage(), e);
            } finally {
                // 记录服务提供方地址
                providers.add(invoker.getUrl().getAddress());
            }
        }

        // 若重试失败，则抛出异常
        throw new RpcException(le != null ? le.getCode() : 0, "Failed to invoke the method "
                + methodName + " in the service " + getInterface().getName()
                + ". Tried " + len + " times of the providers " + providers
                + " (" + providers.size() + "/" + copyinvokers.size()
                + ") from the registry " + directory.getUrl().getAddress()
                + " on the consumer " + NetUtils.getLocalHost() + " using the dubbo version "
                + Version.getVersion() + ". Last error is: "
                + (le != null ? le.getMessage() : ""), le != null && le.getCause() != null ? le.getCause() : le);
    }

}
