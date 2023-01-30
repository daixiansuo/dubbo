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
package org.apache.dubbo.registry.client;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.FrameworkExecutorRepository;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.metadata.MetadataInfo;
import org.apache.dubbo.metadata.report.MetadataReport;
import org.apache.dubbo.metadata.report.MetadataReportInstance;
import org.apache.dubbo.metadata.report.identifier.SubscriberMetadataIdentifier;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils;
import org.apache.dubbo.registry.client.metadata.store.MetaCacheManager;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.CommonConstants.REGISTRY_LOCAL_FILE_CACHE_ENABLED;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_METADATA_STORAGE_TYPE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.REGISTRY_FAILED_FETCH_INSTANCE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.REGISTRY_FAILED_LOAD_METADATA;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_CLUSTER_KEY;
import static org.apache.dubbo.metadata.RevisionResolver.EMPTY_REVISION;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.EXPORTED_SERVICES_REVISION_PROPERTY_NAME;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.getExportedServicesRevision;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.isValidInstance;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.setMetadataStorageType;

/**
 * Each service discovery is bond to one application.
 */
public abstract class AbstractServiceDiscovery implements ServiceDiscovery {
    private final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(AbstractServiceDiscovery.class);
    private volatile boolean isDestroy;

    protected final String serviceName;
    protected volatile ServiceInstance serviceInstance;
    protected volatile MetadataInfo metadataInfo;
    protected MetadataReport metadataReport;
    protected String metadataType;
    protected final MetaCacheManager metaCacheManager;
    protected URL registryURL;

    protected Set<ServiceInstancesChangedListener> instanceListeners = new ConcurrentHashSet<>();

    protected ApplicationModel applicationModel;

    public AbstractServiceDiscovery(ApplicationModel applicationModel, URL registryURL) {
        this(applicationModel, applicationModel.getApplicationName(), registryURL);
        MetadataReportInstance metadataReportInstance = applicationModel.getBeanFactory().getBean(MetadataReportInstance.class);
        metadataType = metadataReportInstance.getMetadataType();
        this.metadataReport = metadataReportInstance.getMetadataReport(registryURL.getParameter(REGISTRY_CLUSTER_KEY));
    }

    public AbstractServiceDiscovery(String serviceName, URL registryURL) {
        this(ApplicationModel.defaultModel(), serviceName, registryURL);
    }

    private AbstractServiceDiscovery(ApplicationModel applicationModel, String serviceName, URL registryURL) {
        this.applicationModel = applicationModel;
        this.serviceName = serviceName;
        this.registryURL = registryURL;
        this.metadataInfo = new MetadataInfo(serviceName);
        boolean localCacheEnabled = registryURL.getParameter(REGISTRY_LOCAL_FILE_CACHE_ENABLED, true);
        // 这个是元数据缓存信息管理的类型 缓存文件使用LRU策略  感兴趣的可以详细看看
        // 对应缓存路径为：/Users/song/.dubbo/.metadata.zookeeper127.0.0.1%003a2181.dubbo.cache
        this.metaCacheManager = new MetaCacheManager(localCacheEnabled, getCacheNameSuffix(),
            applicationModel.getFrameworkModel().getBeanFactory()
                .getBean(FrameworkExecutorRepository.class).getCacheRefreshingScheduledExecutor());
    }


    /**
     * 模块发布器(DefaultModuleDeployer) 启动成功之后，通知 应用发布器(DefaultApplicationDeployer)，
     * 应用发布器 发布元数据信息和  应用实例信息（registerServiceInstance）
     * trigger invoke: org.apache.dubbo.config.deploy.DefaultApplicationDeployer#registerServiceInstance()
     *
     * @throws RuntimeException
     */
    @Override
    public synchronized void register() throws RuntimeException {
        if (isDestroy) {
            return;
        }
        // 创建应用的实例信息 等待下面注册到注册中心
        this.serviceInstance = createServiceInstance(this.metadataInfo);
        if (!isValidInstance(this.serviceInstance)) {
            logger.warn(REGISTRY_FAILED_FETCH_INSTANCE, "", "", "No valid instance found, stop registering instance address to registry.");
            return;
        }

        // 是否需要更新
        boolean revisionUpdated = calOrUpdateInstanceRevision(this.serviceInstance);
        if (revisionUpdated) {
            // 元数据注册
            reportMetadata(this.metadataInfo);
            // 应用的实例信息注册到注册中心之上
            doRegister(this.serviceInstance);
        }
    }

    /**
     * Update assumes that DefaultServiceInstance and its attributes will never get updated once created.
     * Checking hasExportedServices() before registration guarantees that at least one service is ready for creating the
     * instance.
     */
    @Override
    public synchronized void update() throws RuntimeException {
        if (isDestroy) {
            return;
        }

        if (this.serviceInstance == null) {
            this.serviceInstance = createServiceInstance(this.metadataInfo);
        } else if (!isValidInstance(this.serviceInstance)) {
            ServiceInstanceMetadataUtils.customizeInstance(this.serviceInstance, this.applicationModel);
        }

        if (!isValidInstance(this.serviceInstance)) {
            return;
        }

        boolean revisionUpdated = calOrUpdateInstanceRevision(this.serviceInstance);
        if (revisionUpdated) {
            logger.info(String.format("Metadata of instance changed, updating instance with revision %s.", this.serviceInstance.getServiceMetadata().getRevision()));
            doUpdate(this.serviceInstance);
        }
    }

    @Override
    public synchronized void unregister() throws RuntimeException {
        if (isDestroy) {
            return;
        }
        // fixme, this metadata info might still being shared by other instances
//        unReportMetadata(this.metadataInfo);
        if (!isValidInstance(this.serviceInstance)) {
            return;
        }
        doUnregister(this.serviceInstance);
    }

    @Override
    public final ServiceInstance getLocalInstance() {
        return this.serviceInstance;
    }

    @Override
    public MetadataInfo getLocalMetadata() {
        return this.metadataInfo;
    }

    @Override
    public MetadataInfo getRemoteMetadata(String revision, List<ServiceInstance> instances) {
        MetadataInfo metadata = metaCacheManager.get(revision);

        if (metadata != null && metadata != MetadataInfo.EMPTY) {
            metadata.init();
            // metadata loaded from cache
            if (logger.isDebugEnabled()) {
                logger.debug("MetadataInfo for revision=" + revision + ", " + metadata);
            }
            return metadata;
        }

        synchronized (metaCacheManager) {
            // try to load metadata from remote.
            int triedTimes = 0;
            while (triedTimes < 3) {
                metadata = MetadataUtils.getRemoteMetadata(revision, instances, metadataReport);

                if (metadata != MetadataInfo.EMPTY) {// succeeded
                    metadata.init();
                    break;
                } else {// failed
                    if (triedTimes > 0) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Retry the " + triedTimes + " times to get metadata for revision=" + revision);
                        }
                    }
                    triedTimes++;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                }
            }

            if (metadata == MetadataInfo.EMPTY) {
                logger.error(REGISTRY_FAILED_LOAD_METADATA, "", "", "Failed to get metadata for revision after 3 retries, revision=" + revision);
            } else {
                metaCacheManager.put(revision, metadata);
            }
        }
        return metadata;
    }

    @Override
    public MetadataInfo getRemoteMetadata(String revision) {
        return metaCacheManager.get(revision);
    }

    @Override
    public final void destroy() throws Exception {
        isDestroy = true;
        metaCacheManager.destroy();
        doDestroy();
    }

    @Override
    public final boolean isDestroy() {
        return isDestroy;
    }

    @Override
    public void register(URL url) {
        // metadaInfo 类型为MetadataInfo类型，用来操作元数据的
        metadataInfo.addService(url);
    }

    @Override
    public void unregister(URL url) {
        metadataInfo.removeService(url);
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        metadataInfo.addSubscribedURL(url);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        metadataInfo.removeSubscribedURL(url);
    }

    @Override
    public List<URL> lookup(URL url) {
        throw new UnsupportedOperationException("Service discovery implementation does not support lookup of url list.");
    }

    protected void doUpdate(ServiceInstance serviceInstance) throws RuntimeException {
        this.unregister();

        if (!EMPTY_REVISION.equals(getExportedServicesRevision(serviceInstance))) {
            reportMetadata(serviceInstance.getServiceMetadata());
            this.doRegister(serviceInstance);
        }
    }

    @Override
    public URL getUrl() {
        return registryURL;
    }

    protected abstract void doRegister(ServiceInstance serviceInstance) throws RuntimeException;

    protected abstract void doUnregister(ServiceInstance serviceInstance);

    protected abstract void doDestroy() throws Exception;

    protected ServiceInstance createServiceInstance(MetadataInfo metadataInfo) {
        DefaultServiceInstance instance = new DefaultServiceInstance(serviceName, applicationModel);
        instance.setServiceMetadata(metadataInfo);
        // metadataType的值为local 这个方法是将元数据类型存储到应用的元数据对象中 对应内容为dubbo.metadata.storage-type:local
        setMetadataStorageType(instance, metadataType);
        // 这个是自定义元数据数据 我们也可以通过实现扩展ServiceInstanceCustomizer来自定义一些元数据
        ServiceInstanceMetadataUtils.customizeInstance(instance, applicationModel);
        return instance;
    }

    protected boolean calOrUpdateInstanceRevision(ServiceInstance instance) {
        // 获取元数据版本号对应字段dubbo.metadata.revision
        String existingInstanceRevision = getExportedServicesRevision(instance);
        //获取实例的服务元数据信息：metadata{app='dubbo-demo-api-provider',revision='null',size=1,services=[link.elastic.dubbo.entity.DemoService:dubbo]}
        MetadataInfo metadataInfo = instance.getServiceMetadata();
        // 必须在不同线程之间同步计算此实例的状态，如同一实例的修订和修改。此方法的使用仅限于某些点，例如在注册期间。始终尝试使用此选项。改为getRevision（）。
        String newRevision = metadataInfo.calAndGetRevision();
        // 版本号发生了变更（元数据发生了变更）版本号是md5元数据信息计算出来HASH验证
        if (!newRevision.equals(existingInstanceRevision)) {
            // 版本号添加到dubbo.metadata.revision字段中
            instance.getMetadata().put(EXPORTED_SERVICES_REVISION_PROPERTY_NAME, metadataInfo.getRevision());
            return true;
        }
        return false;
    }

    protected void reportMetadata(MetadataInfo metadataInfo) {
        if (metadataReport != null) {
            // 订阅元数据的标识符
            SubscriberMetadataIdentifier identifier = new SubscriberMetadataIdentifier(serviceName, metadataInfo.getRevision());
            // 是否远程发布元数据，这里我们是本地注册这个就不会在元数据中心发布这个元数据信息
            if ((DEFAULT_METADATA_STORAGE_TYPE.equals(metadataType) && metadataReport.shouldReportMetadata()) || REMOTE_METADATA_STORAGE_TYPE.equals(metadataType)) {
                metadataReport.publishAppMetadata(identifier, metadataInfo);
            }
        }
    }

    protected void unReportMetadata(MetadataInfo metadataInfo) {
        if (metadataReport != null) {
            SubscriberMetadataIdentifier identifier = new SubscriberMetadataIdentifier(serviceName, metadataInfo.getRevision());
            if ((DEFAULT_METADATA_STORAGE_TYPE.equals(metadataType) && metadataReport.shouldReportMetadata()) || REMOTE_METADATA_STORAGE_TYPE.equals(metadataType)) {
                metadataReport.unPublishAppMetadata(identifier, metadataInfo);
            }
        }
    }

    private String getCacheNameSuffix() {
        String name = this.getClass().getSimpleName();
        int i = name.indexOf(ServiceDiscovery.class.getSimpleName());
        if (i != -1) {
            name = name.substring(0, i);
        }
        StringBuilder stringBuilder = new StringBuilder(128);
        Optional<ApplicationConfig> application = applicationModel.getApplicationConfigManager().getApplication();
        if (application.isPresent()) {
            stringBuilder.append(application.get().getName());
            stringBuilder.append(".");
        }
        stringBuilder.append(name.toLowerCase());
        URL url = this.getUrl();
        if (url != null) {
            stringBuilder.append(".");
            stringBuilder.append(url.getBackupAddress());
        }
        return stringBuilder.toString();
    }
}
