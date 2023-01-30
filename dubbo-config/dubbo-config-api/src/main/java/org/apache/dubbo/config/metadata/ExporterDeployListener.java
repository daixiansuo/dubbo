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
package org.apache.dubbo.config.metadata;

import org.apache.dubbo.common.deploy.ApplicationDeployListener;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.registry.client.metadata.MetadataServiceDelegation;
import org.apache.dubbo.rpc.model.ApplicationModel;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;

public class ExporterDeployListener implements ApplicationDeployListener, Prioritized {
    protected volatile ConfigurableMetadataServiceExporter metadataServiceExporter;

    @Override
    public void onStarting(ApplicationModel scopeModel) {

    }

    @Override
    public synchronized void onStarted(ApplicationModel applicationModel) {

    }

    @Override
    public synchronized void onStopping(ApplicationModel scopeModel) {

    }

    private String getMetadataType(ApplicationModel applicationModel) {
        String type = applicationModel.getApplicationConfigManager().getApplicationOrElseThrow().getMetadataType();
        if (StringUtils.isEmpty(type)) {
            type = DEFAULT_METADATA_STORAGE_TYPE;
        }
        return type;
    }


    public ConfigurableMetadataServiceExporter getMetadataServiceExporter() {
        return metadataServiceExporter;
    }

    public void setMetadataServiceExporter(ConfigurableMetadataServiceExporter metadataServiceExporter) {
        this.metadataServiceExporter = metadataServiceExporter;
    }

    @Override
    public synchronized void onModuleStarted(ApplicationModel applicationModel) {
        // start metadata service exporter
        // MetadataServiceDelegation 类型为实现提供远程RPC服务以方便元数据信息的查询功能的类型。
        MetadataServiceDelegation metadataService = applicationModel.getBeanFactory().getOrRegisterBean(MetadataServiceDelegation.class);
        if (metadataServiceExporter == null) {
            metadataServiceExporter = new ConfigurableMetadataServiceExporter(applicationModel, metadataService);
            // fixme, let's disable local metadata service export at this moment
            // 默认我们是没有配置这个元数据类型的这里元数据类型默认为local， 条件是：不是remote则开始导出，
            // 在前面的博客<<Dubbo启动器DubboBootstrap添加应用程序的配置信息ApplicationConfig>> 中有提到这个配置下面我再说下
            if (!REMOTE_METADATA_STORAGE_TYPE.equals(getMetadataType(applicationModel))) {
                // 服务导出 ！！！
                metadataServiceExporter.export();
            }
        }
    }


    @Override
    public synchronized void onStopped(ApplicationModel scopeModel) {
        if (metadataServiceExporter != null && metadataServiceExporter.isExported()) {
            try {
                metadataServiceExporter.unexport();
            } catch (Exception ignored) {
                // ignored
            }
        }
    }

    @Override
    public void onFailure(ApplicationModel scopeModel, Throwable cause) {

    }

    @Override
    public int getPriority() {
        return MAX_PRIORITY;
    }
}
