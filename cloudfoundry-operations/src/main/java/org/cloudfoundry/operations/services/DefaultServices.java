/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cloudfoundry.operations.services;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.ApplicationEntity;
import org.cloudfoundry.client.v2.applications.ApplicationResource;
import org.cloudfoundry.client.v2.applications.GetApplicationRequest;
import org.cloudfoundry.client.v2.applications.GetApplicationResponse;
import org.cloudfoundry.client.v2.applications.ListApplicationServiceBindingsRequest;
import org.cloudfoundry.client.v2.servicebindings.CreateServiceBindingRequest;
import org.cloudfoundry.client.v2.servicebindings.CreateServiceBindingResponse;
import org.cloudfoundry.client.v2.servicebindings.DeleteServiceBindingRequest;
import org.cloudfoundry.client.v2.servicebindings.DeleteServiceBindingResponse;
import org.cloudfoundry.client.v2.servicebindings.ListServiceBindingsRequest;
import org.cloudfoundry.client.v2.servicebindings.ServiceBindingResource;
import org.cloudfoundry.client.v2.serviceinstances.LastOperation;
import org.cloudfoundry.client.v2.serviceinstances.ServiceInstanceEntity;
import org.cloudfoundry.client.v2.serviceinstances.ServiceInstanceResource;
import org.cloudfoundry.client.v2.serviceplans.GetServicePlanRequest;
import org.cloudfoundry.client.v2.serviceplans.GetServicePlanResponse;
import org.cloudfoundry.client.v2.services.GetServiceRequest;
import org.cloudfoundry.client.v2.services.GetServiceResponse;
import org.cloudfoundry.client.v2.services.ServiceEntity;
import org.cloudfoundry.client.v2.spaces.ListSpaceApplicationsRequest;
import org.cloudfoundry.client.v2.spaces.ListSpaceServiceInstancesRequest;
import org.cloudfoundry.util.ExceptionUtils;
import org.cloudfoundry.util.JobUtils;
import org.cloudfoundry.util.PaginationUtils;
import org.cloudfoundry.util.ResourceUtils;
import org.cloudfoundry.util.ValidationUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.tuple.Tuple2;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.cloudfoundry.util.tuple.TupleUtils.function;

public final class DefaultServices implements Services {

    private final CloudFoundryClient cloudFoundryClient;

    private final Mono<String> spaceId;

    public DefaultServices(CloudFoundryClient cloudFoundryClient, Mono<String> spaceId) {
        this.cloudFoundryClient = cloudFoundryClient;
        this.spaceId = spaceId;
    }

    @Override
    public Mono<Void> bind(BindServiceInstanceRequest request) {
        return Mono
            .when(ValidationUtils.validate(request), this.spaceId)
            .then(function((request1, spaceId) -> Mono
                .when(
                    getApplicationId(this.cloudFoundryClient, request1.getApplicationName(), spaceId),
                    getSpaceServiceInstanceId(this.cloudFoundryClient, request1.getServiceInstanceName(), spaceId),
                    Mono.just(request1)
                )))
            .then(function((applicationId, serviceInstanceId, request1) -> requestServiceBinding(this.cloudFoundryClient, applicationId, serviceInstanceId, request1.getParameters())))
            .after();
    }

    @Override
    public Flux<ServiceInstance> listInstances() {
        return this.spaceId
            .flatMap(spaceId -> requestSpaceServiceInstances(this.cloudFoundryClient, spaceId)
                .map(resource -> Tuple2.of(spaceId, resource)))
            .flatMap(function((spaceId, resource) -> Mono
                .when(
                    Mono.just(resource),
                    getBoundApplications(this.cloudFoundryClient, ResourceUtils.getId(resource)),
                    getServiceAndPlan(this.cloudFoundryClient, ResourceUtils.getEntity(resource).getServicePlanId())
                )))
            .map(function(DefaultServices::toServiceInstance));
    }

    @Override
    public Mono<Void> unbind(UnbindServiceInstanceRequest request) {
        return Mono
            .when(ValidationUtils.validate(request), this.spaceId)
            .then(function((request1, spaceId) -> Mono
                .when(
                    Mono.just(request1.getServiceInstanceName()),
                    getApplicationId(this.cloudFoundryClient, request1.getApplicationName(), spaceId),
                    getSpaceServiceInstanceId(this.cloudFoundryClient, request1.getServiceInstanceName(), spaceId)
                )))
            .then(function((serviceInstanceName, applicationId, serviceInstanceId) -> getServiceBindingId(this.cloudFoundryClient, applicationId, serviceInstanceId, serviceInstanceName)))
            .then(serviceBindingId -> deleteServiceBinding(this.cloudFoundryClient, serviceBindingId))
            .after();
    }

    private static String convertInstanceType(String type) {
        if ("managed_service_instance".equals(type)) return "managed";
        if ("user_provided_service_instance".equals(type)) return "user-provided";
        return "unknown";
    }

    private static String convertLastOperation(LastOperation lastOperation) {
        return String.format("%s %s", lastOperation.getType(), lastOperation.getState());
    }

    private static Mono<Void> deleteServiceBinding(CloudFoundryClient cloudFoundryClient, String serviceBindingId) {
        return requestDeleteServiceBinding(cloudFoundryClient, serviceBindingId)
            .map(ResourceUtils::getId)
            .then(jobId -> JobUtils.waitForCompletion(cloudFoundryClient, jobId));
    }

    private static Mono<ApplicationResource> getApplication(CloudFoundryClient cloudFoundryClient, String applicationName, String spaceId) {
        return requestApplications(cloudFoundryClient, applicationName, spaceId)
            .single()
            .otherwise(ExceptionUtils.replace(NoSuchElementException.class, () -> ExceptionUtils.illegalArgument("Application %s does not exist", applicationName)));
    }

    private static Mono<String> getApplicationId(CloudFoundryClient cloudFoundryClient, String applicationName, String spaceId) {
        return getApplication(cloudFoundryClient, applicationName, spaceId)
            .map(ResourceUtils::getId);
    }

    private static Mono<List<String>> getBoundApplications(CloudFoundryClient cloudFoundryClient, String serviceInstanceId) {
        return requestListServiceBindings(cloudFoundryClient, serviceInstanceId)
            .map(resource -> ResourceUtils.getEntity(resource).getApplicationId())
            .flatMap(applicationId -> requestApplication(cloudFoundryClient, applicationId))
            .map(ResourceUtils::getEntity)
            .map(ApplicationEntity::getName)
            .toList();
    }

    private static Mono<Tuple2<Optional<String>, Optional<String>>> getServiceAndPlan(CloudFoundryClient cloudFoundryClient, String servicePlanId) {
        return Optional.ofNullable(servicePlanId)
            .map(servicePlanId1 -> requestServicePlan(cloudFoundryClient, servicePlanId1)
                .map(ResourceUtils::getEntity)
                .then(servicePlanEntity -> Mono
                    .when(
                        getServiceName(cloudFoundryClient, servicePlanEntity.getServiceId())
                            .map(Optional::<String>of),
                        Mono.just(Optional.of(servicePlanEntity.getName()))
                    ))
            )
            .orElse(Mono.just(Tuple2.of(Optional.empty(), Optional.empty())));
    }

    private static Mono<String> getServiceBindingId(CloudFoundryClient cloudFoundryClient, String applicationId, String serviceInstanceId, String serviceInstanceName) {
        return requestListApplicationServiceBindings(cloudFoundryClient, applicationId, serviceInstanceId)
            .singleOrEmpty()
            .otherwiseIfEmpty(ExceptionUtils.illegalState("Service instance %s is not bound to application", serviceInstanceName))
            .map(ResourceUtils::getId);
    }

    private static Mono<String> getServiceName(CloudFoundryClient cloudFoundryClient, String serviceId) {
        return requestService(cloudFoundryClient, serviceId)
            .map(ResourceUtils::getEntity)
            .map(ServiceEntity::getLabel);
    }

    private static Mono<ServiceInstanceResource> getSpaceServiceInstance(CloudFoundryClient cloudFoundryClient, String serviceInstanceName, String spaceId) {
        return requestSpaceServiceInstancesByName(cloudFoundryClient, serviceInstanceName, spaceId)
            .single()
            .otherwise(ExceptionUtils.replace(NoSuchElementException.class, () -> ExceptionUtils.illegalArgument("Service instance %s does not exist", serviceInstanceName)));
    }

    private static Mono<String> getSpaceServiceInstanceId(CloudFoundryClient cloudFoundryClient, String serviceInstanceName, String spaceId) {
        return getSpaceServiceInstance(cloudFoundryClient, serviceInstanceName, spaceId)
            .map(ResourceUtils::getId);
    }

    private static Mono<GetApplicationResponse> requestApplication(CloudFoundryClient cloudFoundryClient, String applicationId) {
        return cloudFoundryClient.applicationsV2().get(GetApplicationRequest.builder()
            .applicationId(applicationId)
            .build());
    }

    private static Flux<ApplicationResource> requestApplications(CloudFoundryClient cloudFoundryClient, String application, String spaceId) {
        return PaginationUtils
            .requestResources(page -> cloudFoundryClient.spaces()
                .listApplications(ListSpaceApplicationsRequest.builder()
                    .name(application)
                    .spaceId(spaceId)
                    .page(page)
                    .build()));
    }

    private static Mono<DeleteServiceBindingResponse> requestDeleteServiceBinding(CloudFoundryClient cloudFoundryClient, String serviceBindingId) {
        return cloudFoundryClient.serviceBindings()
            .delete(DeleteServiceBindingRequest.builder()
                .serviceBindingId(serviceBindingId)
                .async(true)
                .build());
    }

    private static Flux<ServiceBindingResource> requestListApplicationServiceBindings(CloudFoundryClient cloudFoundryClient, String applicationId, String serviceInstanceId) {
        return PaginationUtils
            .requestResources(page -> cloudFoundryClient.applicationsV2()
                .listServiceBindings(ListApplicationServiceBindingsRequest.builder()
                    .page(page)
                    .applicationId(applicationId)
                    .serviceInstanceId(serviceInstanceId)
                    .build()));
    }

    private static Flux<ServiceBindingResource> requestListServiceBindings(CloudFoundryClient cloudFoundryClient, String serviceInstanceId) {
        return PaginationUtils
            .requestResources(page -> cloudFoundryClient.serviceBindings()
                .list(ListServiceBindingsRequest.builder()
                    .page(page)
                    .serviceInstanceId(serviceInstanceId)
                    .build()));
    }

    private static Mono<GetServiceResponse> requestService(CloudFoundryClient cloudFoundryClient, String serviceId) {
        return cloudFoundryClient.services()
            .get(GetServiceRequest.builder()
                .serviceId(serviceId)
                .build());
    }

    private static Mono<CreateServiceBindingResponse> requestServiceBinding(CloudFoundryClient cloudFoundryClient, String applicationId, String serviceInstanceId, Map<String, Object> parameters) {
        return cloudFoundryClient.serviceBindings()
            .create(CreateServiceBindingRequest.builder()
                .applicationId(applicationId)
                .parameters(parameters)
                .serviceInstanceId(serviceInstanceId)
                .build());
    }

    private static Mono<GetServicePlanResponse> requestServicePlan(CloudFoundryClient cloudFoundryClient, String servicePlanId) {
        return cloudFoundryClient.servicePlans()
            .get(GetServicePlanRequest.builder()
                .servicePlanId(servicePlanId)
                .build());
    }

    private static Flux<ServiceInstanceResource> requestSpaceServiceInstances(CloudFoundryClient cloudFoundryClient, String spaceId) {
        return PaginationUtils
            .requestResources(page -> cloudFoundryClient.spaces()
                .listServiceInstances(ListSpaceServiceInstancesRequest.builder()
                    .page(page)
                    .returnUserProvidedServiceInstances(true)
                    .spaceId(spaceId)
                    .build()));
    }

    private static Flux<ServiceInstanceResource> requestSpaceServiceInstancesByName(CloudFoundryClient cloudFoundryClient, String serviceInstanceName, String spaceId) {
        return PaginationUtils
            .requestResources(page -> cloudFoundryClient.spaces()
                .listServiceInstances(ListSpaceServiceInstancesRequest.builder()
                    .page(page)
                    .returnUserProvidedServiceInstances(true)
                    .name(serviceInstanceName)
                    .spaceId(spaceId)
                    .build()));
    }

    private static ServiceInstance toServiceInstance(ServiceInstanceResource resource, List<String> applications, Tuple2<Optional<String>, Optional<String>> serviceAndPlan) {
        ServiceInstanceEntity entity = resource.getEntity();
        Optional<String> service = serviceAndPlan.t1;
        Optional<String> plan = serviceAndPlan.t2;

        return ServiceInstance.builder()
            .lastOperation(Optional.ofNullable(entity.getLastOperation()).map(DefaultServices::convertLastOperation).orElse(null))
            .name(entity.getName())
            .plan(plan.orElse(null))
            .type(convertInstanceType(entity.getType()))
            .service(service.orElse(null))
            .applications(applications)
            .build();
    }

}
