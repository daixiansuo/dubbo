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
package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcStatus;

import java.util.concurrent.Semaphore;

/**
 * 该过滤器是限制最大可并行执行请求数，该过滤器是服务提供者侧，而 ActiveLimitFilter 是在消费者侧的限制。
 * <p>
 * ThreadLimitInvokerFilter
 */
@Activate(group = Constants.PROVIDER, value = Constants.EXECUTES_KEY)
public class ExecuteLimitFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获取URL
        URL url = invoker.getUrl();
        // 方法名称
        String methodName = invocation.getMethodName();
        // 信号量
        Semaphore executesLimit = null;
        boolean acquireResult = false;
        // 获取最大可并行数量
        int max = url.getMethodParameter(methodName, Constants.EXECUTES_KEY, 0);
        // 如果该方法设置两 executes 并且值 > 0
        if (max > 0) {
            // 获得该方法对应 RpcStatus
            RpcStatus count = RpcStatus.getStatus(url, invocation.getMethodName());
//            if (count.getActive() >= max) {
            /**
             * http://manzhizhen.iteye.com/blog/2386408
             * use semaphore for concurrency control (to limit thread number)
             */
            // 获取信号量
            executesLimit = count.getSemaphore(max);
            // 如果不能获得许可，则抛出异常
            if (executesLimit != null && !(acquireResult = executesLimit.tryAcquire())) {
                throw new RpcException("Failed to invoke method " + invocation.getMethodName() + " in provider " + url + ", cause: The service using threads greater than <dubbo:service executes=\"" + max + "\" /> limited.");
            }
        }
        long begin = System.currentTimeMillis();
        boolean isSuccess = true;
        // 开始计数
        RpcStatus.beginCount(url, methodName);
        try {
            // 调用下一个调用链
            Result result = invoker.invoke(invocation);
            return result;
        } catch (Throwable t) {
            isSuccess = false;
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else {
                throw new RpcException("unexpected exception when ExecuteLimitFilter", t);
            }
        } finally {
            // 结束计数
            RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, isSuccess);
            if (acquireResult) {
                executesLimit.release();
            }
        }
    }

}
