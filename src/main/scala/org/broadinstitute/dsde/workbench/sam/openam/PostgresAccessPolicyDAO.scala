package org.broadinstitute.dsde.workbench.sam.openam

import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.{DbReference, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.LoadResourceAuthDomainResult.{Constrained, NotConstrained, ResourceNotFound}
import org.broadinstitute.dsde.workbench.sam.util.DatabaseSupport
import org.postgresql.util.PSQLException
import scalikejdbc._

import scala.concurrent.ExecutionContext

class PostgresAccessPolicyDAO(protected val dbRef: DbReference,
                              protected val ecForDatabaseIO: ExecutionContext)(implicit executionContext: ExecutionContext) extends AccessPolicyDAO with DatabaseSupport with LazyLogging {

  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  // This method obtains an EXCLUSIVE lock on the ResourceType table because if we boot multiple Sam instances at once,
  // they will all try to (re)create ResourceTypes at the same time and could collide. The lock is automatically
  // released at the end of the transaction.
  override def createResourceType(resourceType: ResourceType): IO[ResourceType] = {
    val uniqueActions = resourceType.roles.flatMap(_.actions)
    runInTransaction { implicit session =>
      samsql"lock table ${ResourceTypeTable.table} IN EXCLUSIVE MODE".execute().apply()
      val resourceTypePK = insertResourceType(resourceType.name)

      insertActionPatterns(resourceType.actionPatterns, resourceTypePK)
      insertRoles(resourceType.roles, resourceTypePK)
      insertActions(uniqueActions, resourceTypePK)
      insertRoleActions(resourceType.roles, resourceTypePK)

      resourceType
    }
  }

  private def insertRoleActions(roles: Set[ResourceRole], resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    // Load Actions and Roles from DB because we need their DB IDs
    val resourceTypeActions = selectActionsForResourceType(resourceTypePK)
    val resourceTypeRoles = selectRolesForResourceType(resourceTypePK)

    val roleActionValues = roles.flatMap { role =>
      val maybeRolePK = resourceTypeRoles.find(r => r.role == role.roleName).map(_.id)
      val actionPKs = resourceTypeActions.filter(rta => role.actions.contains(rta.action)).map(_.id)

      val rolePK = maybeRolePK.getOrElse(throw new WorkbenchException(s"Cannot add Role Actions because Role '${role.roleName}' does not exist for ResourceType: ${resourceTypePK}"))

      actionPKs.map(actionPK => samsqls"(${rolePK}, ${actionPK})")
    }

    if (roleActionValues.nonEmpty) {
      val insertQuery =
        samsql"""insert into ${RoleActionTable.table}(${RoleActionTable.column.resourceRoleId}, ${RoleActionTable.column.resourceActionId})
                    values ${roleActionValues}
                 on conflict do nothing"""
      insertQuery.update().apply()
    } else {
      0
    }
  }

  private def selectActionsForResourceType(resourceTypePK: ResourceTypePK)(implicit session: DBSession): List[ResourceActionRecord] = {
    val rat = ResourceActionTable.syntax("rat")
    val actionsQuery =
      samsql"""select ${rat.result.*}
               from ${ResourceActionTable as rat}
               where ${rat.resourceTypeId} = ${resourceTypePK}"""

    actionsQuery.map(ResourceActionTable(rat.resultName)).list().apply()
  }

  private def selectRolesForResourceType(resourceTypePK: ResourceTypePK)(implicit session: DBSession): List[ResourceRoleRecord] = {
    val rrt = ResourceRoleTable.syntax("rrt")
    val actionsQuery =
      samsql"""select ${rrt.result.*}
               from ${ResourceRoleTable as rrt}
               where ${rrt.resourceTypeId} = ${resourceTypePK}"""

    actionsQuery.map(ResourceRoleTable(rrt.resultName)).list().apply()
  }

  private def insertRoles(roles: Set[ResourceRole], resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    val roleValues = roles.map(role => samsqls"(${resourceTypePK}, ${role.roleName})")
    val insertRolesQuery =
      samsql"""insert into ${ResourceRoleTable.table}(${ResourceRoleTable.column.resourceTypeId}, ${ResourceRoleTable.column.role})
                  values ${roleValues}
               on conflict do nothing"""

    insertRolesQuery.update().apply()
  }

  private def insertActions(actions: Set[ResourceAction], resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    if (actions.isEmpty) {
      return 0
    } else {
      val uniqueActionValues = actions.map { action =>
        samsqls"(${resourceTypePK}, ${action})"
      }

      val insertActionQuery =
        samsql"""insert into ${ResourceActionTable.table}(${ResourceActionTable.column.resourceTypeId}, ${ResourceActionTable.column.action})
                    values ${uniqueActionValues}
                 on conflict do nothing"""

      insertActionQuery.update().apply()
    }
  }

  /**
    * Performs an UPSERT when a record already exists for this exact ActionPattern for this ResourceType.  Query will
    * rerwrite the `description` and `isAuthDomainConstrainable` columns if the ResourceTypeActionPattern already
    * exists.
    */
  private def insertActionPatterns(actionPatterns: Set[ResourceActionPattern], resourceTypePK: ResourceTypePK)(implicit session: DBSession) = {
    val resourceActionPatternTableColumn = ResourceActionPatternTable.column
    val actionPatternValues = actionPatterns map { actionPattern =>
      samsqls"(${resourceTypePK}, ${actionPattern.value}, ${actionPattern.description}, ${actionPattern.authDomainConstrainable})"
    }

    val actionPatternQuery =
      samsql"""insert into ${ResourceActionPatternTable.table}
                  (${resourceActionPatternTableColumn.resourceTypeId},
                   ${resourceActionPatternTableColumn.actionPattern},
                   ${resourceActionPatternTableColumn.description},
                   ${resourceActionPatternTableColumn.isAuthDomainConstrainable})
                  values ${actionPatternValues}
               on conflict (${resourceActionPatternTableColumn.resourceTypeId}, ${resourceActionPatternTableColumn.actionPattern})
                  do update
                      set ${resourceActionPatternTableColumn.description} = EXCLUDED.${resourceActionPatternTableColumn.description},
                          ${resourceActionPatternTableColumn.isAuthDomainConstrainable} = EXCLUDED.${resourceActionPatternTableColumn.isAuthDomainConstrainable}"""
    actionPatternQuery.update().apply()
  }

  // This method needs to always return the ResourceTypePK, regardless of whether we just inserted the ResourceType or
  // it already existed.  We do this by using a `RETURNING` statement.  This statement will only return values for rows
  // that were created or updated.  Therefore, `ON CONFLICT` will update the row with the same name value that was there
  // before and the previously existing ResourceType id will be returned.
  private def insertResourceType(resourceTypeName: ResourceTypeName)(implicit session: DBSession): ResourceTypePK = {
    val resourceTypeTableColumn = ResourceTypeTable.column
    val insertResourceTypeQuery =
      samsql"""insert into ${ResourceTypeTable.table} (${resourceTypeTableColumn.name})
                  values (${resourceTypeName.value})
               on conflict (${ResourceTypeTable.column.name})
                  do update set ${ResourceTypeTable.column.name}=EXCLUDED.${ResourceTypeTable.column.name}
               returning ${ResourceTypeTable.column.id}"""

    ResourceTypePK(insertResourceTypeQuery.updateAndReturnGeneratedKey().apply())
  }

  // 1. Create Resource
  // 2. Create the entries in the join table for the auth domains
  override def createResource(resource: Resource): IO[Resource] = {
    runInTransaction { implicit session =>
      val resourcePK = insertResource(resource)

      if (resource.authDomain.nonEmpty) {
        insertAuthDomainsForResource(resourcePK, resource.authDomain)
      }

      resource
    }.recoverWith {
      case sqlException: PSQLException => {
        logger.debug(s"createResource psql exception on resource $resource", sqlException)
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"Resource ${resource.resourceTypeName} failed.", sqlException)))
      }
    }
  }

  private def insertResource(resource: Resource)(implicit session: DBSession): ResourcePK = {
    val resourceTableColumn = ResourceTable.column
    val insertResourceQuery =
      samsql"""insert into ${ResourceTable.table} (${resourceTableColumn.name}, ${resourceTableColumn.resourceTypeId})
               values (${resource.resourceId}, (${loadResourceTypePK(resource.resourceTypeName)}))"""
    ResourcePK(insertResourceQuery.updateAndReturnGeneratedKey().apply())
  }

  private def insertAuthDomainsForResource(resourcePK: ResourcePK, authDomains: Set[WorkbenchGroupName])(implicit session: DBSession): Int = {
    val authDomainValues = authDomains.map { authDomain =>
      samsqls"(${resourcePK}, (${GroupTable.groupPKQueryForGroup(authDomain)}))"
    }

    val authDomainColumn = AuthDomainTable.column
    val insertAuthDomainQuery = samsql"insert into ${AuthDomainTable.table} (${authDomainColumn.resourceId}, ${authDomainColumn.groupId}) values ${authDomainValues}"
    insertAuthDomainQuery.update().apply()
  }

  private def loadResourceTypePK(resourceTypeName: ResourceTypeName, resourceTypeTableAlias: String = "rt"): SQLSyntax = {
    val rt = ResourceTypeTable.syntax(resourceTypeTableAlias)
    samsqls"""select ${rt.id} from ${ResourceTypeTable as rt} where ${rt.name} = ${resourceTypeName}"""
  }

  override def deleteResource(resource: FullyQualifiedResourceId): IO[Unit] = {
    runInTransaction { implicit session =>
      val r = ResourceTable.syntax("r")
      samsql"delete from ${ResourceTable as r} where ${r.name} = ${resource.resourceId} and ${r.resourceTypeId} = (${loadResourceTypePK(resource.resourceTypeName)})".update().apply()
    }
  }

  override def loadResourceAuthDomain(resource: FullyQualifiedResourceId): IO[LoadResourceAuthDomainResult] = {
    runInTransaction { implicit session =>
      val ad = AuthDomainTable.syntax("ad")
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")
      val g = GroupTable.syntax("g")

      // left joins below so we can detect the difference between a resource does not exist vs.
      // a resource exists but does not have any auth domains
      val query = samsql"""select ${g.result.name}
              from ${ResourceTable as r}
              join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
              left outer join ${AuthDomainTable as ad} on ${r.id} = ${ad.resourceId}
              left outer join ${GroupTable as g} on  ${ad.groupId} = ${g.id}
              where ${r.name} = ${resource.resourceId} and ${rt.name} = ${resource.resourceTypeName}"""

      val results = query.map(_.stringOpt(g.resultName.name)).list().apply()

      // there are 3 expected forms for the results:
      //   1) empty list - nothing matched inner joins
      //   2) 1 row with a None - nothing matched left joins
      //   3) non-empty list of non-None group names
      // there are 2 unexpected forms
      //   a) more than one row, all Nones
      //   b) more than one row, some Nones
      // a is treated like 2 and b is treated like 3 with Nones removed
      val authDomains = NonEmptyList.fromList(results.flatten) // flatten removes Nones
      authDomains match {
        case None =>
          if (results.isEmpty) {
            ResourceNotFound // case 1
          } else {
            NotConstrained  // case 2
          }
        case Some(nel) => Constrained(nel.map(WorkbenchGroupName)) // case 3
      }
    }
  }

  override def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity): IO[Set[Resource]] = {
    import SamTypeBinders._

    runInTransaction { implicit session =>
      val r = ResourceTable.syntax("r")
      val ad = AuthDomainTable.syntax("ad")
      val g = GroupTable.syntax("g")
      val rt = ResourceTypeTable.syntax("rt")
      val p = PolicyTable.syntax("p")

      val constrainedResourcesPKs = groupId match {
        case group: WorkbenchGroupName =>
          samsqls"""select ${ad.result.resourceId}
           from ${AuthDomainTable as ad}
           join ${GroupTable as g} on ${g.id} = ${ad.groupId}
           where ${g.name} = ${group}"""
        case policy: FullyQualifiedPolicyId =>
          samsqls"""select ${ad.result.resourceId}
           from ${AuthDomainTable as ad}
           join ${PolicyTable as p} on ${ad.groupId} = ${p.groupId}
           join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
           join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
           where ${policy.accessPolicyName} = ${p.name}
           and ${policy.resource.resourceId} = ${r.name}
           and ${policy.resource.resourceTypeName} = ${rt.name}"""
      }

      val results = samsql"""select ${rt.result.name}, ${r.result.name}, ${g.result.name}
                      from ${ResourceTable as r}
                      join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
                      left join ${AuthDomainTable as ad} on ${r.id} = ${ad.resourceId}
                      left join ${GroupTable as g} on ${ad.groupId} = ${g.id}
                      where ${r.id} in (${constrainedResourcesPKs})"""
        .map(rs => (rs.get[ResourceTypeName](rt.resultName.name), rs.get[ResourceId](r.resultName.name), rs.get[WorkbenchGroupName](g.resultName.name))).list().apply()

      val resultsByResource = results.groupBy(result => (result._1, result._2))
      resultsByResource.map {
        case ((resourceTypeName, resourceId), groupedResults) => Resource(resourceTypeName, resourceId, groupedResults.collect {
          case (_, _, authDomainGroupName) => authDomainGroupName
        }.toSet)
      }.toSet
    }
  }

  override def createPolicy(policy: AccessPolicy): IO[AccessPolicy] = ???
  override def deletePolicy(policy: FullyQualifiedPolicyId): IO[Unit] = ???
  override def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId): IO[Option[AccessPolicy]] = ???
  override def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject]): IO[Unit] = ???
  override def overwritePolicy(newPolicy: AccessPolicy): IO[AccessPolicy] = ???
  override def listPublicAccessPolicies(resourceTypeName: ResourceTypeName): IO[Stream[ResourceIdAndPolicyName]] = ???
  override def listPublicAccessPolicies(resource: FullyQualifiedResourceId): IO[Stream[AccessPolicy]] = ???

  override def listResourcesWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId]): IO[Set[Resource]] = {
    import SamTypeBinders._

    runInTransaction { implicit session =>
      val r = ResourceTable.syntax("r")
      val ad = AuthDomainTable.syntax("ad")
      val g = GroupTable.syntax("g")
      val rt = ResourceTypeTable.syntax("rt")

      val results = samsql"""select ${r.result.name}, ${g.result.name}
                      from ${ResourceTable as r}
                      join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
                      left join ${AuthDomainTable as ad} on ${r.id} = ${ad.resourceId}
                      left join ${GroupTable as g} on ${ad.groupId} = ${g.id}
                      where ${r.name} in (${resourceId}) and ${rt.name} = ${resourceTypeName}"""
        .map(rs => (rs.get[ResourceId](r.resultName.name), rs.stringOpt(g.resultName.name).map(WorkbenchGroupName))).list().apply()

      val resultsByResource = results.groupBy(_._1)
      resultsByResource.map {
        case (resource, groupedResults) => Resource(resourceTypeName, resource, groupedResults.collect {
          case (_, Some(authDomainGroupName)) => authDomainGroupName
        }.toSet)
      }.toSet
    }
  }

  override def listResourceWithAuthdomains(resourceId: FullyQualifiedResourceId): IO[Option[Resource]] = {
    listResourcesWithAuthdomains(resourceId.resourceTypeName, Set(resourceId.resourceId)).map(_.headOption)
  }

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): IO[Set[ResourceIdAndPolicyName]] = ???
  override def listAccessPolicies(resource: FullyQualifiedResourceId): IO[Stream[AccessPolicy]] = ???
  override def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId): IO[Set[AccessPolicy]] = ???
  override def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId): IO[Set[WorkbenchUser]] = ???
  override def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, isPublic: Boolean): IO[Unit] = ???
  override def evictIsMemberOfCache(subject: WorkbenchSubject): IO[Unit] = ???
}
