package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicyName, ResourceAction, ResourceId, ResourceRoleName, ResourceTypeName}
import spray.json.DefaultJsonProtocol.jsonFormat1
import spray.json.RootJsonFormat
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import spray.json.DefaultJsonProtocol._

object FilteredResources {
  implicit val FilteredResourcesFormat: RootJsonFormat[FilteredResources] = jsonFormat1(FilteredResources.apply)

}
case class FilteredResources(resources: Set[FilteredResource])

object FilteredResource {
  implicit val FilteredResourceFormat: RootJsonFormat[FilteredResource] = jsonFormat6(FilteredResource.apply)

}
case class FilteredResource(
    resourceType: ResourceTypeName,
    resourceId: ResourceId,
    policies: Set[AccessPolicyName],
    roles: Set[ResourceRoleName],
    actions: Set[ResourceAction],
    isPublic: Boolean
)