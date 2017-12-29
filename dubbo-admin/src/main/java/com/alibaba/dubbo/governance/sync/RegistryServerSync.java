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
package com.alibaba.dubbo.governance.sync;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.governance.web.common.pulltool.Tool;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.RegistryService;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 */
public class RegistryServerSync implements InitializingBean, DisposableBean, NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryServerSync.class);

    private static final URL SUBSCRIBE = new URL(Constants.ADMIN_PROTOCOL, NetUtils.getLocalHost(), 0, "",
            Constants.INTERFACE_KEY, Constants.ANY_VALUE,
            Constants.GROUP_KEY, Constants.ANY_VALUE,
            Constants.VERSION_KEY, Constants.ANY_VALUE,
            Constants.CLASSIFIER_KEY, Constants.ANY_VALUE,
            Constants.CATEGORY_KEY, Constants.PROVIDERS_CATEGORY + ","
            + Constants.CONSUMERS_CATEGORY + ","
            + Constants.ROUTERS_CATEGORY + ","
            + Constants.CONFIGURATORS_CATEGORY,
            Constants.ENABLED_KEY, Constants.ANY_VALUE,
            Constants.CHECK_KEY, String.valueOf(false));

    private static final AtomicLong ID = new AtomicLong();

    /**
     * Make sure ID never changed when the same url notified many times
     */
    private final ConcurrentHashMap<String, Long> URL_IDS_MAPPER = new ConcurrentHashMap<String, Long>();

    // ConcurrentMap<category, ConcurrentMap<servicename, Map<Long, URL>>>
    private final ConcurrentMap<String, ConcurrentMap<String, Map<Long, URL>>> registryCache = new ConcurrentHashMap<String, ConcurrentMap<String, Map<Long, URL>>>();
    @Autowired
    private RegistryService registryService;

    public ConcurrentMap<String, ConcurrentMap<String, Map<Long, URL>>> getRegistryCache() {
        return registryCache;
    }

    public void afterPropertiesSet() throws Exception {
        logger.info("Init Dubbo Admin Sync Cache...");
        registryService.subscribe(SUBSCRIBE, this);
    }

    public void destroy() throws Exception {
        registryService.unsubscribe(SUBSCRIBE, this);
    }

    // Notification of of any service with any type (override、subcribe、route、provider) is full.
    public void notify(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        // Map<category, Map<servicename, Map<Long, URL>>>
        final Map<String, Map<String, Map<Long, URL>>> categories = new HashMap<String, Map<String, Map<Long, URL>>>();
        String interfaceName = null;
        // 遍历所有的服务
        // 一个url可能有多个provider,也可以能有多个consumer
        // 所以registryCache是key:string, value:Map
        for (URL url : urls) {
            // category表示你是生产者还是消费者
            String category = url.getParameter(Constants.CATEGORY_KEY, Constants.PROVIDERS_CATEGORY);
            if (Constants.EMPTY_PROTOCOL.equalsIgnoreCase(url.getProtocol())) { // NOTE: group and version in empty protocol is *
                ConcurrentMap<String, Map<Long, URL>> services = registryCache.get(category);
                // 从缓存中查看
                if (services != null) {
                    // 查看服务的group
                    String group = url.getParameter(Constants.GROUP_KEY);
                    // 查看服务的version
                    String version = url.getParameter(Constants.VERSION_KEY);
                    // NOTE: group and version in empty protocol is *
                    // 如果group不是任意的且version不是任意的，就可以从缓存中删除服务
                    // 因为在empty protocol中 group version都必须是任意的
                    if (!Constants.ANY_VALUE.equals(group) && !Constants.ANY_VALUE.equals(version)) {
                        services.remove(url.getServiceKey());
                    } else {
                        // 如果group是任意的，或者version是任意的，那么就遍历所有的服务
                        for (Map.Entry<String, Map<Long, URL>> serviceEntry : services.entrySet()) {
                            String service = serviceEntry.getKey();
                            // service中已经存在了，而且group,version都是任务的，那么需要从缓存中移除原来的服务
                            if (Tool.getInterface(service).equals(url.getServiceInterface())
                                    && (Constants.ANY_VALUE.equals(group) || StringUtils.isEquals(group, Tool.getGroup(service)))
                                    && (Constants.ANY_VALUE.equals(version) || StringUtils.isEquals(version, Tool.getVersion(service)))) {
                                services.remove(service);
                            }
                        }
                    }
                }
            } else {
                if (StringUtils.isEmpty(interfaceName)) {
                    interfaceName = url.getServiceInterface();
                }
                Map<String, Map<Long, URL>> services = categories.get(category);
                if (services == null) {
                    services = new HashMap<String, Map<Long, URL>>();
                    categories.put(category, services);
                }
                // 获取注册的service的name，通常是个bean name
                String service = url.getServiceKey();
                // key是当前系统维护的自增编号，用于区分不同的URL
                // 为什么services同一个服务名，但是对应的value却是个map呢？
                // 因为即便你服务的名称相同，并不代表你就是同一个服务
                // 可能服务名相同，但是接口不同的接口名，只是服务名相同而已
                // 可能服务名相同，接口名相同，但是group,version,timeout等参数配置不同，就是不同的service
                Map<Long, URL> ids = services.get(service);
                if (ids == null) {
                    ids = new HashMap<Long, URL>();
                    services.put(service, ids);
                }

                // Make sure we use the same ID for the same URL
                // URL_IDS_MAPPER对id 和 URL做个记录，如果已经添加了就把URL对应的id保存起来
                // 如果下次相同的URL被监听到，那么继续使用这个id
                // 否则需要对新的URL，添加新的id
                if (URL_IDS_MAPPER.containsKey(url.toFullString())) {
                    ids.put(URL_IDS_MAPPER.get(url.toFullString()), url);
                } else {
                    long currentId = ID.incrementAndGet();
                    ids.put(currentId, url);
                    URL_IDS_MAPPER.putIfAbsent(url.toFullString(), currentId);
                }
            }
        }

        // 如果注册的服务空了，直接返回
        if (categories.size() == 0) {
            return;
        }

        // 对缓存中所有的服务做更新校验
        // 遍历所有注册的服务
        for (Map.Entry<String, Map<String, Map<Long, URL>>> categoryEntry : categories.entrySet()) {
            // 服务的种类，consumer,provider
            String category = categoryEntry.getKey();
            // 从缓存中获取所有的服务
            ConcurrentMap<String, Map<Long, URL>> services = registryCache.get(category);
            if (services == null) {
                services = new ConcurrentHashMap<String, Map<Long, URL>>();
                registryCache.put(category, services);
            } else {// Fix map can not be cleared when service is unregistered: when a unique “group/service:version” service is unregistered, but we still have the same services with different version or group, so empty protocols can not be invoked.
                // 获取缓存中所有的服务名
                Set<String> keys = new HashSet<String>(services.keySet());
                for (String key : keys) {
                    // 如果缓存中不存在，则移除
                    if (Tool.getInterface(key).equals(interfaceName) && !categoryEntry.getValue().entrySet().contains(key)) {
                        services.remove(key);
                    }
                }
            }
            services.putAll(categoryEntry.getValue());
        }
    }
}
    
