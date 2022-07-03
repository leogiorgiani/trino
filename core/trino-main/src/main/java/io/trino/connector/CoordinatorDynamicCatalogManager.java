/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.connector;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.trino.connector.system.GlobalSystemConnector;
import io.trino.metadata.Catalog;
import io.trino.metadata.CatalogManager;
import io.trino.spi.TrinoException;

import javax.annotation.PreDestroy;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.trino.connector.CatalogHandle.createRootCatalogHandle;
import static io.trino.spi.StandardErrorCode.ALREADY_EXISTS;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public class CoordinatorDynamicCatalogManager
        implements CatalogManager, ConnectorServicesProvider
{
    private final CatalogFactory catalogFactory;

    private final Lock catalogsUpdateLock = new ReentrantLock();
    private final ConcurrentMap<String, CatalogConnector> catalogs = new ConcurrentHashMap<>();

    @GuardedBy("catalogsUpdateLock")
    private boolean stopped;

    @Inject
    public CoordinatorDynamicCatalogManager(CatalogFactory catalogFactory)
    {
        this.catalogFactory = requireNonNull(catalogFactory, "catalogFactory is null");
    }

    @PreDestroy
    public void stop()
    {
        List<CatalogConnector> catalogs;

        catalogsUpdateLock.lock();
        try {
            if (stopped) {
                return;
            }
            stopped = true;

            catalogs = ImmutableList.copyOf(this.catalogs.values());
            this.catalogs.clear();
        }
        finally {
            catalogsUpdateLock.unlock();
        }

        for (CatalogConnector connector : catalogs) {
            connector.shutdown();
        }
    }

    @Override
    public void loadInitialCatalogs() {}

    @Override
    public Set<String> getCatalogNames()
    {
        return ImmutableSet.copyOf(catalogs.keySet());
    }

    @Override
    public Optional<Catalog> getCatalog(String catalogName)
    {
        return Optional.ofNullable(catalogs.get(catalogName))
                .map(CatalogConnector::getCatalog);
    }

    @Override
    public ConnectorServices getConnectorServices(CatalogHandle catalogHandle)
    {
        CatalogConnector catalogConnector = catalogs.get(catalogHandle.getCatalogName());
        checkArgument(catalogConnector != null, "No catalog '%s'", catalogHandle.getCatalogName());
        return catalogConnector.getMaterializedConnector(catalogHandle.getType());
    }

    @Override
    public CatalogHandle createCatalog(String catalogName, String connectorName, Map<String, String> properties)
    {
        requireNonNull(catalogName, "catalogName is null");
        requireNonNull(connectorName, "connectorName is null");
        requireNonNull(properties, "properties is null");

        catalogsUpdateLock.lock();
        try {
            checkState(!stopped, "ConnectorManager is stopped");

            if (catalogs.containsKey(catalogName)) {
                throw new TrinoException(ALREADY_EXISTS, format("Catalog '%s' already exists", catalogName));
            }

            CatalogProperties catalogProperties = new CatalogProperties(createRootCatalogHandle(catalogName), connectorName, properties);
            CatalogConnector catalog = catalogFactory.createCatalog(catalogProperties);
            catalogs.put(catalogName, catalog);
            return catalog.getCatalogHandle();
        }
        finally {
            catalogsUpdateLock.unlock();
        }
    }

    public void registerGlobalSystemConnector(GlobalSystemConnector connector)
    {
        requireNonNull(connector, "connector is null");

        catalogsUpdateLock.lock();
        try {
            if (stopped) {
                return;
            }

            CatalogConnector catalog = catalogFactory.createCatalog(GlobalSystemConnector.CATALOG_HANDLE, GlobalSystemConnector.NAME, connector);
            if (catalogs.putIfAbsent(GlobalSystemConnector.NAME, catalog) != null) {
                throw new IllegalStateException("Global system catalog already registered");
            }
        }
        finally {
            catalogsUpdateLock.unlock();
        }
    }
}
