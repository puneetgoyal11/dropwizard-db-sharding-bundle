/*
 * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.dropwizard.sharding;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.dropwizard.sharding.config.ShardedHibernateFactory;
import io.dropwizard.sharding.dao.RelationalDao;
import io.dropwizard.sharding.dao.LookupDao;
import io.dropwizard.sharding.dao.WrapperDao;
import io.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.hibernate.AbstractDAO;
import io.dropwizard.hibernate.HibernateBundle;
import io.dropwizard.hibernate.SessionFactoryFactory;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import lombok.val;
import org.hibernate.SessionFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A dropwizard bundle that provides sharding over normal RDBMS.
 */
public abstract class DBShardingBundle<T extends Configuration> implements ConfiguredBundle<T> {
    private List<HibernateBundle<T>> shardBundles = Lists.newArrayList();
    @Getter
    private List<SessionFactory> sessionFactories;
    @Getter
    private ShardManager shardManager;

    public DBShardingBundle(Class<?> entity, Class<?> ...entities) {
        String numShardsEnv = System.getProperty("db.shards", "2");
        int numShards = Integer.parseInt(numShardsEnv);
        shardManager = new ShardManager(numShards);
        val inEntities = ImmutableList.<Class<?>>builder().add(entity).add(entities).build();
        for(int i = 0; i < numShards; i++) {
            final int finalI = i;
            shardBundles.add(new HibernateBundle<T>(inEntities, new SessionFactoryFactory()) {
                @Override
                protected String name() {
                    return String.format("connectionpool-%d", finalI);
                }

                @Override
                public PooledDataSourceFactory getDataSourceFactory(T t) {
                    return getConfig(t).getShards().get(finalI);
                }
            });
        }
    }

    @Override
    public void run(T configuration, Environment environment) throws Exception {
        sessionFactories = shardBundles.stream().map(HibernateBundle::getSessionFactory).collect(Collectors.toList());

    }

    @Override
    @SuppressWarnings("unchecked")
    public void initialize(Bootstrap<?> bootstrap) {
        //shardBundles.forEach(shardBundle -> bootstrap.addBundle((ConfiguredBundle)shardBundle));
        shardBundles.forEach(hibernateBundle -> bootstrap.addBundle((ConfiguredBundle)hibernateBundle));
    }

    @VisibleForTesting
    public void runBundles(T configuration, Environment environment) {
        shardBundles.forEach(hibernateBundle -> {
            try {
                hibernateBundle.run(configuration, environment);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
    @VisibleForTesting
    public void initBundles(Bootstrap bootstrap) {
        shardBundles.forEach(hibernameBundle ->initialize(bootstrap));
    }

    protected abstract ShardedHibernateFactory getConfig(T config);

    public static <EntityType, T extends Configuration>
    LookupDao<EntityType> createParentObjectDao(DBShardingBundle<T> bundle, Class<EntityType> clazz) {
        return new LookupDao<>(bundle.sessionFactories, clazz, bundle.shardManager);
    }

    public static <EntityType, T extends Configuration>
    RelationalDao<EntityType> createRelatedObjectDao(DBShardingBundle<T> bundle, Class<EntityType> clazz) {
        return new RelationalDao<>(bundle.sessionFactories, clazz, bundle.shardManager);
    }

    public static <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
    WrapperDao<EntityType, DaoType> createWrapperDao(DBShardingBundle<T> bundle, Class<DaoType> daoTypeClass) {
        return new WrapperDao<>(bundle.sessionFactories, daoTypeClass, bundle.shardManager);
    }

    public static <EntityType, DaoType extends AbstractDAO<EntityType>, T extends Configuration>
    WrapperDao<EntityType, DaoType> createWrapperDao(DBShardingBundle<T> bundle, Class<DaoType> daoTypeClass,
                                                     Class[] extraConstructorParamClasses, Class[] extraConstructorParamObjects) {
        return new WrapperDao<>(bundle.sessionFactories, daoTypeClass, bundle.shardManager,
                extraConstructorParamClasses, extraConstructorParamObjects);
    }
}
