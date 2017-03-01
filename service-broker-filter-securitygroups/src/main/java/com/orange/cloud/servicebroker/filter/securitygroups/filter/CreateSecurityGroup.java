/*
 * <!--
 *
 *     Copyright (C) 2015 Orange
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 * -->
 */

package com.orange.cloud.servicebroker.filter.securitygroups.filter;

import com.orange.cloud.servicebroker.filter.core.filters.CreateServiceInstanceBindingPostFilter;
import com.orange.cloud.servicebroker.filter.core.filters.ServiceBrokerPostFilter;
import lombok.extern.slf4j.Slf4j;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.ApplicationEntity;
import org.cloudfoundry.client.v2.applications.GetApplicationRequest;
import org.cloudfoundry.client.v2.applications.GetApplicationResponse;
import org.cloudfoundry.client.v2.securitygroups.CreateSecurityGroupRequest;
import org.cloudfoundry.client.v2.securitygroups.Protocol;
import org.cloudfoundry.client.v2.securitygroups.RuleEntity;
import org.cloudfoundry.client.v2.securitygroups.SecurityGroupEntity;
import org.cloudfoundry.client.v2.servicebrokers.GetServiceBrokerRequest;
import org.cloudfoundry.client.v2.servicebrokers.ServiceBrokerEntity;
import org.cloudfoundry.client.v2.serviceinstances.GetServiceInstanceRequest;
import org.cloudfoundry.client.v2.serviceinstances.ServiceInstanceEntity;
import org.cloudfoundry.client.v2.services.GetServiceRequest;
import org.cloudfoundry.client.v2.services.ServiceEntity;
import org.cloudfoundry.util.ResourceUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.servicebroker.model.CreateServiceInstanceAppBindingResponse;
import org.springframework.cloud.servicebroker.model.CreateServiceInstanceBindingRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

import static org.cloudfoundry.util.tuple.TupleUtils.function;

@Slf4j
@Component
public class CreateSecurityGroup implements CreateServiceInstanceBindingPostFilter, ServiceBrokerPostFilter<CreateServiceInstanceBindingRequest, CreateServiceInstanceAppBindingResponse> {

    static final Protocol DEFAULT_PROTOCOL = Protocol.TCP;
    private CloudFoundryClient cloudFoundryClient;

    @Autowired
    public CreateSecurityGroup(CloudFoundryClient cloudFoundryClient) {
        this.cloudFoundryClient = cloudFoundryClient;
    }

    private static Mono<ServiceEntity> getService(CloudFoundryClient cloudFoundryClient, String serviceId) {
        return cloudFoundryClient.services()
                .get(GetServiceRequest.builder()
                        .serviceId(serviceId)
                        .build()
                )
                .map(ResourceUtils::getEntity);
    }

    private static Mono<ServiceBrokerEntity> getServiceBroker(CloudFoundryClient cloudFoundryClient, String serviceBrokerId) {
        return cloudFoundryClient.serviceBrokers().get(GetServiceBrokerRequest.builder()
                .serviceBrokerId(serviceBrokerId).build())
                .map(ResourceUtils::getEntity);
    }

    private static Mono<ServiceInstanceEntity> getServiceInstance(CloudFoundryClient cloudFoundryClient, String serviceInstanceId) {
        return cloudFoundryClient.serviceInstances()
                .get(GetServiceInstanceRequest.builder()
                        .serviceInstanceId(serviceInstanceId)
                        .build()
                )
                .map(ResourceUtils::getEntity);
    }

    private static Mono<String> getRuleDescription(CloudFoundryClient cloudFoundryClient, String bindingId, String serviceInstanceId, String serviceId) {
        return getService(cloudFoundryClient, serviceId).map(ServiceEntity::getServiceBrokerId)
                .then(serviceBrokerId -> Mono.when(
                        Mono.just(bindingId),
                        getServiceInstanceName(cloudFoundryClient, serviceInstanceId),
                        getServiceBrokerName(cloudFoundryClient, serviceBrokerId)
                ))
                .map(function((servicebindingId, serviceInstanceName, serviceBrokerName) -> ImmutableRuleDescription.builder()
                        .servicebindingId(servicebindingId)
                        .serviceInstanceName(serviceInstanceName)
                        .serviceBrokerName(serviceBrokerName).build()
                        .value()));
    }

    private static Mono<String> getServiceBrokerName(CloudFoundryClient cloudFoundryClient, String serviceBrokerId) {
        return getServiceBroker(cloudFoundryClient, serviceBrokerId)
                .map(ServiceBrokerEntity::getName);
    }

    private static Mono<String> getServiceInstanceName(CloudFoundryClient cloudFoundryClient, String serviceInstanceId) {
        return getServiceInstance(cloudFoundryClient, serviceInstanceId)
                .map(ServiceInstanceEntity::getName);
    }

    @Override
    public void run(CreateServiceInstanceBindingRequest request, CreateServiceInstanceAppBindingResponse response) {
        Assert.notNull(response);
        Assert.notNull(response.getCredentials());

        final ConnectionInfo connectionInfo = ConnectionInfoFactory.fromCredentials(response.getCredentials());

        log.debug("creating security group for credentials {}.", response.getCredentials());

        try {

            final SecurityGroupEntity securityGroup = Mono.when(
                    getRuleDescription(cloudFoundryClient, request.getBindingId(), request.getServiceInstanceId(), request.getServiceDefinitionId()),
                    getSpaceId(cloudFoundryClient, request.getBoundAppGuid())
            ).then(function((description, spaceId) -> create(getSecurityGroupName(request), connectionInfo, description, spaceId)))
                    .doOnError(t -> log.error("Fail to create security group. Error details {}", t))
                    .block();

            log.debug("Security Group {} created", securityGroup.getName());
        } catch (Exception e) {
            log.error("Fail to create Security Group. Error details {}", e);
            ReflectionUtils.rethrowRuntimeException(e);
        }

    }

    private Mono<SecurityGroupEntity> create(String securityGroupName, ConnectionInfo connectionInfo, String description, String spaceId) {
        return getRules(connectionInfo, description)
                .then(rules ->
                        cloudFoundryClient.securityGroups()
                                .create(CreateSecurityGroupRequest.builder()
                                        .name(securityGroupName)
                                        .rules(rules)
                                        .spaceId(spaceId)
                                        .build()))
                .map(ResourceUtils::getEntity)
                .checkpoint();
    }

    private Mono<List<RuleEntity>> getRules(ConnectionInfo connectionInfo, String description) {
        return Mono.justOrEmpty(connectionInfo.getIPs()
                .map(ip -> RuleEntity.builder()
                        .protocol(DEFAULT_PROTOCOL)
                        .destination(ip)
                        .description(description)
                        .ports(String.valueOf(connectionInfo.getPort()))
                        .build())
                .collect(Collectors.toList()))
                .checkpoint();
    }

    private String getSecurityGroupName(CreateServiceInstanceBindingRequest request) {
        return request.getBindingId();
    }

    private Mono<String> getSpaceId(CloudFoundryClient cloudFoundryClient, String appId) {
        return cloudFoundryClient.applicationsV2().get(GetApplicationRequest.builder()
                .applicationId(appId)
                .build())
                .map(GetApplicationResponse::getEntity)
                .map(ApplicationEntity::getSpaceId)
                .checkpoint();
    }
}
