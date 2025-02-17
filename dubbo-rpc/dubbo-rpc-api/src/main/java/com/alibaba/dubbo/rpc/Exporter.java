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
package com.alibaba.dubbo.rpc;

/**
 * Exporter. (API/SPI, Prototype, ThreadSafe)
 * 该接口是暴露服务的接口，定义了两个方法分别是获得invoker和取消暴露服务。
 *
 * @see com.alibaba.dubbo.rpc.Protocol#export(Invoker)
 * @see com.alibaba.dubbo.rpc.ExporterListener
 * @see com.alibaba.dubbo.rpc.protocol.AbstractExporter
 */
public interface Exporter<T> {

    /**
     * get invoker.
     * 获得对应的实体域 invoker
     *
     * @return invoker
     */
    Invoker<T> getInvoker();

    /**
     * unexport.
     * 取消暴露
     * <p>
     * <code>
     * getInvoker().destroy();
     * </code>
     */
    void unexport();

}