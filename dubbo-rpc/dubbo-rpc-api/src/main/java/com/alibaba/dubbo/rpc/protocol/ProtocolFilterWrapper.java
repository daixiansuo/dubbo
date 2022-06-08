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
package com.alibaba.dubbo.rpc.protocol;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.*;

import java.util.List;

/**
 * ListenerProtocol
 * 该类实现了Protocol接口，其中也用到了装饰模式，是对Protocol的装饰，是在服务引用和暴露的方法上加上了过滤器功能。
 */
public class ProtocolFilterWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }


    /**
     * 该方法就是创建带 Filter 链的 Invoker 对象。倒序的把每一个过滤器串连起来，形成一个invoker。
     *
     * @param invoker 实体域
     * @param key     消费方: service.filter / 提供方: reference.filter
     * @param group   provider / consumer
     * @param <T>     T 泛型
     * @return Invoker
     */
    private static <T> Invoker<T> buildInvokerChain(final Invoker<T> invoker, String key, String group) {

        // TODO：保存引用，后续用于把真正的调用者保存到过滤器链的最后
        Invoker<T> last = invoker;
        // 获得 过滤器的所有扩展实现类 实例集合
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).getActivateExtension(invoker.getUrl(), key, group);

        // 过滤器不为空，倒序遍历所有过滤器
        if (!filters.isEmpty()) {

            // 对过滤器做倒排遍历，即从尾到头
            for (int i = filters.size() - 1; i >= 0; i--) {

                final Filter filter = filters.get(i);
                // TODO：注意这段逻辑，把 last 节点变成 next 节点，并放到 Filter 链的 next 中。
                final Invoker<T> next = last;

                // 为每个 filter 生成一个 invoker，依次串起来
                last = new Invoker<T>() {


                    /**
                     TODO：为什么要倒排遍历呢 ？

                     因为是通过从里到外构造匿名类的方式构造Invoker的，所以只有倒排，最外层的Invoker才能是第一个过滤器。

                     我们来看一个例子:
                     假设有过滤器A、B、C 和 Invoker，会按照C、B、A倒序遍历，过滤器链构建顺序为: C—Invoker, B—C—Invoker, A—B—C—Invoker。
                     最终调用时的顺序就会变为A是第一个过 滤器。
                     */


                    @Override
                    public Result invoke(Invocation invocation) throws RpcException {
                        // TODO：设置过滤器链的下一个节点，不断循环形成过滤器链。
                        return filter.invoke(next, invocation);
                    }

                    @Override
                    public Class<T> getInterface() {
                        return invoker.getInterface();
                    }

                    @Override
                    public URL getUrl() {
                        return invoker.getUrl();
                    }

                    @Override
                    public boolean isAvailable() {
                        return invoker.isAvailable();
                    }

                    @Override
                    public void destroy() {
                        invoker.destroy();
                    }

                    @Override
                    public String toString() {
                        return invoker.toString();
                    }
                };
            }
        }
        return last;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }


    /**
     * 该方法是在服务暴露上做了过滤器链的增强，也就是加上了过滤器。
     *
     * @param invoker Service invoker
     * @param <T>     泛型
     * @return Exporter
     * @throws RpcException e
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // 如果是注册中心，则直接暴露服务
        if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            return protocol.export(invoker);
        }
        // 服务提供侧暴露服务：先构造拦截器链（会过滤provider端分组），然后触发具体协议暴露
        return protocol.export(buildInvokerChain(invoker, Constants.SERVICE_FILTER_KEY, Constants.PROVIDER));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // 如果是注册中心，则直接引用
        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            return protocol.refer(type, url);
        }
        // 消费者侧引用服务
        return buildInvokerChain(protocol.refer(type, url), Constants.REFERENCE_FILTER_KEY, Constants.CONSUMER);
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

}
